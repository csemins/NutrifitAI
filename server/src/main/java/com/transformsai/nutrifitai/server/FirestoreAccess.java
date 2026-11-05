package com.transformsai.nutrifitai.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * AI access lives in Firebase: Firestore aiAccess/{uid}.allowed, which only admins (or a verified pre-allowed
 * email) can set true — see firestore.rules. The server reads that one document through the Firestore REST API
 * with the CALLER's own ID token, so it needs no Firebase admin credentials and the security rules still apply.
 * Answers are cached briefly to keep the extra read off most AI calls.
 */
final class FirestoreAccess implements Api.AccessCheck {
    static final long ALLOWED_TTL_MS = 60_000, DENIED_TTL_MS = 15_000;

    private record Entry(boolean allowed, long until) { }

    private final String base;
    /** Unsigned Auth-emulator tokens are checked against the Firestore emulator (development only). */
    private final String emulatorBase;
    private final LongSupplier clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    FirestoreAccess(Config cfg, LongSupplier clock) {
        this("https://firestore.googleapis.com", cfg.firestoreEmulator.isEmpty() ? null : "http://" + cfg.firestoreEmulator,
                cfg.projectId, clock);
    }

    FirestoreAccess(String origin, String emulatorOrigin, String projectId, LongSupplier clock) {
        String docs = "/v1/projects/" + projectId + "/databases/(default)/documents/aiAccess/";
        this.base = origin + docs;
        this.emulatorBase = emulatorOrigin == null ? null : emulatorOrigin + docs;
        this.clock = clock;
    }

    @Override
    public boolean allowed(String uid, String idToken) throws IOException {
        long now = clock.getAsLong();
        Entry e = cache.get(uid);
        if (e != null && e.until > now) return e.allowed;
        boolean allowed = fetch(uid, idToken);
        if (cache.size() > 20_000) cache.entrySet().removeIf(x -> x.getValue().until <= now); // bounded memory
        cache.put(uid, new Entry(allowed, now + (allowed ? ALLOWED_TTL_MS : DENIED_TTL_MS)));
        return allowed;
    }

    private boolean fetch(String uid, String idToken) throws IOException {
        String root = emulatorBase != null && unsigned(idToken) ? emulatorBase : base;
        HttpRequest req = HttpRequest.newBuilder(URI.create(root + uid))
                .header("Authorization", "Bearer " + idToken)
                .timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (r.statusCode() == 404 || r.statusCode() == 403) return false; // no record yet, or not readable
        if (r.statusCode() != 200) throw new IOException("firestore " + r.statusCode());
        return parseAllowed(r.body());
    }

    /** Auth-emulator tokens use alg "none" (the verifier only accepts them in emulator mode). */
    static boolean unsigned(String token) {
        try {
            String header = new String(java.util.Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
                    java.nio.charset.StandardCharsets.UTF_8);
            return "none".equals(JsonParser.parseString(header).getAsJsonObject().get("alg").getAsString());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Firestore REST document: {"fields": {"allowed": {"booleanValue": true}, ...}}. */
    static boolean parseAllowed(String body) {
        try {
            JsonObject f = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("fields");
            JsonObject a = f == null ? null : f.getAsJsonObject("allowed");
            return a != null && a.has("booleanValue") && a.get("booleanValue").getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
