package com.transformsai.nutrifitai.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Server configuration from environment variables, with an optional .env file (real env wins). */
public final class Config {
    /** Only these AI origins may ever be contacted (local ChatGPT2API gateway or the hosted one). */
    public static final List<String> ALLOWED_UPSTREAMS = List.of(
            "http://localhost:3000", "http://127.0.0.1:3000", "https://chatgptapi.transformsai.org");

    public static final class Limit {
        public final int perMinute, perDay;

        public Limit(int perMinute, int perDay) {
            this.perMinute = perMinute;
            this.perDay = perDay;
        }
    }

    public String apiKey = "";
    public String projectId = "nutrifit-ai-6c41c";
    public int port = 8787;
    public String host = "127.0.0.1";
    public String upstream = ALLOWED_UPSTREAMS.get(0);
    public String model = "auto";
    public String authEmulator = "";
    public Path bucketDir = Path.of("storage");
    /** host:port of the Firestore emulator (development); empty = production Firestore. */
    public String firestoreEmulator = "";
    /** Demo mode: every signed-in account may use AI (sign-in and rate limits still apply). */
    public boolean aiOpenToAll = false;
    /** Call relay (see Turn): Cloudflare Realtime TURN key, or a TURN server's URLs with a REST secret or fixed login. */
    public String cfTurnKeyId = "", cfTurnToken = "";
    public String turnUrls = "", turnSecret = "", turnUsername = "", turnCredential = "";
    public int turnTtlSeconds = 6 * 3600;
    /** Worker threads for all requests, and how many slow AI requests may run at once (the rest wait briefly). */
    public int serverThreads = 2000;
    public int aiMaxConcurrent = 32;
    public long aiQueueWaitMs = 20_000;
    public int maxBodyBytes = 6 * 1024 * 1024;
    public int maxImageBytes = 4 * 1024 * 1024;
    public int upstreamTimeoutMs = 60_000;
    public long retryDelayMs = 1200;
    public final Map<String, Limit> limits = new HashMap<>();

    public Config() {
        limits.put("analyze", new Limit(8, 150));
        limits.put("assistant", new Limit(15, 300));
        limits.put("workout", new Limit(6, 60));
        limits.put("files", new Limit(60, 2000));
        limits.put("turn", new Limit(20, 400));
        limits.put("relay", new Limit(60, 3000));
    }

    public boolean apiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("replace-with");
    }

    public static Config load(Map<String, String> env, Path dotEnv, boolean allowTestUpstream) {
        Map<String, String> e = new HashMap<>(readDotEnv(dotEnv));
        e.putAll(env); // real environment variables take precedence
        Config c = new Config();
        String up = e.getOrDefault("UPSTREAM_BASE_URL", ALLOWED_UPSTREAMS.get(0)).replaceAll("/+$", "");
        if (!ALLOWED_UPSTREAMS.contains(up) && !allowTestUpstream && e.get("ALLOW_TEST_UPSTREAM") == null) {
            throw new IllegalArgumentException("UPSTREAM_BASE_URL " + up + " is not in the allow-list");
        }
        c.upstream = up;
        c.apiKey = e.getOrDefault("NUTRIFIT_LLM_API_KEY", "");
        c.projectId = e.getOrDefault("FIREBASE_PROJECT_ID", c.projectId);
        c.port = Integer.parseInt(e.getOrDefault("PORT", "8787"));
        c.host = e.getOrDefault("HOST", "127.0.0.1");
        c.model = e.getOrDefault("UPSTREAM_MODEL", "auto");
        c.authEmulator = e.getOrDefault("FIREBASE_AUTH_EMULATOR_HOST", "");
        c.bucketDir = Path.of(e.getOrDefault("BUCKET_DIR", "storage")).toAbsolutePath();
        c.firestoreEmulator = e.getOrDefault("FIRESTORE_EMULATOR_HOST", "");
        c.aiOpenToAll = "true".equalsIgnoreCase(e.getOrDefault("AI_OPEN_TO_ALL", "false").trim());
        c.cfTurnKeyId = e.getOrDefault("CF_TURN_KEY_ID", "").trim();
        c.cfTurnToken = e.getOrDefault("CF_TURN_KEY_API_TOKEN", "").trim();
        c.turnUrls = e.getOrDefault("TURN_URLS", "").trim();
        c.turnSecret = e.getOrDefault("TURN_SECRET", "").trim();
        c.turnUsername = e.getOrDefault("TURN_USERNAME", "").trim();
        c.turnCredential = e.getOrDefault("TURN_CREDENTIAL", "").trim();
        c.serverThreads = Math.max(16, Integer.parseInt(e.getOrDefault("SERVER_THREADS", "2000").trim()));
        c.aiMaxConcurrent = Math.max(1, Integer.parseInt(e.getOrDefault("AI_MAX_CONCURRENT", "32").trim()));
        c.upstreamTimeoutMs = Integer.parseInt(e.getOrDefault("UPSTREAM_TIMEOUT_MS", "60000"));
        return c;
    }

    private static final Pattern LINE = Pattern.compile("^\\s*([A-Z0-9_]+)\\s*=\\s*(.*?)\\s*$");

    static Map<String, String> readDotEnv(Path p) {
        Map<String, String> m = new HashMap<>();
        if (p == null || !Files.isRegularFile(p)) return m;
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.trim().startsWith("#")) continue;
                Matcher x = LINE.matcher(line);
                if (x.matches()) m.put(x.group(1), x.group(2));
            }
        } catch (IOException ignored) {
            // unreadable .env behaves like a missing one
        }
        return m;
    }
}
