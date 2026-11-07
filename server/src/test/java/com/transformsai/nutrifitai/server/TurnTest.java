package com.transformsai.nutrifitai.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TurnTest {
    @Test
    void nothingConfiguredGivesEmptyList() throws Exception {
        Config cfg = new Config();
        Turn t = new Turn(cfg, "http://unused", () -> 0);
        assertFalse(t.configured());
        assertEquals(0, t.iceServers("u1").getAsJsonArray("iceServers").size());
    }

    @Test
    void restSecretMintsExpiringCredentials() throws Exception {
        Config cfg = new Config();
        cfg.turnUrls = "turn:relay.example.com:3478?transport=udp, turns:relay.example.com:5349?transport=tcp, http://evil";
        cfg.turnSecret = "s3cret";
        cfg.turnTtlSeconds = 600;
        Turn t = new Turn(cfg, "http://unused", () -> 1_000_000L);
        JsonObject s = t.iceServers("alice").getAsJsonArray("iceServers").get(0).getAsJsonObject();
        assertEquals(2, s.getAsJsonArray("urls").size(), "non-TURN URLs are dropped");
        String user = s.get("username").getAsString();
        assertTrue(user.startsWith("1600:"), "expiry = now + ttl");
        assertFalse(user.contains("alice"), "the uid itself is not exposed");
        assertEquals(Turn.hmac("s3cret", user), s.get("credential").getAsString());
    }

    @Test
    void fixedCredentialsArePassedThrough() throws Exception {
        Config cfg = new Config();
        cfg.turnUrls = "turn:relay.example.com:80";
        cfg.turnUsername = "u";
        cfg.turnCredential = "p";
        JsonObject s = new Turn(cfg, "http://unused", () -> 0).iceServers("x").getAsJsonArray("iceServers").get(0).getAsJsonObject();
        assertEquals("u", s.get("username").getAsString());
        assertEquals("p", s.get("credential").getAsString());
    }

    @Test
    void cloudflareKeyIsUsedServerSideAndResultSanitized() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>(), path = new AtomicReference<>();
        HttpServer cf = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cf.createContext("/", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            path.set(ex.getRequestURI().getPath());
            byte[] b = ("{\"iceServers\":[{\"urls\":[\"stun:stun.cloudflare.com:3478\"]},"
                    + "{\"urls\":[\"turn:turn.cloudflare.com:3478?transport=udp\",\"javascript:x\"],\"username\":\"cu\",\"credential\":\"cc\"},"
                    + "{\"urls\":\"ftp://nope\"}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(201, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        cf.start();
        try {
            Config cfg = new Config();
            cfg.cfTurnKeyId = "key123";
            cfg.cfTurnToken = "tok456";
            Turn t = new Turn(cfg, "http://127.0.0.1:" + cf.getAddress().getPort(), () -> 0);
            assertTrue(t.configured());
            JsonArray list = t.iceServers("u").getAsJsonArray("iceServers");
            assertEquals("Bearer tok456", auth.get());
            assertEquals("/v1/turn/keys/key123/credentials/generate-ice-servers", path.get());
            assertEquals(2, list.size(), "entries without valid URLs are dropped");
            JsonObject relay = list.get(1).getAsJsonObject();
            assertEquals(1, relay.getAsJsonArray("urls").size());
            assertEquals("cu", relay.get("username").getAsString());
        } finally {
            cf.stop(0);
        }
    }

    /** A fake Cloudflare that counts calls and can be switched to failing. */
    private static HttpServer fakeCloudflare(java.util.concurrent.atomic.AtomicInteger calls,
                                             java.util.concurrent.atomic.AtomicBoolean failing) throws Exception {
        HttpServer cf = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cf.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        cf.createContext("/", ex -> {
            calls.incrementAndGet();
            byte[] b = ("{\"iceServers\":[{\"urls\":[\"turn:t:3478\"],\"username\":\"u" + calls.get()
                    + "\",\"credential\":\"c\"}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(failing.get() ? 500 : 201, failing.get() ? -1 : b.length);
            if (!failing.get()) {
                try (OutputStream o = ex.getResponseBody()) {
                    o.write(b);
                }
            }
            ex.close();
        });
        cf.start();
        return cf;
    }

    @Test
    void manyParallelCallersShareOneMintedSet() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer cf = fakeCloudflare(calls, new java.util.concurrent.atomic.AtomicBoolean(false));
        try {
            Config cfg = new Config();
            cfg.cfTurnKeyId = "k";
            cfg.cfTurnToken = "t";
            Turn t = new Turn(cfg, "http://127.0.0.1:" + cf.getAddress().getPort(), () -> 1_000L);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(50);
            java.util.List<java.util.concurrent.Future<JsonObject>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 200; i++) {
                String uid = "user" + i;
                results.add(pool.submit(() -> t.iceServers(uid)));
            }
            for (java.util.concurrent.Future<JsonObject> f : results) {
                assertEquals(1, f.get().getAsJsonArray("iceServers").size());
            }
            pool.shutdown();
            assertEquals(1, calls.get(), "200 parallel callers -> one Cloudflare request");
        } finally {
            cf.stop(0);
        }
    }

    @Test
    void refreshesAfterHalfLifeAndSurvivesCloudflareOutage() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean failing = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(0);
        HttpServer cf = fakeCloudflare(calls, failing);
        try {
            Config cfg = new Config();
            cfg.cfTurnKeyId = "k";
            cfg.cfTurnToken = "t";
            cfg.turnTtlSeconds = 3600;
            Turn t = new Turn(cfg, "http://127.0.0.1:" + cf.getAddress().getPort(), now::get);
            String first = t.iceServers("a").getAsJsonArray("iceServers").get(0).getAsJsonObject().get("username").getAsString();
            now.set(31 * 60_000L); // past half-life, Cloudflare down: keep serving the still-valid set
            failing.set(true);
            assertEquals(first, t.iceServers("b").getAsJsonArray("iceServers").get(0).getAsJsonObject().get("username").getAsString());
            int afterFailure = calls.get();
            t.iceServers("c"); // within the back-off: no new attempt
            assertEquals(afterFailure, calls.get());
            now.addAndGet(Turn.RETRY_AFTER_FAILURE_MS + 1); // Cloudflare back: refreshed set
            failing.set(false);
            String second = t.iceServers("d").getAsJsonArray("iceServers").get(0).getAsJsonObject().get("username").getAsString();
            assertNotEquals(first, second);
            now.set(3 * 3600_000L); // everything expired and Cloudflare down: a clear error, not stale credentials
            failing.set(true);
            assertThrows(java.io.IOException.class, () -> t.iceServers("e"));
        } finally {
            cf.stop(0);
        }
    }

    @Test
    void rateLimiterForgetsFinishedDays() {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(0);
        RateLimiter r = new RateLimiter(new Config().limits, now::get);
        for (int i = 0; i < 1000; i++) r.take("u" + i, "turn");
        assertEquals(1000, r.size());
        now.set(86_400_000L + 1);
        r.prune(now.get());
        assertEquals(0, r.size());
    }

    @Test
    void sanitizeAcceptsSingleObject() {
        JsonArray a = Turn.sanitize(JsonParser.parseString("{\"urls\":\"turn:a:1\",\"username\":\"u\",\"credential\":\"c\"}"));
        assertEquals(1, a.size());
        assertEquals(0, Turn.sanitize(null).size());
    }
}
