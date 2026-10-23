package com.transformsai.nutrifitai.util;

import android.content.Context;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the bundled photo for a food: its own catalog photo (Wikimedia Commons, credited in
 * assets/food_images/credits.json), else a photo of its food group for database foods. Items logged
 * by text or voice borrow a catalog photo only on a clear name match. A user's own meal photo
 * always wins and is handled by the caller.
 */
public final class FoodImages {
    private static final String DIR = "food_images";
    private static volatile Set<String> files;
    private static volatile Map<String, String> aliases;

    private FoodImages() { }

    /** USDA ids are "usda_{category}_{fdcId}" so the group photo is known without a lookup. */
    @Nullable
    public static String categoryOfId(@Nullable String foodId) {
        if (foodId == null || !foodId.startsWith("usda_")) return null;
        int a = 5, b = foodId.indexOf('_', a);
        return b > a ? foodId.substring(a, b) : null;
    }

    /** @return an android_asset URI or null when no suitable image exists. */
    @Nullable
    public static String uri(Context c, @Nullable String foodId, @Nullable String category, @Nullable String name) {
        Set<String> f = files(c);
        if (foodId != null && f.contains(foodId + ".webp")) return asset(foodId);
        if (name != null) {
            String matched = matchName(c, name);
            if (matched != null && f.contains(matched + ".webp")) return asset(matched);
        }
        String cat = category != null ? category : categoryOfId(foodId);
        if (cat != null && f.contains("category_" + cat + ".webp")) return asset("category_" + cat);
        return null;
    }

    private static String asset(String base) {
        return "file:///android_asset/" + DIR + "/" + base + ".webp";
    }

    private static Set<String> files(Context c) {
        Set<String> s = files;
        if (s == null) {
            try {
                String[] list = c.getAssets().list(DIR);
                s = new HashSet<>(list == null ? Arrays.asList() : Arrays.asList(list));
            } catch (Exception e) {
                s = new HashSet<>();
            }
            files = s;
        }
        return s;
    }

    /** Longest catalog alias (>= 4 letters) contained in the item name, e.g. "Chicken biryani" -> biryani. */
    @Nullable
    static String matchName(Context c, String name) {
        Map<String, String> m = aliases(c);
        String n = " " + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z\\u0980-\\u09ff ]", " ") + " ";
        String best = null;
        int bestLen = 0;
        for (Map.Entry<String, String> e : m.entrySet()) {
            String a = e.getKey();
            if (a.length() > bestLen && n.contains(" " + a + " ")) {
                best = e.getValue();
                bestLen = a.length();
            }
        }
        return best;
    }

    private static Map<String, String> aliases(Context c) {
        Map<String, String> m = aliases;
        if (m != null) return m;
        m = new HashMap<>();
        try (InputStream in = c.getAssets().open("foods_catalog.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("foods")) {
                JsonObject o = e.getAsJsonObject();
                String id = o.get("id").getAsString();
                String all = o.get("name").getAsString() + "|" + (o.has("aliases") && !o.get("aliases").isJsonNull() ? o.get("aliases").getAsString() : "");
                for (String a : all.toLowerCase(Locale.ROOT).split("[|,()]")) {
                    String t = a.trim();
                    if (t.length() >= 4 && !m.containsKey(t)) m.put(t, id);
                }
            }
        } catch (Exception ignored) {
            // no name matching without the catalog
        }
        aliases = m;
        return m;
    }
}
