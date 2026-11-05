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
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/** OpenAI-compatible POST /v1/chat/completions with a bounded timeout and at most one retry. */
public class Upstream {
    public interface Validator {
        JsonObject apply(JsonObject o) throws Contract.ContractError;
    }

    private final Config cfg;
    private final HttpClient http;

    public Upstream(Config cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public String chat(JsonArray messages, int retries) throws ApiException {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.addProperty("stream", false);
        body.add("messages", messages);
        ApiException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.upstream + "/v1/chat/completions"))
                        .timeout(Duration.ofMillis(cfg.upstreamTimeoutMs))
                        .header("content-type", "application/json")
                        .header("authorization", "Bearer " + cfg.apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                int sc = res.statusCode();
                if (sc == 401 || sc == 403) throw new ApiException(503, "upstream_auth", "AI provider rejected the server credentials.");
                if (sc == 429 || sc >= 500) {
                    last = new ApiException(502, "upstream_unavailable", "AI provider unavailable (" + sc + ").");
                    if (attempt < retries) {
                        pause(attempt);
                        continue;
                    }
                    throw last;
                }
                if (sc < 200 || sc >= 300) throw new ApiException(502, "upstream_error", "AI provider error (" + sc + ").");
                return content(res.body());
            } catch (ApiException e) {
                throw e;
            } catch (HttpTimeoutException e) {
                last = new ApiException(504, "upstream_timeout", "AI provider timed out.");
            } catch (IOException e) {
                last = new ApiException(502, "upstream_network", "Could not reach the AI provider.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(503, "interrupted", "Server is shutting down.");
            }
            if (attempt < retries) pause(attempt);
        }
        throw last;
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(cfg.retryDelayMs * (attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String content(String envelope) throws ApiException {
        JsonObject data;
        try {
            data = JsonParser.parseString(envelope).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ApiException(502, "upstream_format", "AI provider returned a non-JSON envelope.");
        }
        try {
            JsonElement msg = data.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content");
            String text;
            if (msg.isJsonArray()) {
                StringBuilder b = new StringBuilder();
                for (JsonElement p : msg.getAsJsonArray()) {
                    if (p.isJsonPrimitive()) b.append(p.getAsString());
                    else if (p.isJsonObject() && p.getAsJsonObject().has("text")) b.append(p.getAsJsonObject().get("text").getAsString());
                }
                text = b.toString();
            } else {
                text = msg.getAsString();
            }
            if (text == null || text.isBlank()) throw new ApiException(502, "upstream_empty", "AI provider returned no content.");
            return text;
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ApiException(502, "upstream_empty", "AI provider returned no content.");
        }
    }

    /** Ask, parse, validate. If the JSON is malformed or invalid, perform ONE text-only repair request. */
    public JsonObject askJson(JsonArray messages, Validator validate) throws ApiException {
        String content = chat(messages, 1);
        try {
            return validate.apply(Contract.extractJson(content));
        } catch (Contract.ContractError e) {
            JsonArray repair = new JsonArray();
            repair.add(msg("system", "You fix JSON. Return ONLY a valid JSON object that satisfies the schema from the previous instructions. "
                    + "Do not add new items or change values except to fix structure."));
            String schema = messages.get(0).getAsJsonObject().get("content").getAsString();
            repair.add(msg("user", "Schema instructions:\n" + schema + "\n\nInvalid output (data):\n"
                    + content.substring(0, Math.min(content.length(), 12000)) + "\n\nValidation error: " + e.code + " " + e.getMessage()));
            String fixed = chat(repair, 0);
            try {
                return validate.apply(Contract.extractJson(fixed));
            } catch (Contract.ContractError e2) {
                throw new ApiException(502, "invalid_ai_output", "The AI returned an invalid result. Please try again.");
            }
        }
    }

    static JsonObject msg(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }
}
