package com.transformsai.nutrifitai.call;

import androidx.annotation.Nullable;

import com.google.gson.JsonParser;
import com.transformsai.nutrifitai.App;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Calls through our own server, without open ports or third-party relays. WebRTC is given a TURN server at
 * 127.0.0.1:{port} (TCP); every connection it makes there is carried over HTTPS to the server's built-in relay
 * (server: TurnRelay, routes /v1/relay/*), which reaches the phone through the same tunnel as the rest of the app.
 * Uploads and the streamed download both carry byte offsets, so a dropped request is retried without losing or
 * repeating data, and a brief network change does not break the call.
 */
public final class CallRelayBridge {
    private static final MediaType BYTES = MediaType.get("application/octet-stream");
    private static final long GIVE_UP_MS = 25_000;
    @Nullable private static CallRelayBridge instance;

    private final ServerSocket server;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private CallRelayBridge() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(this::acceptLoop, "relay-accept");
        t.setDaemon(true);
        t.start();
    }

    /** Starts the bridge once per process; returns its local port. */
    public static synchronized int start() throws IOException {
        if (instance == null || instance.server.isClosed()) instance = new CallRelayBridge();
        return instance.server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                new Tunnel(s).start();
            } catch (IOException e) {
                if (server.isClosed()) return;
            }
        }
    }

    private static String base() {
        String b = App.graph().ai.baseUrl();
        return b.endsWith("/") ? b : b + "/";
    }

    private static String token() throws IOException {
        String t = App.graph().ai.bearerToken();
        if (t == null) throw new IOException("signed out");
        return t;
    }

    /** One WebRTC TCP connection carried over HTTPS. */
    private final class Tunnel {
        private final Socket socket;
        private String id;
        private volatile long downOff;
        private long upOff;
        private volatile boolean closed;

        Tunnel(Socket socket) {
            this.socket = socket;
        }

        void start() {
            Thread t = new Thread(() -> {
                try {
                    id = open();
                } catch (IOException e) {
                    close();
                    return;
                }
                Thread down = new Thread(this::downLoop, "relay-down");
                down.setDaemon(true);
                down.start();
                upLoop();
            }, "relay-up");
            t.setDaemon(true);
            t.start();
        }

        private String open() throws IOException {
            IOException last = null;
            for (int i = 0; i < 3; i++) {
                Request r = new Request.Builder().url(base() + "v1/relay/open").header("Authorization", "Bearer " + token())
                        .post(RequestBody.create(new byte[0], BYTES)).build();
                try (Response resp = http.newCall(r).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        return JsonParser.parseString(resp.body().string()).getAsJsonObject().get("id").getAsString();
                    }
                    last = new IOException("relay open " + resp.code());
                } catch (IOException | RuntimeException e) {
                    last = e instanceof IOException ? (IOException) e : new IOException(e);
                }
                pause(400L * (i + 1));
            }
            throw last;
        }

        /** Phone -> server: whatever WebRTC wrote since the last upload, sent in order with its stream offset. */
        private void upLoop() {
            byte[] buf = new byte[256 * 1024];
            try {
                InputStream in = socket.getInputStream();
                while (!closed) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    while (n < buf.length && in.available() > 0) {
                        int more = in.read(buf, n, Math.min(in.available(), buf.length - n));
                        if (more <= 0) break;
                        n += more;
                    }
                    if (!upload(Arrays.copyOf(buf, n))) break;
                }
            } catch (IOException ignored) {
                // socket closed by WebRTC
            }
            close();
        }

        private boolean upload(byte[] data) {
            long deadline = System.currentTimeMillis() + GIVE_UP_MS;
            long backoff = 150;
            while (!closed && System.currentTimeMillis() < deadline) {
                try {
                    Request r = new Request.Builder()
                            .url(base() + "v1/relay/" + id + "/up?off=" + upOff + "&ack=" + downOff)
                            .header("Authorization", "Bearer " + token())
                            .post(RequestBody.create(data, BYTES)).build();
                    try (Response resp = http.newCall(r).execute()) {
                        if (resp.isSuccessful()) {
                            upOff += data.length;
                            return true;
                        }
                        if (resp.code() == 404 || resp.code() == 401 || resp.code() == 409) return false;
                    }
                } catch (IOException ignored) {
                    // network blip: retry the same bytes at the same offset
                }
                pause(backoff);
                backoff = Math.min(backoff * 2, 2000);
            }
            return false;
        }

        /** Server -> phone: a streamed response of base64 lines, resumed from the last byte received. */
        private void downLoop() {
            long failingSince = 0;
            try {
                OutputStream out = socket.getOutputStream();
                while (!closed) {
                    Request r = new Request.Builder().url(base() + "v1/relay/" + id + "/down?off=" + downOff)
                            .header("Authorization", "Bearer " + token()).get().build();
                    try (Response resp = http.newCall(r).execute()) {
                        if (resp.code() == 404 || resp.code() == 401) break;
                        if (!resp.isSuccessful() || resp.body() == null) throw new IOException("down " + resp.code());
                        failingSince = 0;
                        BufferedReader lines = new BufferedReader(new InputStreamReader(resp.body().byteStream(), StandardCharsets.US_ASCII));
                        String line;
                        while (!closed && (line = lines.readLine()) != null) {
                            if (line.startsWith("d:")) {
                                byte[] b = android.util.Base64.decode(line.substring(2), android.util.Base64.NO_WRAP);
                                out.write(b);
                                out.flush();
                                downOff += b.length;
                            } else if (line.equals("x")) {
                                closed = true;
                            }
                        }
                    } catch (IOException e) {
                        if (failingSince == 0) failingSince = System.currentTimeMillis();
                        if (System.currentTimeMillis() - failingSince > GIVE_UP_MS) break;
                        pause(300);
                    }
                }
            } catch (IOException ignored) {
                // socket closed
            }
            close();
        }

        private void close() {
            if (closed && socket.isClosed()) return;
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed
            }
            String sid = id;
            if (sid == null) return;
            new Thread(() -> {
                try {
                    Request r = new Request.Builder().url(base() + "v1/relay/" + sid + "/close")
                            .header("Authorization", "Bearer " + token()).post(RequestBody.create(new byte[0], BYTES)).build();
                    http.newCall(r).execute().close();
                } catch (IOException ignored) {
                    // the server drops idle relay connections on its own
                }
            }, "relay-close").start();
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
