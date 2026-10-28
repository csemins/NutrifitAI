package com.transformsai.nutrifitai.data.repo;

import android.util.Log;

import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.data.db.entity.FavoriteEntity;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.ServingEntity;
import com.transformsai.nutrifitai.data.db.model.FrequentFood;
import com.transformsai.nutrifitai.sync.Collections;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Ids;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Starter catalog (bundled asset) + personal foods + favorites. Search is fully offline. */
public class FoodRepository {
    private final AppGraph g;

    public FoodRepository(AppGraph g) {
        this.g = g;
    }

    /** Loads assets/foods_catalog.json into Room once per catalog version. */
    public void seedCatalogIfNeeded() {
        try (InputStream in = g.context.getAssets().open("foods_catalog.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            int version = root.get("catalog_version").getAsInt();
            if (version <= g.prefs.catalogVersion() && g.db.foods().catalogCount() > 0) return;
            List<FoodEntity> foods = new ArrayList<>();
            List<ServingEntity> servings = new ArrayList<>();
            JsonArray arr = root.getAsJsonArray("foods");
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                FoodEntity f = new FoodEntity();
                f.id = o.get("id").getAsString();
                f.ownerUid = null;
                f.name = o.get("name").getAsString();
                f.nameBn = str(o, "name_bn");
                f.aliases = (f.name + "|" + str(o, "aliases")).toLowerCase(Locale.ROOT);
                f.category = str(o, "category");
                f.emoji = str(o, "emoji");
                f.bangladeshi = o.get("bangladeshi").getAsBoolean();
                f.kcal100 = o.get("kcal").getAsDouble();
                f.protein100 = o.get("protein").getAsDouble();
                f.carbs100 = o.get("carbs").getAsDouble();
                f.fat100 = o.get("fat").getAsDouble();
                f.fibre100 = dbl(o, "fibre");
                f.sugar100 = dbl(o, "sugar");
                f.sodiumMg100 = dbl(o, "sodium_mg");
                f.microsJson = o.has("micros") && o.get("micros").isJsonObject() ? o.get("micros").toString() : null;
                f.provenance = FoodEntity.PROV_CURATED;
                f.sourceRef = str(o, "source");
                f.servingLabel = str(o, "serving_label");
                f.servingGrams = o.get("serving_g").getAsDouble();
                f.updatedAt = 0;
                foods.add(f);
                for (JsonElement s : o.getAsJsonArray("servings")) {
                    ServingEntity se = new ServingEntity();
                    se.foodId = f.id;
                    se.label = s.getAsJsonObject().get("label").getAsString();
                    se.grams = s.getAsJsonObject().get("g").getAsDouble();
                    se.id = f.id + ":" + se.label;
                    servings.add(se);
                }
            }
            g.db.foods().replaceCatalog(foods, servings);
            g.prefs.setCatalogVersion(version);
        } catch (Exception e) {
            Log.e("NutriFit AI", "Catalog seed failed", e);
        }
    }

    public static final int USDA_VERSION = 1;
    private static final String[][] CATEGORY_EMOJI = {{"grains", "🍞"}, {"protein", "🍗"}, {"vegetables", "🥦"}, {"fruits", "🍎"},
            {"dairy", "🥛"}, {"drinks", "🥤"}, {"sweets", "🍰"}, {"snacks", "🥜"}, {"lentils", "🫘"}, {"fats", "🫒"},
            {"meals", "🍱"}, {"other", "🧂"}};

    /**
     * Loads the USDA FoodData Central SR Legacy subset (assets/usda_sr_legacy.json.gz, ~7,300 foods,
     * public domain) once. Rows are labelled "sourced" with their FDC id so they are never confused
     * with curated or user foods.
     */
    public void seedUsdaIfNeeded() {
        if (g.prefs.usdaVersion() >= USDA_VERSION && g.db.foods().countByProvenance(FoodEntity.PROV_SOURCED) > 0) return;
        try (InputStream in = com.transformsai.nutrifitai.util.AssetFiles.open(g.context, "usda_sr_legacy.json.gz")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            List<FoodEntity> foods = new ArrayList<>();
            List<ServingEntity> servings = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("foods")) {
                JsonArray a = e.getAsJsonArray();
                FoodEntity f = new FoodEntity();
                String cat = a.get(2).getAsString();
                f.id = "usda_" + cat + "_" + a.get(0).getAsLong();
                f.name = a.get(1).getAsString();
                f.aliases = f.name.toLowerCase(Locale.ROOT);
                f.category = cat;
                f.emoji = emojiFor(cat);
                f.kcal100 = a.get(3).getAsDouble();
                f.protein100 = a.get(4).getAsDouble();
                f.carbs100 = a.get(5).getAsDouble();
                f.fat100 = a.get(6).getAsDouble();
                f.fibre100 = a.get(7).isJsonNull() ? null : a.get(7).getAsDouble();
                f.sugar100 = a.get(8).isJsonNull() ? null : a.get(8).getAsDouble();
                f.sodiumMg100 = a.get(9).isJsonNull() ? null : a.get(9).getAsDouble();
                JsonObject micros = a.get(10).getAsJsonObject();
                f.microsJson = micros.size() == 0 ? null : micros.toString();
                f.provenance = FoodEntity.PROV_SOURCED;
                f.sourceRef = "USDA FoodData Central SR Legacy · FDC ID " + a.get(0).getAsLong() + " (public domain)";
                JsonArray portions = a.get(11).getAsJsonArray();
                f.servingLabel = portions.size() > 0 ? portions.get(0).getAsJsonArray().get(0).getAsString() : "100 g";
                f.servingGrams = portions.size() > 0 ? portions.get(0).getAsJsonArray().get(1).getAsDouble() : 100;
                for (JsonElement pe : portions) {
                    JsonArray p = pe.getAsJsonArray();
                    ServingEntity se = new ServingEntity();
                    se.foodId = f.id;
                    se.label = p.get(0).getAsString();
                    se.grams = p.get(1).getAsDouble();
                    se.id = f.id + ":" + se.label;
                    servings.add(se);
                }
                foods.add(f);
            }
            for (int i = 0; i < foods.size(); i += 500) {
                List<FoodEntity> part = foods.subList(i, Math.min(foods.size(), i + 500));
                List<ServingEntity> sp = new ArrayList<>();
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (FoodEntity f : part) ids.add(f.id);
                for (ServingEntity s : servings) if (ids.contains(s.foodId)) sp.add(s);
                g.db.foods().replaceCatalog(new ArrayList<>(part), sp);
            }
            g.prefs.setUsdaVersion(USDA_VERSION);
        } catch (Exception e) {
            Log.e("NutriFit AI", "USDA seed failed", e);
        }
    }

    static String emojiFor(String cat) {
        for (String[] p : CATEGORY_EMOJI) if (p[0].equals(cat)) return p[1];
        return "🍽";
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static Double dbl(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : null;
    }

    public static final class SearchQuery {
        public String text = "";
        public String filter = "all"; // all | favorites | recent | bd | custom | <category>
    }

    public void search(SearchQuery q, Result.Callback<List<FoodEntity>> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            String text = q.text == null ? "" : q.text.trim().toLowerCase(Locale.ROOT);
            List<FoodEntity> out;
            switch (q.filter) {
                case "favorites": out = filterText(g.db.foods().favorites(uid), text); break;
                case "recent": out = filterText(g.db.foods().recent(uid, 30), text); break;
                case "bd": out = g.db.foods().search(uid, text, "", true, false, 200); break;
                case "custom": out = g.db.foods().search(uid, text, "", false, true, 200); break;
                case "all": out = g.db.foods().search(uid, text, "", false, false, 200); break;
                default: out = g.db.foods().search(uid, text, q.filter, false, false, 200);
            }
            g.postResult(cb, Result.ok(out));
        });
    }

    private static List<FoodEntity> filterText(List<FoodEntity> list, String text) {
        if (text.isEmpty()) return list;
        List<FoodEntity> out = new ArrayList<>();
        for (FoodEntity f : list) {
            if (f.name.toLowerCase(Locale.ROOT).contains(text) || (f.nameBn != null && f.nameBn.contains(text))
                    || (f.aliases != null && f.aliases.contains(text))) out.add(f);
        }
        return out;
    }

    public void favoriteIds(Result.Callback<List<String>> cb) {
        g.runIo(() -> g.postResult(cb, Result.ok(g.db.foods().favoriteIds(g.session.requireUid()))));
    }

    public void frequent(Result.Callback<List<FrequentFood>> cb) {
        g.runIo(() -> g.postResult(cb, Result.ok(g.db.foods().frequent(g.session.requireUid(),
                DateUtil.shift(DateUtil.today(), -30), 8))));
    }

    public LiveData<FoodEntity> observe(String id) {
        return g.db.foods().observe(id);
    }

    public LiveData<Boolean> observeFavorite(String uid, String foodId) {
        return g.db.foods().observeIsFavorite(uid, foodId);
    }

    public LiveData<List<FoodEntity>> observeCustom(String uid) {
        return g.db.foods().observeCustom(uid);
    }

    public void load(String id, Result.Callback<FoodEntity> cb) {
        g.runIo(() -> g.postResult(cb, Result.ok(g.db.foods().byId(id))));
    }

    public void servings(String foodId, Result.Callback<List<ServingEntity>> cb) {
        g.runIo(() -> g.postResult(cb, Result.ok(g.db.foods().servings(foodId))));
    }

    public void toggleFavorite(String foodId) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            g.db.runInTransaction(() -> {
                FavoriteEntity f = g.db.foods().favorite(uid, foodId);
                long now = DateUtil.nowMillis();
                if (f == null) {
                    f = new FavoriteEntity();
                    f.uid = uid;
                    f.foodId = foodId;
                    f.createdAt = now;
                    f.deleted = false;
                } else {
                    f.deleted = !f.deleted;
                }
                f.updatedAt = now;
                g.db.foods().upsertFavorite(f);
                g.outbox.enqueue(uid, Collections.FAVORITES, foodId);
            });
            g.outbox.kick();
        });
    }

    /**
     * Barcode lookup: a product scanned before is served from Room (works offline); otherwise Open Food
     * Facts is queried and the product is saved as a personal food with provenance "sourced".
     * Result.ok(null) = product unknown.
     */
    public void lookupBarcode(String code, Result.Callback<FoodEntity> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            String id = "usr_bc_" + code;
            FoodEntity cached = g.db.foods().byId(id);
            if (cached != null && !cached.deleted) {
                g.postResult(cb, Result.ok(cached));
                return;
            }
            try {
                FoodEntity f = new com.transformsai.nutrifitai.data.remote.OpenFoodFacts().lookup(code);
                if (f == null) {
                    g.postResult(cb, Result.ok(null));
                    return;
                }
                f.id = id;
                f.ownerUid = uid;
                f.aliases = (f.name + "|" + code).toLowerCase(Locale.ROOT);
                f.updatedAt = DateUtil.nowMillis();
                g.db.runInTransaction(() -> {
                    g.db.foods().upsertFood(f);
                    g.outbox.enqueue(uid, Collections.FOODS, f.id);
                });
                g.outbox.kick();
                g.postResult(cb, Result.ok(f));
            } catch (java.io.IOException e) {
                g.postResult(cb, Result.fail("Could not reach the product database. Check your connection."));
            } catch (RuntimeException e) {
                g.postResult(cb, Result.fail("The product data could not be read."));
            }
        });
    }

    /** Create or update a personal food. Values are per 100 g. */
    public void saveCustom(FoodEntity f, Result.Callback<FoodEntity> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            if (f.id.isEmpty()) f.id = "usr_" + Ids.newId();
            f.ownerUid = uid;
            if (f.provenance == null) f.provenance = FoodEntity.PROV_USER;
            if (f.emoji == null) f.emoji = "🍽";
            f.aliases = (f.name + (f.nameBn == null ? "" : "|" + f.nameBn)).toLowerCase(Locale.ROOT);
            f.updatedAt = DateUtil.nowMillis();
            g.db.runInTransaction(() -> {
                g.db.foods().upsertFood(f);
                g.outbox.enqueue(uid, Collections.FOODS, f.id);
            });
            g.outbox.kick();
            g.postResult(cb, Result.ok(f));
        });
    }

    public void deleteCustom(String id, Result.Callback<Void> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            FoodEntity f = g.db.foods().byId(id);
            if (f == null || !uid.equals(f.ownerUid)) {
                g.postResult(cb, Result.fail("Only your own foods can be deleted."));
                return;
            }
            f.deleted = true;
            f.updatedAt = DateUtil.nowMillis();
            g.db.runInTransaction(() -> {
                g.db.foods().upsertFood(f);
                g.outbox.enqueue(uid, Collections.FOODS, f.id);
            });
            g.outbox.kick();
            g.postResult(cb, Result.ok(null));
        });
    }
}
