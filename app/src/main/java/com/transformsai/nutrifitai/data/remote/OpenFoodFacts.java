package com.transformsai.nutrifitai.data.remote;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Packaged-food lookup by barcode in Open Food Facts (open database, ODbL, no key). Only the product
 * name, brand, serving and per-100 g nutrients are requested. Values are validated: missing nutrients
 * stay unknown (null), implausible ones reject the product rather than being saved.
 */
public final class OpenFoodFacts {
    private static final String BASE = "https://world.openfoodfacts.org/api/v2/product/";
    private static final String FIELDS = "product_name,product_name_en,brands,serving_size,serving_quantity,nutriments";
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build();

    /** Digits only, 8-14 long (EAN-8, UPC-A, EAN-13, GTIN-14). */
    public static boolean validCode(@Nullable String code) {
        return code != null && code.matches("\\d{8,14}");
    }

    /** @return the product as a food (id not set), or null when the product is unknown. Blocking. */
    @Nullable
    public FoodEntity lookup(String code) throws IOException {
        if (!validCode(code)) return null;
        Request req = new Request.Builder().url(BASE + code + ".json?fields=" + FIELDS)
                .header("User-Agent", "NutriFitAI/3.0 (Android app)").build();
        try (Response r = http.newCall(req).execute()) {
            if (r.code() == 404) return null;
            if (!r.isSuccessful() || r.body() == null) throw new IOException("Lookup failed (" + r.code() + ")");
            String body = r.body().string();
            if (body.length() > 400_000) throw new IOException("Unexpected response");
            return parse(code, body);
        }
    }

    /** Parses and validates an Open Food Facts v2 product response. Package-private for tests. */
    @Nullable
    public static FoodEntity parse(String code, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("product") || !root.get("product").isJsonObject()) return null;
        JsonObject p = root.getAsJsonObject("product");
        String name = str(p, "product_name");
        if (name == null || name.isEmpty()) name = str(p, "product_name_en");
        if (name == null || name.isEmpty()) return null;
        JsonObject n = p.has("nutriments") && p.get("nutriments").isJsonObject() ? p.getAsJsonObject("nutriments") : new JsonObject();
        Double kcal = num(n, "energy-kcal_100g");
        if (kcal == null) {
            Double kj = num(n, "energy-kj_100g");
            if (kj == null) kj = num(n, "energy_100g");
            if (kj != null) kcal = kj / 4.184;
        }
        Double protein = num(n, "proteins_100g"), carbs = num(n, "carbohydrates_100g"), fat = num(n, "fat_100g");
        if (kcal == null || protein == null || carbs == null || fat == null) return null; // not enough to log
        if (kcal > 900 || protein > 100 || carbs > 100 || fat > 100 || protein + carbs + fat > 105) return null;
        FoodEntity f = new FoodEntity();
        String brand = str(p, "brands");
        if (brand != null && brand.contains(",")) brand = brand.substring(0, brand.indexOf(',')).trim();
        f.name = clip(brand == null || brand.isEmpty() || name.toLowerCase(Locale.ROOT).contains(brand.toLowerCase(Locale.ROOT))
                ? name : name + " (" + brand + ")", 80);
        f.kcal100 = round1(kcal);
        f.protein100 = round1(protein);
        f.carbs100 = round1(carbs);
        f.fat100 = round1(fat);
        f.fibre100 = num(n, "fiber_100g");
        f.sugar100 = num(n, "sugars_100g");
        Double sodium = num(n, "sodium_100g");
        f.sodiumMg100 = sodium == null ? null : round1(sodium * 1000);
        Double sq = num(p, "serving_quantity");
        String ss = str(p, "serving_size");
        if (sq != null && sq > 0 && sq <= 2000) {
            f.servingGrams = sq;
            f.servingLabel = ss == null || ss.isEmpty() ? "serving" : clip(ss, 30);
        } else {
            f.servingGrams = 100;
            f.servingLabel = "100 g";
        }
        f.provenance = FoodEntity.PROV_SOURCED;
        f.sourceRef = "Open Food Facts " + code;
        f.category = "packaged";
        f.emoji = "🛒";
        return f;
    }

    @Nullable
    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString().replace('\n', ' ').trim() : null;
    }

    @Nullable
    private static Double num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) return null;
        try {
            double v = e.getAsDouble();
            return Double.isFinite(v) && v >= 0 ? v : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
