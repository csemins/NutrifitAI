package com.transformsai.nutrifitai.ai;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Strict parser and validator for the versioned food-analysis contract (schema 1.x).
 * Recovery is bounded: (1) parse as-is, (2) strip a Markdown code fence, (3) take the outermost
 * {...} span. If all three fail, an actionable error is raised. Nothing is ever invented.
 */
public final class AiResultParser {
    public static final String SUPPORTED_MAJOR = "1";
    public static final double LOW_CONFIDENCE = 0.5;
    public static final int MAX_ITEMS = 20;
    public static final int MAX_NAME = 80;
    public static final int MAX_TEXT = 200;
    public static final int MAX_LIST = 8;
    public static final double MAX_WEIGHT_G = 3000;
    public static final double MAX_ITEM_KCAL = 5000;
    public static final double MAX_MACRO_G = 500;
    public static final double MAX_FIBRE_G = 150;
    public static final double MAX_SODIUM_MG = 20000;

    private AiResultParser() {
    }

    public static AiResult parse(String raw) throws AiParseException {
        JsonObject root = parseObject(raw);
        return validate(root);
    }

    /** Bounded JSON recovery. */
    public static JsonObject parseObject(String raw) throws AiParseException {
        if (raw == null || raw.trim().isEmpty()) throw new AiParseException("empty", "The AI service returned an empty response.");
        String s = raw.trim();
        JsonObject o = tryStrict(s);
        if (o != null) return o;
        String fenced = stripFence(s);
        if (fenced != null) {
            o = tryStrict(fenced);
            if (o != null) return o;
        }
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a >= 0 && b > a) {
            o = tryStrict(s.substring(a, b + 1));
            if (o != null) return o;
        }
        throw new AiParseException("malformed_json", "The AI response was not valid JSON. Please try again.");
    }

    @Nullable
    private static JsonObject tryStrict(String s) {
        try {
            JsonReader r = new JsonReader(new StringReader(s));
            r.setStrictness(Strictness.STRICT);
            JsonElement e = JsonParser.parseReader(r);
            if (r.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) return null;
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    @Nullable
    private static String stripFence(String s) {
        if (!s.startsWith("```")) return null;
        int nl = s.indexOf('\n');
        int end = s.lastIndexOf("```");
        if (nl < 0 || end <= nl) return null;
        return s.substring(nl + 1, end).trim();
    }

    public static AiResult validate(JsonObject o) throws AiParseException {
        AiResult r = new AiResult();
        r.schemaVersion = reqString(o, "schema_version", 10);
        if (!r.schemaVersion.equals(SUPPORTED_MAJOR) && !r.schemaVersion.startsWith(SUPPORTED_MAJOR + ".")) {
            throw new AiParseException("unsupported_schema", "Unsupported analysis format version " + r.schemaVersion + ".");
        }
        r.status = reqString(o, "status", 20);
        if (!r.status.equals(AiResult.STATUS_SUCCESS) && !r.status.equals(AiResult.STATUS_UNCERTAIN)
                && !r.status.equals(AiResult.STATUS_NON_FOOD)) {
            if ("error".equals(r.status)) {
                throw new AiParseException("model_error", "The AI could not analyse this input. Try a clearer photo or description.");
            }
            throw new AiParseException("invalid_status", "Unexpected analysis status.");
        }
        r.inputType = reqString(o, "input_type", 10);
        if (!r.inputType.equals("image") && !r.inputType.equals("text")) throw invalid("input_type");
        r.isFood = reqBool(o, "is_food");
        r.needsConfirmation = !o.has("needs_confirmation") || reqBool(o, "needs_confirmation");
        r.warnings = stringList(o, "warnings");
        r.clarifyingQuestions = stringList(o, "clarifying_questions");

        JsonArray items = reqArray(o, "items");
        if (items.size() > MAX_ITEMS) throw new AiParseException("too_many_items", "The AI returned too many items.");
        if (r.status.equals(AiResult.STATUS_NON_FOOD) || !r.isFood) {
            // Non-food results must not carry items; anything returned is discarded, not trusted.
            r.status = AiResult.STATUS_NON_FOOD;
            r.isFood = false;
            r.items = new ArrayList<>();
            return r;
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isJsonObject()) throw invalid("items[" + i + "]");
            AiItem it = parseItem(items.get(i).getAsJsonObject(), i);
            if (!ids.add(it.id)) it.id = it.id + "_" + i;
            r.items.add(it);
        }
        if (r.items.isEmpty() && r.status.equals(AiResult.STATUS_SUCCESS)) {
            throw new AiParseException("no_items", "No foods were identified. Try another photo or add a description.");
        }
        if (r.anyLowConfidence() && r.status.equals(AiResult.STATUS_SUCCESS)) {
            r.status = AiResult.STATUS_UNCERTAIN;
        }
        r.needsConfirmation = true; // the app always requires explicit confirmation
        return r;
    }

    private static AiItem parseItem(JsonObject o, int index) throws AiParseException {
        String path = "items[" + index + "]";
        AiItem it = new AiItem();
        it.id = optString(o, "id", 40);
        if (it.id == null || it.id.isEmpty()) it.id = "item_" + (index + 1);
        it.name = clean(reqString(o, "name", MAX_NAME));
        if (it.name.isEmpty()) throw invalid(path + ".name");
        it.nameBn = optString(o, "name_bn", MAX_NAME);
        it.confidence = reqNumber(o, "confidence", path);
        if (it.confidence < 0 || it.confidence > 1) throw invalid(path + ".confidence");

        JsonObject portion = reqObject(o, "portion", path);
        it.quantity = reqNumber(portion, "quantity", path + ".portion");
        if (it.quantity <= 0 || it.quantity > 100) throw invalid(path + ".portion.quantity");
        it.unit = clean(reqString(portion, "unit", 30));
        it.weightG = reqNumber(portion, "estimated_weight_g", path + ".portion");
        if (it.weightG <= 0 || it.weightG > MAX_WEIGHT_G) throw invalid(path + ".portion.estimated_weight_g");

        JsonObject n = reqObject(o, "nutrition_for_portion", path);
        it.kcal = bounded(n, "calories_kcal", MAX_ITEM_KCAL, path);
        it.protein = bounded(n, "protein_g", MAX_MACRO_G, path);
        it.carbs = bounded(n, "carbohydrates_g", MAX_MACRO_G, path);
        it.fat = bounded(n, "fat_g", MAX_MACRO_G, path);
        it.fibre = optBounded(n, "fibre_g", MAX_FIBRE_G, path);
        it.sugar = optBounded(n, "sugar_g", MAX_MACRO_G, path);
        it.sodiumMg = optBounded(n, "sodium_mg", MAX_SODIUM_MG, path);
        // Energy density sanity check: nothing edible exceeds ~900 kcal per 100 g (pure fat).
        if (it.kcal * 100 / it.weightG > 950) throw invalid(path + ".calories_kcal (density)");

        if (o.has("calorie_range_kcal") && !o.get("calorie_range_kcal").isJsonNull()) {
            JsonObject range = reqObject(o, "calorie_range_kcal", path);
            double min = reqNumber(range, "min", path + ".calorie_range_kcal");
            double max = reqNumber(range, "max", path + ".calorie_range_kcal");
            if (min < 0 || max < min || max > MAX_ITEM_KCAL * 2) throw invalid(path + ".calorie_range_kcal");
            it.kcalMin = min;
            it.kcalMax = max;
        }
        String source = optString(o, "source", 20);
        it.source = "ai_estimate"; // whatever the model says, provenance is an AI estimate
        if (source != null && !source.equals("ai_estimate")) {
            it.assumptions.add("Reported source normalised to AI estimate");
        }
        for (String a : stringList(o, "assumptions")) if (it.assumptions.size() < MAX_LIST) it.assumptions.add(a);
        return it;
    }

    // ---------- helpers ----------
    private static AiParseException invalid(String field) {
        return new AiParseException("invalid_field", "The AI response had an invalid value (" + field + ").");
    }

    private static String reqString(JsonObject o, String key, int max) throws AiParseException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw invalid(key);
        String s = e.getAsString().trim();
        if (s.length() > max) throw invalid(key);
        return s;
    }

    @Nullable
    private static String optString(JsonObject o, String key, int max) throws AiParseException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw invalid(key);
        String s = clean(e.getAsString());
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static boolean reqBool(JsonObject o, String key) throws AiParseException {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw invalid(key);
        return e.getAsBoolean();
    }

    private static JsonArray reqArray(JsonObject o, String key) throws AiParseException {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) throw invalid(key);
        return e.getAsJsonArray();
    }

    private static JsonObject reqObject(JsonObject o, String key, String path) throws AiParseException {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonObject()) throw invalid(path + "." + key);
        return e.getAsJsonObject();
    }

    private static double reqNumber(JsonObject o, String key, String path) throws AiParseException {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) throw invalid(path + "." + key);
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) throw invalid(path + "." + key);
        double v = p.getAsDouble();
        if (Double.isNaN(v) || Double.isInfinite(v)) throw invalid(path + "." + key);
        return v;
    }

    private static double bounded(JsonObject o, String key, double max, String path) throws AiParseException {
        double v = reqNumber(o, key, path);
        if (v < 0 || v > max) throw invalid(path + "." + key);
        return v;
    }

    @Nullable
    private static Double optBounded(JsonObject o, String key, double max, String path) throws AiParseException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        return bounded(o, key, max, path);
    }

    private static List<String> stringList(JsonObject o, String key) throws AiParseException {
        List<String> out = new ArrayList<>();
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return out;
        if (!e.isJsonArray()) throw invalid(key);
        for (JsonElement x : e.getAsJsonArray()) {
            if (out.size() >= MAX_LIST) break;
            if (x.isJsonPrimitive() && x.getAsJsonPrimitive().isString()) {
                String s = clean(x.getAsString());
                if (!s.isEmpty()) out.add(s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s);
            }
        }
        return out;
    }

    /** Untrusted display text: strip control characters and collapse whitespace. */
    public static String clean(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c)) b.append(' ');
            else b.append(c);
        }
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    /** Normalised JSON for storage in Room (items carry current portion amounts). */
    public static String toJson(AiResult r) {
        JsonObject o = new JsonObject();
        o.addProperty("schema_version", r.schemaVersion);
        o.addProperty("status", r.status);
        o.addProperty("input_type", r.inputType);
        o.addProperty("is_food", r.isFood);
        o.addProperty("needs_confirmation", true);
        JsonArray items = new JsonArray();
        for (AiItem i : r.items) {
            if (i.removed) continue;
            JsonObject it = new JsonObject();
            it.addProperty("id", i.id);
            it.addProperty("name", i.name);
            if (i.nameBn != null) it.addProperty("name_bn", i.nameBn);
            it.addProperty("confidence", i.confidence);
            JsonObject p = new JsonObject();
            p.addProperty("quantity", i.quantity);
            p.addProperty("unit", i.unit);
            p.addProperty("estimated_weight_g", i.weightG);
            it.add("portion", p);
            JsonObject n = new JsonObject();
            n.addProperty("calories_kcal", i.kcal);
            n.addProperty("protein_g", i.protein);
            n.addProperty("carbohydrates_g", i.carbs);
            n.addProperty("fat_g", i.fat);
            n.add("fibre_g", i.fibre == null ? com.google.gson.JsonNull.INSTANCE : new JsonPrimitive(i.fibre));
            n.add("sugar_g", i.sugar == null ? com.google.gson.JsonNull.INSTANCE : new JsonPrimitive(i.sugar));
            n.add("sodium_mg", i.sodiumMg == null ? com.google.gson.JsonNull.INSTANCE : new JsonPrimitive(i.sodiumMg));
            it.add("nutrition_for_portion", n);
            if (i.kcalMin != null && i.kcalMax != null) {
                JsonObject range = new JsonObject();
                range.addProperty("min", i.kcalMin);
                range.addProperty("max", i.kcalMax);
                it.add("calorie_range_kcal", range);
            }
            it.addProperty("source", "ai_estimate");
            JsonArray a = new JsonArray();
            for (String s : i.assumptions) a.add(s);
            it.add("assumptions", a);
            items.add(it);
        }
        o.add("items", items);
        JsonArray w = new JsonArray();
        for (String s : r.warnings) w.add(s);
        o.add("warnings", w);
        JsonArray q = new JsonArray();
        for (String s : r.clarifyingQuestions) q.add(s);
        o.add("clarifying_questions", q);
        return o.toString();
    }

    public static String describeConfidence(double c) {
        return String.format(Locale.getDefault(), "%d%%", Math.round(c * 100));
    }
}
