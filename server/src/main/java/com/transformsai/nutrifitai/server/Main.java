package com.transformsai.nutrifitai.server;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Entry point: java -jar nutrifitai-server.jar (reads ./.env, real environment variables win). */
public final class Main {
    private Main() { }

    public static HttpServer start(Config cfg, Api.Verifier verifier) throws Exception {
        return start(cfg, verifier, cfg.aiOpenToAll ? (uid, token) -> true : new FirestoreAccess(cfg, System::currentTimeMillis));
    }

    static HttpServer start(Config cfg, Api.Verifier verifier, Api.AccessCheck access) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(cfg.host, cfg.port), 512);
        server.createContext("/", new Api(cfg, verifier, new Upstream(cfg), System::currentTimeMillis, access));
        // Threads grow with demand (streamed call-relay downloads hold one each) and shrink when idle.
        ExecutorService pool = new java.util.concurrent.ThreadPoolExecutor(Math.min(32, cfg.serverThreads), cfg.serverThreads, 60,
                java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>());
        server.setExecutor(pool);
        server.start();
        return server;
    }

    public static void main(String[] args) throws Exception {
        Config cfg = Config.load(System.getenv(), Path.of(".env"), false);
        boolean emulator = !cfg.authEmulator.isEmpty();
        TokenVerifier tv = TokenVerifier.google(cfg.projectId, emulator);
        HttpServer server = start(cfg, token -> tv.identify(token, System.currentTimeMillis()));
        System.out.println("NutriFit AI server (Java) listening on http://" + cfg.host + ":" + server.getAddress().getPort()
                + " (upstream " + cfg.upstream + ", key " + (cfg.apiKeyConfigured() ? "configured" : "MISSING")
                + ", bucket " + cfg.bucketDir + (emulator ? ", accepting Auth-emulator tokens" : "")
                + (cfg.aiOpenToAll ? ", AI open to every signed-in account (demo)" : ", AI access from Firestore aiAccess/{uid}"
                + (cfg.firestoreEmulator.isEmpty() ? "" : " (emulator)")) + ")");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1)));
    }
}
