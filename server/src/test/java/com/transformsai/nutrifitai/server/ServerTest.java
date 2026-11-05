package com.transformsai.nutrifitai.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end tests against a real HttpServer with a mock OpenAI-compatible upstream. */
class ServerTest {
    static final String ALICE = "aliceUid123", BOB = "bobUid456", CAROL = "carolUid789";
    static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1, 2, 3, 4};

    @TempDir
    Path tmp;
    HttpServer upstream, server;
    final Deque<Object[]> replies = new ArrayDeque<>(); // {status, content}
    final AtomicInteger upstreamCalls = new AtomicInteger();
    final HttpClient http = HttpClient.newHttpClient();
    String base;

    @BeforeEach
    void setUp() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", ex -> {
            upstreamCalls.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            Object[] r = replies.isEmpty() ? new Object[]{500, ""} : replies.poll();
            JsonObject env = new JsonObject();
            JsonArray choices = new JsonArray();
            JsonObject c = new JsonObject();
            JsonObject m = new JsonObject();
            m.addProperty("role", "assistant");
            m.addProperty("content", (String) r[1]);
            c.add("message", m);
            choices.add(c);
            env.add("choices", choices);
            byte[] data = env.toString().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders((Integer) r[0], data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        });
        upstream.start();
        Config cfg = new Config();
        cfg.apiKey = "test-key";
        cfg.port = 0;
        cfg.upstream = "http://127.0.0.1:" + upstream.getAddress().getPort();
        cfg.bucketDir = tmp.resolve("storage");
        cfg.retryDelayMs = 10;
        cfg.limits.put("assistant", new Config.Limit(2, 100));
        server = Main.start(cfg, token -> {
            if (token.equals("tok-alice")) return new Identity(ALICE, "alice@test.dev", true, "Alice");
            if (token.equals("tok-bob")) return new Identity(BOB, "Bob@Test.dev", true, "Bob");
            if (token.equals("tok-carol")) return new Identity(CAROL, "carol@test.dev", true, "Carol");
            throw new TokenVerifier.AuthError("bad");
        }, (uid, token) -> !CAROL.equals(uid)); // AI enabled in Firestore for everyone but carol
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        upstream.stop(0);
    }

    HttpResponse<String> call(String method, String path, String token, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String token, String json) throws Exception {
        return call("POST", path, token, json.getBytes(StandardCharsets.UTF_8));
    }

    static String code(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsString();
    }

    static final String GOOD = "{\"schema_version\":\"1.0\",\"status\":\"success\",\"is_food\":true,\"items\":[{\"name\":\"Rice\",\"confidence\":0.9,"
            + "\"portion\":{\"quantity\":1,\"unit\":\"plate\",\"estimated_weight_g\":200},\"nutrition_for_portion\":{\"calories_kcal\":260,"
            + "\"protein_g\":5,\"carbohydrates_g\":56,\"fat_g\":1,\"fibre_g\":null}}],\"totals\":{\"calories_kcal\":99999}}";

    @Test
    void healthIsPublicAndReportsJava() throws Exception {
        HttpResponse<String> r = call("GET", "/healthz", null, null);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"server\":\"java\""));
        assertTrue(r.body().contains("\"upstream_configured\":true"));
    }

    @Test
    void rejectsMissingAndBadTokens() throws Exception {
        assertEquals(401, post("/v1/analyze", null, "{}").statusCode());
        assertEquals(401, post("/v1/analyze", "forged", "{}").statusCode());
        assertEquals(404, call("GET", "/v1/analyze", "tok-alice", null).statusCode());
        assertEquals(0, upstreamCalls.get());
    }

    @Test
    void analyzeRecomputesTotals() throws Exception {
        replies.add(new Object[]{200, "```json\n" + GOOD + "\n```"});
        HttpResponse<String> r = post("/v1/analyze", "tok-alice", "{\"input_type\":\"text\",\"text\":\"one plate of rice\"}");
        assertEquals(200, r.statusCode(), r.body());
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        assertEquals(260.0, o.getAsJsonObject("totals").get("calories_kcal").getAsDouble());
        assertEquals("success", o.get("status").getAsString());
    }

    @Test
    void malformedOutputIsRepairedOnceThenFails() throws Exception {
        replies.add(new Object[]{200, "not json at all"});
        replies.add(new Object[]{200, GOOD});
        assertEquals(200, post("/v1/analyze", "tok-alice", "{\"input_type\":\"text\",\"text\":\"rice\"}").statusCode());
        assertEquals(2, upstreamCalls.get());

        replies.add(new Object[]{200, "still bad"});
        replies.add(new Object[]{200, "{\"status\":\"weird\"}"});
        HttpResponse<String> r = post("/v1/analyze", "tok-alice", "{\"input_type\":\"text\",\"text\":\"rice\"}");
        assertEquals(502, r.statusCode());
        assertEquals("invalid_ai_output", code(r));
    }

    @Test
    void upstreamErrorsMapToActionableCodes() throws Exception {
        replies.add(new Object[]{401, ""});
        HttpResponse<String> r = post("/v1/analyze", "tok-alice", "{\"input_type\":\"text\",\"text\":\"rice\"}");
        assertEquals(503, r.statusCode());
        assertEquals("upstream_auth", code(r));
        replies.add(new Object[]{503, ""});
        replies.add(new Object[]{503, ""});
        r = post("/v1/analyze", "tok-alice", "{\"input_type\":\"text\",\"text\":\"rice\"}");
        assertEquals(502, r.statusCode());
        assertEquals("upstream_unavailable", code(r));
    }

    @Test
    void validatesRequests() throws Exception {
        assertEquals(400, post("/v1/analyze", "tok-alice", "{\"input_type\":\"video\"}").statusCode());
        assertEquals(400, post("/v1/analyze", "tok-alice", "{\"input_type\":\"image\",\"mime_type\":\"image/gif\",\"image_base64\":\"AAAA\"}").statusCode());
        assertEquals(400, post("/v1/analyze", "tok-alice", "not json").statusCode());
        assertEquals(400, post("/v1/assistant", "tok-alice", "{\"messages\":[{\"role\":\"assistant\",\"content\":\"hi\"}]}").statusCode());
        assertEquals(0, upstreamCalls.get());
    }

    @Test
    void rateLimitsPerUser() throws Exception {
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        for (int i = 0; i < 2; i++) {
            replies.add(new Object[]{200, "{\"reply\":\"Hello\",\"suggestions\":[]}"});
            assertEquals(200, post("/v1/assistant", "tok-alice", body).statusCode());
        }
        HttpResponse<String> r = post("/v1/assistant", "tok-alice", body);
        assertEquals(429, r.statusCode());
        assertTrue(r.headers().firstValue("retry-after").isPresent());
        replies.add(new Object[]{200, "{\"reply\":\"Hello\",\"suggestions\":[]}"});
        assertEquals(200, post("/v1/assistant", "tok-bob", body).statusCode());
    }

    @Test
    void workoutUsesOnlyAllowedExercises() throws Exception {
        replies.add(new Object[]{200, "{\"title\":\"Quick burn\",\"summary\":\"Short session\",\"steps\":["
                + "{\"exercise_id\":\"squat\",\"reps\":12,\"rest_seconds\":20,\"cue\":\"Chest up\"},"
                + "{\"exercise_id\":\"made_up\",\"reps\":10},"
                + "{\"exercise_id\":\"plank\",\"seconds\":30},"
                + "{\"exercise_id\":\"pushup\",\"reps\":500}]}"});
        String body = "{\"level\":\"beginner\",\"minutes\":10,\"exercises\":["
                + "{\"id\":\"squat\",\"name\":\"Squat\",\"kind\":\"reps\"},{\"id\":\"plank\",\"name\":\"Plank\",\"kind\":\"hold\"},"
                + "{\"id\":\"pushup\",\"name\":\"Push-up\",\"kind\":\"reps\"}]}";
        HttpResponse<String> r = post("/v1/workout", "tok-alice", body);
        assertEquals(200, r.statusCode(), r.body());
        JsonArray steps = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("steps");
        assertEquals(2, steps.size()); // unknown id and out-of-range reps dropped
        assertEquals(30, steps.get(1).getAsJsonObject().get("seconds").getAsInt());
    }

    @Test
    void filesAreOwnerOnly() throws Exception {
        String p = "/v1/files/users/" + ALICE + "/meals/m1.jpg";
        assertEquals(200, call("PUT", p, "tok-alice", JPEG).statusCode());
        HttpResponse<String> got = call("GET", p, "tok-alice", null);
        assertEquals(200, got.statusCode());
        assertEquals(403, call("GET", p, "tok-bob", null).statusCode());
        assertEquals(403, call("PUT", p, "tok-bob", JPEG).statusCode());
        assertEquals(401, call("GET", p, null, null).statusCode());
        assertEquals(415, call("PUT", "/v1/files/users/" + ALICE + "/avatar.jpg", "tok-alice", "hello".getBytes()).statusCode());
        assertEquals(400, call("GET", "/v1/files/users/" + ALICE + "/..%2F..%2Fsecret.jpg", "tok-alice", null).statusCode());
        assertEquals(413, call("PUT", "/v1/files/users/" + ALICE + "/avatar.jpg", "tok-alice", new byte[Bucket.MAX_FILE_BYTES + 10]).statusCode());
        assertEquals(200, call("DELETE", p, "tok-alice", null).statusCode());
        assertEquals(404, call("GET", p, "tok-alice", null).statusCode());
    }

    @Test
    void accountWipeRemovesOnlyOwnFolder() throws Exception {
        assertEquals(200, call("PUT", "/v1/files/users/" + ALICE + "/avatar.jpg", "tok-alice", JPEG).statusCode());
        assertEquals(200, call("PUT", "/v1/files/users/" + BOB + "/avatar.jpg", "tok-bob", JPEG).statusCode());
        assertEquals(403, call("DELETE", "/v1/files/users/" + BOB + "/", "tok-alice", null).statusCode());
        assertEquals(200, call("DELETE", "/v1/files/users/" + ALICE + "/", "tok-alice", null).statusCode());
        assertFalse(Files.exists(tmp.resolve("storage/users/" + ALICE)));
        assertTrue(Files.exists(tmp.resolve("storage/users/" + BOB + "/avatar.jpg")));
    }

    @Test
    void aiIsOffUntilEnabledInFirebase() throws Exception {
        String chat = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"context\":{}}";
        HttpResponse<String> r = post("/v1/assistant", "tok-carol", chat);
        assertEquals(403, r.statusCode());
        assertEquals("ai_not_enrolled", code(r));
        assertEquals(403, post("/v1/analyze", "tok-carol", "{}").statusCode());
        // media uploads are not AI calls and stay available
        assertEquals(200, call("PUT", "/v1/files/users/" + CAROL + "/avatar.jpg", "tok-carol", JPEG).statusCode());
    }

    @Test
    void slowAiCannotStarveCallRelayAndExcessAiGetsBusy() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        slow.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            started.incrementAndGet();
            try {
                release.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        slow.start();
        Config cfg = new Config();
        cfg.apiKey = "test-key";
        cfg.port = 0;
        cfg.upstream = "http://127.0.0.1:" + slow.getAddress().getPort();
        cfg.bucketDir = tmp.resolve("storage2");
        cfg.retryDelayMs = 10;
        cfg.aiMaxConcurrent = 2;
        cfg.aiQueueWaitMs = 300;
        cfg.serverThreads = 16;
        HttpServer s2 = Main.start(cfg, token -> new Identity(token, token + "@test.dev", true, token), (uid, token) -> true);
        String b2 = "http://127.0.0.1:" + s2.getAddress().getPort();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            String body = "{\"input_type\":\"text\",\"text\":\"rice\"}";
            for (String who : new String[]{"u1", "u2"}) {
                pool.submit(() -> http.send(HttpRequest.newBuilder(URI.create(b2 + "/v1/analyze"))
                        .header("Authorization", "Bearer " + who)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()));
            }
            long until = System.currentTimeMillis() + 5000;
            while (started.get() < 2 && System.currentTimeMillis() < until) Thread.onSpinWait();
            assertEquals(2, started.get(), "two AI calls are in flight");
            HttpResponse<String> third = http.send(HttpRequest.newBuilder(URI.create(b2 + "/v1/analyze"))
                    .header("Authorization", "Bearer u3").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, third.statusCode());
            assertEquals("busy", code(third));
            long t0 = System.currentTimeMillis();
            HttpResponse<String> relay = http.send(HttpRequest.newBuilder(URI.create(b2 + "/v1/turn"))
                    .header("Authorization", "Bearer u4").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, relay.statusCode(), relay.body());
            assertTrue(System.currentTimeMillis() - t0 < 1000, "relay credentials stay fast while AI is saturated");
            assertTrue(relay.body().contains("iceServers"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            s2.stop(0);
            slow.stop(0);
        }
    }

    @Test
    void relayNeedsSignInAndGet() throws Exception {
        assertEquals(401, call("GET", "/v1/turn", null, null).statusCode());
        assertEquals(404, post("/v1/turn", "tok-alice", "{}").statusCode());
        HttpResponse<String> r = call("GET", "/v1/turn", "tok-carol", null); // relay is not gated on AI access
        assertEquals(200, r.statusCode());
    }
}
