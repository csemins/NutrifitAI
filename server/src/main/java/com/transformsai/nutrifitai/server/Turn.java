package com.transformsai.nutrifitai.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.function.LongSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Relay (TURN) credentials for calls. Phones on mobile data are often behind carrier NAT, where a direct WebRTC
 * connection cannot form; a TURN relay carries the media instead. The relay's secret never leaves the server:
 * signed-in users get short-lived credentials from GET /v1/turn. Supported, in order of preference:
 * <ul>
 * <li>Cloudflare Realtime TURN: CF_TURN_KEY_ID + CF_TURN_KEY_API_TOKEN (credentials minted per request).</li>
 * <li>A TURN server with a shared REST secret (coturn use-auth-secret): TURN_URLS + TURN_SECRET.</li>
 * <li>A TURN server with fixed credentials: TURN_URLS + TURN_USERNAME + TURN_CREDENTIAL.</li>
 * </ul>
 * With none configured the answer is an empty list and calls use direct connections only.
 * <p>
 * Load: call media never passes through this server (it goes phone to phone, or through the relay). Cloudflare
 * credentials are not tied to a user, so one set is minted and shared by every caller until half its lifetime has
 * passed; one thread refreshes it while the others keep using the current set. If Cloudflare is unreachable, the
 * current set is used while still valid, and new attempts are spaced out instead of retried on every call.
 */
final class Turn {
    static final String CLOUDFLARE = "https://rtc.live.cloudflare.com";

    private final Config cfg;
    private final String cloudflareOrigin;
    private final LongSupplier clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final long RETRY_AFTER_FAILURE_MS = 15_000;

    private record Minted(JsonArray servers, long mintedAt, long expiresAt) { }

    private volatile Minted shared;
    private volatile long lastFailureAt = Long.MIN_VALUE / 2;
    private final Object refreshLock = new Object();
    int cloudflareCalls;

    Turn(Config cfg, String cloudflareOrigin, LongSupplier clock) {
        this.cfg = cfg;
        this.cloudflareOrigin = cloudflareOrigin;
        this.clock = clock;
    }

    boolean configured() {
        return cloudflare() || !cfg.turnUrls.isEmpty();
    }

    private boolean cloudflare() {
        return !cfg.cfTurnKeyId.isEmpty() && !cfg.cfTurnToken.isEmpty();
    }

    /** {"iceServers":[{urls:[...],username,credential}], "ttl": seconds}. */
    JsonObject iceServers(String uid) throws IOException {
        JsonObject out = new JsonObject();
        JsonArray list = new JsonArray();
        out.addProperty("ttl", cfg.turnTtlSeconds);
        if (cloudflare()) {
            Minted m = sharedCredentials();
            for (JsonElement e : m.servers) list.add(e);
            out.addProperty("ttl", Math.max(60, (m.expiresAt - clock.getAsLong()) / 1000));
        } else if (!cfg.turnUrls.isEmpty()) {
            JsonObject s = new JsonObject();
            JsonArray urls = new JsonArray();
            for (String u : cfg.turnUrls.split(",")) if (validUrl(u.trim())) urls.add(u.trim());
            s.add("urls", urls);
            if (!cfg.turnSecret.isEmpty()) {
                // coturn REST API: username "<expiry>:<id>", password base64(HMAC-SHA1(secret, username))
                String user = (clock.getAsLong() / 1000 + cfg.turnTtlSeconds) + ":" + Api.hash(uid);
                s.addProperty("username", user);
                s.addProperty("credential", hmac(cfg.turnSecret, user));
            } else {
                s.addProperty("username", cfg.turnUsername);
                s.addProperty("credential", cfg.turnCredential);
            }
            if (!urls.isEmpty()) list.add(s);
        }
        out.add("iceServers", list);
        return out;
    }

    /** The shared Cloudflare set: reused until half-life, refreshed by one thread at a time. */
    private Minted sharedCredentials() throws IOException {
        long now = clock.getAsLong();
        Minted m = shared;
        if (m != null && now < m.mintedAt + (m.expiresAt - m.mintedAt) / 2) return m;
        synchronized (refreshLock) {
            m = shared;
            now = clock.getAsLong();
            if (m != null && now < m.mintedAt + (m.expiresAt - m.mintedAt) / 2) return m; // refreshed meanwhile
            boolean usable = m != null && now < m.expiresAt - 60_000;
            if (now - lastFailureAt < RETRY_AFTER_FAILURE_MS) {
                if (usable) return m;
                throw new IOException("cloudflare turn recently failed");
            }
            try {
                JsonArray servers = fromCloudflare();
                if (servers.isEmpty()) throw new IOException("cloudflare turn: empty");
                m = new Minted(servers, now, now + cfg.turnTtlSeconds * 1000L);
                shared = m;
                return m;
            } catch (IOException e) {
                lastFailureAt = now;
                if (usable) return m;
                throw e;
            }
        }
    }

    private JsonArray fromCloudflare() throws IOException {
        cloudflareCalls++;
        JsonObject body = new JsonObject();
        body.addProperty("ttl", cfg.turnTtlSeconds);
        HttpRequest req = HttpRequest.newBuilder(URI.create(cloudflareOrigin + "/v1/turn/keys/" + cfg.cfTurnKeyId
                        + "/credentials/generate-ice-servers"))
                .header("Authorization", "Bearer " + cfg.cfTurnToken)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(8))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (r.statusCode() / 100 != 2) throw new IOException("cloudflare turn " + r.statusCode());
        return sanitize(JsonParser.parseString(r.body()).getAsJsonObject().get("iceServers"));
    }

    /** Keeps only well-formed STUN/TURN entries (an object or a list of objects), dropping anything else. */
    static JsonArray sanitize(JsonElement servers) {
        JsonArray out = new JsonArray();
        if (servers == null || servers.isJsonNull()) return out;
        JsonArray in = servers.isJsonArray() ? servers.getAsJsonArray() : new JsonArray();
        if (servers.isJsonObject()) in.add(servers);
        for (JsonElement e : in) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            JsonArray urls = new JsonArray();
            JsonElement u = o.get("urls");
            if (u != null && u.isJsonPrimitive() && validUrl(u.getAsString())) urls.add(u.getAsString());
            if (u != null && u.isJsonArray()) {
                for (JsonElement x : u.getAsJsonArray()) {
                    if (x.isJsonPrimitive() && validUrl(x.getAsString())) urls.add(x.getAsString());
                }
            }
            if (urls.isEmpty()) continue;
            JsonObject s = new JsonObject();
            s.add("urls", urls);
            if (o.has("username") && o.get("username").isJsonPrimitive()) s.addProperty("username", o.get("username").getAsString());
            if (o.has("credential") && o.get("credential").isJsonPrimitive()) s.addProperty("credential", o.get("credential").getAsString());
            out.add(s);
        }
        return out;
    }

    static boolean validUrl(String u) {
        return u != null && u.length() <= 200 && (u.startsWith("stun:") || u.startsWith("turn:") || u.startsWith("turns:"));
    }

    static String hmac(String secret, String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA1");
            m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(m.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
