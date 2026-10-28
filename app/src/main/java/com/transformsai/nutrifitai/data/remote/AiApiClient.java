package com.transformsai.nutrifitai.data.remote;

import android.util.Base64;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.data.Prefs;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Talks ONLY to the NutriFit AI proxy (never directly to the LLM provider). Every request carries the
 * user's Firebase ID token; the provider key lives exclusively on the proxy.
 */
public class AiApiClient {
    public static final long MAX_IMAGE_BYTES = 4L * 1024 * 1024;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    public static final String NOT_ENROLLED = "not_enrolled";
    private final Prefs prefs;
    private final OkHttpClient http;
    /** Told whenever the server says whether AI is enabled for this account (see AiAccess). */
    @Nullable private volatile java.util.function.Consumer<Boolean> accessListener;

    public void setAccessListener(@Nullable java.util.function.Consumer<Boolean> l) {
        accessListener = l;
    }

    public AiApiClient(Prefs prefs) {
        this.prefs = prefs;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    /** Lets a worker cancel the in-flight call. */
    public static final class Handle {
        final AtomicReference<Call> call = new AtomicReference<>();

        public void cancel() {
            Call c = call.get();
            if (c != null) c.cancel();
        }
    }

    public String analyzeImage(File jpeg, @Nullable String note, String locale, @Nullable Handle h) throws AiHttpException {
        try {
            if (jpeg.length() > MAX_IMAGE_BYTES) throw new AiHttpException("too_large", "The photo is too large to analyse.", false);
            byte[] bytes = Files.readAllBytes(jpeg.toPath());
            JsonObject body = new JsonObject();
            body.addProperty("input_type", "image");
            body.addProperty("image_base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
            body.addProperty("mime_type", "image/jpeg");
            if (note != null && !note.isEmpty()) body.addProperty("note", note);
            body.addProperty("locale", locale);
            return post("v1/analyze", body.toString(), h);
        } catch (IOException e) {
            throw new AiHttpException("io", "Could not read the photo.", false);
        }
    }

    public String analyzeText(String text, String locale, @Nullable Handle h) throws AiHttpException {
        JsonObject body = new JsonObject();
        body.addProperty("input_type", "text");
        body.addProperty("text", text);
        body.addProperty("locale", locale);
        return post("v1/analyze", body.toString(), h);
    }

    public String assistant(JsonArray messages, JsonObject context, @Nullable Handle h) throws AiHttpException {
        JsonObject body = new JsonObject();
        body.add("messages", messages);
        body.add("context", context);
        return post("v1/assistant", body.toString(), h);
    }

    /** AI workout plan restricted to the exercises listed in the body (validated by server and app). */
    public String workout(JsonObject body, @Nullable Handle h) throws AiHttpException {
        return post("v1/workout", body.toString(), h);
    }

    /** GET /healthz: true when the proxy is reachable and has its upstream key configured. */
    public boolean health() {
        try {
            Request r = new Request.Builder().url(url("healthz")).get().build();
            try (Response resp = http.newCall(r).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return false;
                JsonObject o = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                return o.has("upstream_configured") && o.get("upstream_configured").getAsBoolean();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * GET /v1/turn: call relay (TURN) servers as JSON {"iceServers":[...]} for the signed-in account, or null when
     * the server is unreachable or has no relay. Blocking (at most a few seconds); call off the main thread.
     */
    @Nullable
    public String turnServers() {
        try {
            Request r = new Request.Builder().url(url("v1/turn")).header("Authorization", "Bearer " + token()).get().build();
            OkHttpClient quick = http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build();
            try (Response resp = quick.newCall(r).execute()) {
                return resp.isSuccessful() && resp.body() != null ? resp.body().string() : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** The signed-in user's Firebase ID token (cached by Firebase), or null when signed out. Blocking. */
    @Nullable
    public String bearerToken() {
        try {
            return token();
        } catch (AiHttpException e) {
            return null;
        }
    }

    public String baseUrl() {
        return prefs.aiBaseUrl();
    }

    private String url(String path) {
        String base = prefs.aiBaseUrl();
        if (!base.endsWith("/")) base += "/";
        return base + path;
    }

    private String token() throws AiHttpException {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) throw new AiHttpException("auth", "Please sign in again.", false);
        try {
            GetTokenResult t = Tasks.await(u.getIdToken(false), 20, TimeUnit.SECONDS);
            if (t.getToken() == null) throw new AiHttpException("auth", "Please sign in again.", false);
            return t.getToken();
        } catch (AiHttpException e) {
            throw e;
        } catch (Exception e) {
            throw new AiHttpException("auth_network", "Could not verify your session. Check your connection.", true);
        }
    }

    private String post(String path, String json, @Nullable Handle h) throws AiHttpException {
        Request req;
        try {
            req = new Request.Builder().url(url(path))
                    .header("Authorization", "Bearer " + token())
                    .post(RequestBody.create(json, JSON)).build();
        } catch (IllegalArgumentException e) {
            throw new AiHttpException("config", "The AI server address is invalid. Check Settings › AI server.", false);
        }
        Call call = http.newCall(req);
        if (h != null) h.call.set(call);
        try (Response resp = call.execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (resp.isSuccessful()) {
                notifyAccess(true);
                return body;
            }
            AiHttpException err = mapError(resp.code(), body, resp.header("Retry-After"));
            if (NOT_ENROLLED.equals(err.code)) notifyAccess(false);
            throw err;
        } catch (AiHttpException e) {
            throw e;
        } catch (InterruptedIOException e) {
            if (call.isCanceled()) throw new AiHttpException("cancelled", "Analysis cancelled.", false);
            throw new AiHttpException("timeout", "The AI service took too long to respond.", true);
        } catch (UnknownHostException | ConnectException e) {
            throw new AiHttpException("offline", "Can't reach the NutriFit AI AI server. Check your connection or server settings.", true);
        } catch (IOException e) {
            if (call.isCanceled()) throw new AiHttpException("cancelled", "Analysis cancelled.", false);
            throw new AiHttpException("network", "Network error while contacting the AI server.", true);
        }
    }

    private void notifyAccess(boolean allowed) {
        java.util.function.Consumer<Boolean> l = accessListener;
        if (l != null) l.accept(allowed);
    }

    static AiHttpException mapError(int code, String body, @Nullable String retryAfter) {
        String serverMsg = null, serverCode = null;
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("error") && o.get("error").isJsonObject()) {
                serverMsg = o.getAsJsonObject("error").get("message").getAsString();
                serverCode = o.getAsJsonObject("error").get("code").getAsString();
            }
        } catch (Exception ignored) {
            // non-JSON error body
        }
        if (code == 403 && "ai_not_enrolled".equals(serverCode)) {
            return new AiHttpException(NOT_ENROLLED, "AI features aren't enabled for your account yet.", false);
        }
        switch (code) {
            case 400: return new AiHttpException("bad_request", serverMsg != null ? serverMsg : "The request was rejected.", false);
            case 401:
            case 403: return new AiHttpException("auth", "Your session expired. Please sign in again.", false);
            case 413: return new AiHttpException("too_large", "The photo or text is too large.", false);
            case 429: return new AiHttpException("rate_limited", "Too many requests. Try again "
                    + (retryAfter != null ? "in " + retryAfter + " s." : "shortly."), true);
            case 502: return new AiHttpException("upstream", serverMsg != null ? serverMsg : "The AI provider returned an invalid response.", true);
            case 503: return new AiHttpException("unavailable", serverMsg != null ? serverMsg : "The AI service is not configured or unavailable.", true);
            case 504: return new AiHttpException("timeout", "The AI provider timed out.", true);
            default: return new AiHttpException("http_" + code, "AI server error (" + code + ").", code >= 500);
        }
    }
}
