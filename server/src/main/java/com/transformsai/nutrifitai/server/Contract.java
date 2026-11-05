package com.transformsai.nutrifitai.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validation for the versioned AI contracts. Mirrors the Android parsers so the app never depends
 * on the server alone. Food totals are always recomputed from validated items.
 */
public final class Contract {
    private Contract() { }

    public static final int MAX_ITEMS = 20, MAX_NAME = 80, MAX_TEXT = 200, MAX_LIST = 8;
    public static final double MAX_WEIGHT_G = 3000, MAX_ITEM_KCAL = 5000, MAX_MACRO_G = 500, MAX_FIBRE_G = 150,
            MAX_SODIUM_MG = 20000, MAX_KCAL_PER_100G = 950;
    public static final int MAX_STEPS = 24;

    public static final class ContractError extends Exception {
        public final String code;

        public ContractError(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    static String clean(String s) {
        return s.replaceAll("[\\x00-\\x1f\\x7f]", " ").replaceAll("\\s+", " ").trim();
    }

    private static final Pattern FENCE = Pattern.compile("(?is)^```(?:json)?\\s*\\n(.*?)\\n?```\\s*$");

    /** Bounded recovery: as-is, then a fenced block, then the outermost {...}. */
    public static JsonObject extractJson(String raw) throws ContractError {
        if (raw == null || raw.isBlank()) throw new ContractError("empty", "empty response");
        String s = raw.trim();
        List<String> tries = new ArrayList<>();
        tries.add(s);
        Matcher m = FENCE.matcher(s);
        if (m.matches()) tries.add(m.group(1));
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a >= 0 && b > a) tries.add(s.substring(a, b + 1));
        for (String t : tries) {
            try {
                JsonElement e = JsonParser.parseString(t);
                if (e.isJsonObject()) return e.getAsJsonObject();
            } catch (RuntimeException ignored) {
                // try the next candidate
            }
        }
        throw new ContractError("malformed_json", "response was not valid JSON");
    }

    // ---------------------------------------------------------------- helpers
    private static boolean isNum(JsonElement v) {
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber();
    }

    static double num(JsonElement v, String path, double min, double max) throws ContractError {
        if (!isNum(v)) throw new ContractError("invalid_field", path);
        double d = v.getAsDouble();
        if (!Double.isFinite(d) || d < min || d > max) throw new ContractError("invalid_field", path);
        return d;
    }

    static Double optNum(JsonElement v, String path, double max) throws ContractError {
        if (v == null || v.isJsonNull()) return null;
        return num(v, path, 0, max);
    }

    static String str(JsonElement v, String path, int max) throws ContractError {
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) throw new ContractError("invalid_field", path);
        String c = clean(v.getAsString());
        if (c.isEmpty() || c.length() > max) throw new ContractError("invalid_field", path);
        return c;
    }

    static String optStr(JsonElement v, int max) {
        if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) return null;
        String c = clean(v.getAsString());
        if (c.isEmpty()) return null;
        return c.length() > max ? c.substring(0, max) : c;
    }

    static JsonArray strList(JsonElement v) {
        JsonArray out = new JsonArray();
        if (v == null || !v.isJsonArray()) return out;
        for (JsonElement e : v.getAsJsonArray()) {
            String s = optStr(e, MAX_TEXT);
            if (s != null && out.size() < MAX_LIST) out.add(s);
        }
        return out;
    }

    private static JsonObject obj(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    // ---------------------------------------------------------------- food analysis
    public static JsonObject validateItem(JsonElement el, int i) throws ContractError {
        String p = "items[" + i + "]";
        if (el == null || !el.isJsonObject()) throw new ContractError("invalid_field", p);
        JsonObject o = el.getAsJsonObject();
        JsonObject portion = obj(o.get("portion"));
        JsonObject n = obj(o.get("nutrition_for_portion"));
        JsonObject item = new JsonObject();
        String id = optStr(o.get("id"), 40);
        item.addProperty("id", id != null ? id : "item_" + (i + 1));
        item.addProperty("name", str(o.get("name"), p + ".name", MAX_NAME));
        String bn = optStr(o.get("name_bn"), MAX_NAME);
        item.add("name_bn", bn == null ? JsonNull.INSTANCE : new JsonPrimitive(bn));
        item.addProperty("confidence", num(o.get("confidence"), p + ".confidence", 0, 1));
        JsonObject po = new JsonObject();
        po.addProperty("quantity", num(portion.get("quantity"), p + ".portion.quantity", Double.MIN_VALUE, 100));
        po.addProperty("unit", str(portion.get("unit"), p + ".portion.unit", 30));
        double weight = num(portion.get("estimated_weight_g"), p + ".portion.estimated_weight_g", Double.MIN_VALUE, MAX_WEIGHT_G);
        po.addProperty("estimated_weight_g", weight);
        item.add("portion", po);
        JsonObject nu = new JsonObject();
        double kcal = num(n.get("calories_kcal"), p + ".calories_kcal", 0, MAX_ITEM_KCAL);
        nu.addProperty("calories_kcal", kcal);
        nu.addProperty("protein_g", num(n.get("protein_g"), p + ".protein_g", 0, MAX_MACRO_G));
        nu.addProperty("carbohydrates_g", num(n.get("carbohydrates_g"), p + ".carbohydrates_g", 0, MAX_MACRO_G));
        nu.addProperty("fat_g", num(n.get("fat_g"), p + ".fat_g", 0, MAX_MACRO_G));
        addOpt(nu, "fibre_g", optNum(n.get("fibre_g"), p + ".fibre_g", MAX_FIBRE_G));
        addOpt(nu, "sugar_g", optNum(n.get("sugar_g"), p + ".sugar_g", MAX_MACRO_G));
        addOpt(nu, "sodium_mg", optNum(n.get("sodium_mg"), p + ".sodium_mg", MAX_SODIUM_MG));
        item.add("nutrition_for_portion", nu);
        item.addProperty("source", "ai_estimate");
        item.add("assumptions", strList(o.get("assumptions")));
        if (kcal * 100 / weight > MAX_KCAL_PER_100G) throw new ContractError("invalid_field", p + ".calories_kcal (density)");
        JsonElement r = o.get("calorie_range_kcal");
        if (r != null && r.isJsonObject()) {
            double min = num(r.getAsJsonObject().get("min"), p + ".calorie_range_kcal.min", 0, MAX_ITEM_KCAL * 2);
            double max = num(r.getAsJsonObject().get("max"), p + ".calorie_range_kcal.max", min, MAX_ITEM_KCAL * 2);
            JsonObject range = new JsonObject();
            range.addProperty("min", min);
            range.addProperty("max", max);
            item.add("calorie_range_kcal", range);
        }
        return item;
    }

    private static void addOpt(JsonObject o, String k, Double v) {
        o.add(k, v == null ? JsonNull.INSTANCE : new JsonPrimitive(v));
    }

    public static JsonObject validateAnalysis(JsonObject o, String inputType) throws ContractError {
        if (o == null) throw new ContractError("invalid_field", "root");
        String version = optStr(o.get("schema_version"), 10);
        if (version == null || !(version.equals("1") || version.startsWith("1."))) throw new ContractError("unsupported_schema", "schema_version");
        String status = optStr(o.get("status"), 20);
        if ((status == null || !Set.of("success", "uncertain", "non_food").contains(status))) throw new ContractError("invalid_status", "status");
        JsonElement items = o.get("items");
        if (items == null || !items.isJsonArray()) throw new ContractError("invalid_field", "items");
        if (items.getAsJsonArray().size() > MAX_ITEMS) throw new ContractError("too_many_items", "items");
        boolean isFood = o.has("is_food") && isTrue(o.get("is_food"));
        JsonObject out = new JsonObject();
        out.addProperty("schema_version", "1.0");
        out.addProperty("input_type", inputType);
        out.addProperty("needs_confirmation", true);
        JsonArray valid = new JsonArray();
        if ("non_food".equals(status) || !isFood) {
            status = "non_food";
            isFood = false;
        } else {
            boolean low = false;
            int i = 0;
            for (JsonElement e : items.getAsJsonArray()) {
                JsonObject it = validateItem(e, i++);
                if (it.get("confidence").getAsDouble() < 0.5) low = true;
                valid.add(it);
            }
            if (valid.isEmpty() && "success".equals(status)) throw new ContractError("no_items", "items");
            if (low && "success".equals(status)) status = "uncertain";
        }
        out.addProperty("status", status);
        out.addProperty("is_food", isFood);
        out.add("items", valid);
        out.add("totals", totals(valid));
        JsonArray warnings = strList(o.get("warnings"));
        boolean hasEstimate = false;
        for (JsonElement w : warnings) if (w.getAsString().toLowerCase().contains("estimate")) hasEstimate = true;
        if (!hasEstimate) warnings.add("Image and text based nutrition values are estimates and may be inaccurate.");
        out.add("warnings", warnings);
        out.add("clarifying_questions", strList(o.get("clarifying_questions")));
        return out;
    }

    private static boolean isTrue(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    public static JsonObject totals(JsonArray items) {
        double kcal = 0, p = 0, c = 0, f = 0;
        Double fibre = null;
        for (JsonElement e : items) {
            JsonObject n = e.getAsJsonObject().getAsJsonObject("nutrition_for_portion");
            kcal += n.get("calories_kcal").getAsDouble();
            p += n.get("protein_g").getAsDouble();
            c += n.get("carbohydrates_g").getAsDouble();
            f += n.get("fat_g").getAsDouble();
            if (!n.get("fibre_g").isJsonNull()) fibre = (fibre == null ? 0 : fibre) + n.get("fibre_g").getAsDouble();
        }
        JsonObject t = new JsonObject();
        t.addProperty("calories_kcal", r1(kcal));
        t.addProperty("protein_g", r1(p));
        t.addProperty("carbohydrates_g", r1(c));
        t.addProperty("fat_g", r1(f));
        t.add("fibre_g", fibre == null ? JsonNull.INSTANCE : new JsonPrimitive(r1(fibre)));
        return t;
    }

    static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    // ---------------------------------------------------------------- assistant
    public static JsonObject validateAssistant(JsonObject o) throws ContractError {
        String reply = o == null ? null : optStr(o.get("reply"), 4000);
        if (reply == null) throw new ContractError("invalid_field", "reply");
        // keep line breaks for readability, drop other control characters
        String raw = o.get("reply").getAsString().replace("\u0000", "").trim();
        reply = raw.length() > 2000 ? raw.substring(0, 2000) : raw;
        JsonArray suggestions = new JsonArray();
        JsonElement sv = o.get("suggestions");
        if (sv != null && sv.isJsonArray()) {
            for (JsonElement se : sv.getAsJsonArray()) {
                if (suggestions.size() >= 3) break;
                try {
                    JsonObject s = se.getAsJsonObject();
                    JsonArray items = new JsonArray();
                    JsonElement iv = s.get("items");
                    if (iv != null && iv.isJsonArray()) {
                        int i = 0;
                        for (JsonElement ie : iv.getAsJsonArray()) {
                            if (i >= 8) break;
                            items.add(validateItem(ie, i++));
                        }
                    }
                    if (items.isEmpty()) continue;
                    JsonObject out = new JsonObject();
                    out.addProperty("title", str(s.get("title"), "title", 80));
                    String mt = optStr(s.get("meal_type"), 20);
                    out.addProperty("meal_type", (mt != null && Set.of("breakfast", "lunch", "dinner", "snack").contains(mt)) ? mt : "snack");
                    String why = optStr(s.get("rationale"), 200);
                    out.addProperty("rationale", why == null ? "" : why);
                    out.add("items", items);
                    suggestions.add(out);
                } catch (ContractError | RuntimeException ignored) {
                    // drop the invalid suggestion, keep the reply
                }
            }
        }
        String scope = optStr(o.get("scope"), 20);
        if (scope == null) scope = "in_scope"; // older replies without the label
        if (!SCOPES.contains(scope)) throw new ContractError("invalid_field", "scope");
        JsonObject out = new JsonObject();
        out.addProperty("scope", scope);
        out.addProperty("reply", "in_scope".equals(scope) ? reply : guardReply(scope, reply));
        out.add("suggestions", "in_scope".equals(scope) ? suggestions : new JsonArray());
        out.add("actions", new JsonArray());
        return out;
    }

    static final Set<String> SCOPES = Set.of("in_scope", "out_of_scope", "unsafe");
    static final String OUT_OF_SCOPE = "I can only help with nutrition, fitness and health. Try asking about your meals, water, "
            + "workouts or progress.";
    static final String UNSAFE = "I can't help with that. If you are thinking about harming yourself or are in danger, please call 999 "
            + "or talk to someone you trust now. I'm here for safe nutrition and fitness support.";

    /** Off-topic or unsafe: keep the model's short text only if it is short, otherwise use a fixed message. */
    static String guardReply(String scope, String modelReply) {
        if ("unsafe".equals(scope)) return UNSAFE;
        return modelReply.length() <= 280 && !modelReply.isEmpty() ? modelReply : OUT_OF_SCOPE;
    }

    static final Set<String> SCREENS = Set.of("hydration", "steps", "fasting", "progress", "train", "meals", "weight",
            "recipes", "ai_workout", "achievements", "scan");

    /** App actions the coach may propose; anything unknown or out of range is dropped. Never throws. */
    public static JsonArray validateActions(JsonElement v) {
        JsonArray out = new JsonArray();
        if (v == null || !v.isJsonArray()) return out;
        for (JsonElement e : v.getAsJsonArray()) {
            if (out.size() >= 3 || !e.isJsonObject()) continue;
            JsonObject a = e.getAsJsonObject();
            String type = optStr(a.get("type"), 20);
            if (type == null) continue;
            JsonObject x = new JsonObject();
            x.addProperty("type", type);
            try {
                switch (type) {
                    case "log_water":
                        x.addProperty("ml", (int) Math.round(num(a.get("ml"), "ml", 50, 3000)));
                        break;
                    case "log_weight":
                        x.addProperty("kg", r1(num(a.get("kg"), "kg", 20, 300)));
                        break;
                    case "start_fast":
                        x.addProperty("hours", (int) Math.round(num(a.get("hours"), "hours", 8, 36)));
                        break;
                    case "set_step_goal":
                        x.addProperty("steps", (int) Math.round(num(a.get("steps"), "steps", 1000, 50000)));
                        break;
                    case "log_food": {
                        String text = optStr(a.get("text"), 300);
                        if (text == null || text.trim().length() < 3) continue;
                        x.addProperty("text", text.trim());
                        String mt = optStr(a.get("meal_type"), 20);
                        x.addProperty("meal_type", mt != null && Set.of("breakfast", "lunch", "dinner", "snack").contains(mt) ? mt : "snack");
                        break;
                    }
                    case "open": {
                        String sc = optStr(a.get("screen"), 20);
                        if (sc == null || !SCREENS.contains(sc)) continue;
                        x.addProperty("screen", sc);
                        break;
                    }
                    default:
                        continue;
                }
            } catch (ContractError err) {
                continue;
            }
            out.add(x);
        }
        return out;
    }

    // ---------------------------------------------------------------- workout plan
    /**
     * Validates an AI workout plan against the exercises the app can actually run.
     *
     * @param allowed exercise id -> "reps" or "hold"
     */
    public static JsonObject validateWorkout(JsonObject o, Map<String, String> allowed) throws ContractError {
        if (o == null) throw new ContractError("invalid_field", "root");
        JsonObject out = new JsonObject();
        out.addProperty("schema_version", "1.0");
        out.addProperty("title", str(o.get("title"), "title", 60));
        String summary = optStr(o.get("summary"), 240);
        out.addProperty("summary", summary == null ? "" : summary);
        JsonElement sv = o.get("steps");
        if (sv == null || !sv.isJsonArray()) throw new ContractError("invalid_field", "steps");
        JsonArray steps = new JsonArray();
        int total = 0;
        for (JsonElement se : sv.getAsJsonArray()) {
            if (steps.size() >= MAX_STEPS) break;
            if (!se.isJsonObject()) continue;
            JsonObject s = se.getAsJsonObject();
            String id = optStr(s.get("exercise_id"), 80);
            String kind = id == null ? null : allowed.get(id);
            if (kind == null) continue; // never accept an exercise the app does not know
            JsonObject step = new JsonObject();
            step.addProperty("exercise_id", id);
            try {
                if ("hold".equals(kind)) {
                    int sec = (int) Math.round(num(s.get("seconds"), "seconds", 5, 300));
                    step.addProperty("seconds", sec);
                    total += sec;
                } else {
                    int reps = (int) Math.round(num(s.get("reps"), "reps", 1, 100));
                    step.addProperty("reps", reps);
                    total += reps * 3;
                }
                int rest = s.has("rest_seconds") && isNum(s.get("rest_seconds"))
                        ? (int) Math.round(num(s.get("rest_seconds"), "rest_seconds", 0, 180)) : 20;
                step.addProperty("rest_seconds", rest);
                total += rest;
            } catch (ContractError e) {
                continue; // drop malformed step
            }
            String cue = optStr(s.get("cue"), 120);
            step.addProperty("cue", cue == null ? "" : cue);
            String phase = optStr(s.get("phase"), 12);
            step.addProperty("phase", phase != null && Set.of("warmup", "main", "cooldown").contains(phase) ? phase : "main");
            steps.add(step);
        }
        if (steps.isEmpty()) throw new ContractError("no_steps", "steps");
        out.add("steps", steps);
        out.addProperty("estimated_minutes", Math.max(1, Math.round(total / 60f)));
        return out;
    }
}
