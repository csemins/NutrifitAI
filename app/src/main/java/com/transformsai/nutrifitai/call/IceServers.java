package com.transformsai.nutrifitai.call;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.webrtc.PeerConnection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads the server's relay answer ({"iceServers":[{urls,username,credential}]}) into WebRTC ICE servers. */
public final class IceServers {
    private IceServers() { }

    /** One validated server entry. */
    public static final class Entry {
        public final List<String> urls;
        public final String username, credential;

        Entry(List<String> urls, String username, String credential) {
            this.urls = urls;
            this.username = username;
            this.credential = credential;
        }
    }

    /** Validated entries; anything malformed (or a non STUN/TURN URL) is skipped. Never throws. */
    public static List<Entry> entries(@Nullable String json) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        List<Entry> out = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("iceServers")) return out;
            JsonElement list = root.getAsJsonObject().get("iceServers");
            if (!list.isJsonArray()) return out;
            for (JsonElement e : list.getAsJsonArray()) {
                if (!e.isJsonObject() || out.size() >= 8) continue;
                JsonObject o = e.getAsJsonObject();
                List<String> urls = new ArrayList<>();
                JsonElement u = o.get("urls");
                if (u != null && u.isJsonPrimitive()) addUrl(urls, u.getAsString());
                if (u != null && u.isJsonArray()) {
                    JsonArray a = u.getAsJsonArray();
                    for (JsonElement x : a) if (x.isJsonPrimitive()) addUrl(urls, x.getAsString());
                }
                if (urls.isEmpty()) continue;
                out.add(new Entry(urls, string(o, "username"), string(o, "credential")));
            }
        } catch (RuntimeException ignored) {
            // malformed: no relay, calls still try direct connections
        }
        return out;
    }

    /** Login for the server's built-in relay ({"relay":{username,credential}}), or null when not offered. */
    @Nullable
    public static Entry relay(@Nullable String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("relay")) return null;
            JsonElement r = root.getAsJsonObject().get("relay");
            if (!r.isJsonObject()) return null;
            String user = string(r.getAsJsonObject(), "username"), pass = string(r.getAsJsonObject(), "credential");
            if (user.isEmpty() || pass.isEmpty() || user.length() > 100 || pass.length() > 200) return null;
            return new Entry(Collections.emptyList(), user, pass);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static List<PeerConnection.IceServer> parse(@Nullable String json) {
        List<PeerConnection.IceServer> out = new ArrayList<>();
        for (Entry e : entries(json)) {
            out.add(PeerConnection.IceServer.builder(e.urls).setUsername(e.username).setPassword(e.credential)
                    .createIceServer());
        }
        return out;
    }

    private static void addUrl(List<String> urls, String u) {
        if (u.length() <= 200 && (u.startsWith("stun:") || u.startsWith("turn:") || u.startsWith("turns:"))) urls.add(u);
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
