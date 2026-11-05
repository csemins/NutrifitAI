package com.transformsai.nutrifitai.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies Firebase ID tokens (RS256 JWTs signed by securetoken@system.gserviceaccount.com).
 * Only Google's PUBLIC certificates are needed, so the server holds no admin credentials.
 */
public final class TokenVerifier {
    public static final String CERTS_URL =
            "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com";

    public static final class AuthError extends Exception {
        public AuthError(String m) {
            super(m);
        }
    }

    private final String projectId;
    private final boolean acceptEmulatorTokens;
    /** kid -> public key; injectable for tests. */
    private final Function<String, PublicKey> keys;

    public TokenVerifier(String projectId, boolean acceptEmulatorTokens, Function<String, PublicKey> keys) {
        this.projectId = projectId;
        this.acceptEmulatorTokens = acceptEmulatorTokens;
        this.keys = keys;
    }

    public static TokenVerifier google(String projectId, boolean acceptEmulatorTokens) {
        return new TokenVerifier(projectId, acceptEmulatorTokens, new GoogleCerts()::key);
    }

    /** @return the Firebase uid. */
    public String verify(String token, long nowMs) throws AuthError {
        return identify(token, nowMs).uid();
    }

    /** Verifies the token and returns the uid with the email claims used for AI access. */
    public Identity identify(String token, long nowMs) throws AuthError {
        if (token == null || token.isEmpty() || token.length() > 4096) throw new AuthError("missing token");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) throw new AuthError("malformed token");
        JsonObject header, payload;
        try {
            header = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)).getAsJsonObject();
            payload = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new AuthError("malformed token");
        }
        long now = nowMs / 1000;
        if (!projectId.equals(str(payload, "aud"))) throw new AuthError("wrong audience");
        if (!("https://securetoken.google.com/" + projectId).equals(str(payload, "iss"))) throw new AuthError("wrong issuer");
        String sub = str(payload, "sub");
        if (sub == null || sub.isEmpty() || sub.length() > 128) throw new AuthError("bad subject");
        Long exp = num(payload, "exp"), iat = num(payload, "iat");
        if (exp == null || exp <= now) throw new AuthError("expired");
        if (iat == null || iat > now + 300) throw new AuthError("issued in future");

        String alg = str(header, "alg");
        Identity id = new Identity(sub, str(payload, "email"),
                payload.has("email_verified") && payload.get("email_verified").isJsonPrimitive() && payload.get("email_verified").getAsBoolean(),
                str(payload, "name"));
        if (acceptEmulatorTokens && "none".equals(alg)) return id;
        String kid = str(header, "kid");
        if (!"RS256".equals(alg) || kid == null) throw new AuthError("bad algorithm");
        PublicKey key = keys.apply(kid);
        if (key == null) throw new AuthError("unknown key");
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(key);
            s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!s.verify(Base64.getUrlDecoder().decode(parts[2]))) throw new AuthError("bad signature");
        } catch (AuthError e) {
            throw e;
        } catch (Exception e) {
            throw new AuthError("bad signature");
        }
        return id;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isString() ? o.get(k).getAsString() : null;
    }

    private static Long num(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isNumber() ? o.get(k).getAsLong() : null;
    }

    /** Fetches and caches Google's signing certificates, honouring Cache-Control max-age. */
    static final class GoogleCerts {
        private static final Pattern MAX_AGE = Pattern.compile("max-age=(\\d+)");
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private Map<String, PublicKey> cache = new HashMap<>();
        private long expires;

        synchronized PublicKey key(String kid) {
            if (System.currentTimeMillis() >= expires || !cache.containsKey(kid)) refresh();
            return cache.get(kid);
        }

        private void refresh() {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(CERTS_URL)).timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return;
                JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                Map<String, PublicKey> m = new HashMap<>();
                for (String k : o.keySet()) {
                    m.put(k, cf.generateCertificate(new ByteArrayInputStream(o.get(k).getAsString().getBytes(StandardCharsets.US_ASCII))).getPublicKey());
                }
                cache = m;
                Matcher x = MAX_AGE.matcher(r.headers().firstValue("cache-control").orElse(""));
                expires = System.currentTimeMillis() + (x.find() ? Long.parseLong(x.group(1)) * 1000 : 3_600_000);
            } catch (Exception e) {
                // keep the previous cache; verification fails closed for unknown keys
            }
        }
    }
}
