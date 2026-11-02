package com.transformsai.nutrifitai.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Validates assistant replies: {"reply": string, "suggestions": [{title, meal_type, rationale,
 * items: [same item schema as food analysis]}]}. Invalid suggestions are dropped, never repaired.
 */
public final class AssistantReplyParser {
    public static final int MAX_REPLY = 2000;
    public static final int MAX_SUGGESTIONS = 3;

    private AssistantReplyParser() {
    }

    public static AssistantReply parse(String raw) throws AiParseException {
        JsonObject o = AiResultParser.parseObject(raw);
        JsonElement r = o.get("reply");
        if (r == null || !r.isJsonPrimitive() || !r.getAsJsonPrimitive().isString()) {
            throw new AiParseException("invalid_field", "The assistant response was incomplete.");
        }
        AssistantReply out = new AssistantReply();
        String text = r.getAsString().replace("\u0000", "").trim();
        out.reply = text.length() > MAX_REPLY ? text.substring(0, MAX_REPLY) + "…" : text;
        if (out.reply.isEmpty()) throw new AiParseException("empty", "The assistant returned an empty answer.");
        JsonElement s = o.get("suggestions");
        if (s != null && s.isJsonArray()) {
            JsonArray arr = s.getAsJsonArray();
            for (int i = 0; i < arr.size() && out.suggestions.size() < MAX_SUGGESTIONS; i++) {
                try {
                    out.suggestions.add(parseSuggestion(arr.get(i).getAsJsonObject()));
                } catch (Exception ignored) {
                    // Drop invalid suggestion; the text reply is still shown.
                }
            }
        }
        JsonElement src = o.get("sources");
        if (src != null && src.isJsonArray()) {
            for (JsonElement e : src.getAsJsonArray()) {
                if (out.sources.size() >= 8 || !e.isJsonPrimitive()) continue;
                String v = AiResultParser.clean(e.getAsString());
                if (!v.isEmpty() && v.length() <= 60) out.sources.add(v);
            }
        }
        JsonElement acts = o.get("actions");
        if (acts != null && acts.isJsonArray()) {
            for (JsonElement e : acts.getAsJsonArray()) {
                if (out.actions.size() >= 3 || !e.isJsonObject()) continue;
                AssistantReply.Action a = parseAction(e.getAsJsonObject());
                if (a != null) out.actions.add(a);
            }
        }
        JsonElement w = o.get("workout");
        if (w != null && w.isJsonObject() && w.getAsJsonObject().has("steps") && w.getAsJsonObject().get("steps").isJsonArray()
                && w.getAsJsonObject().getAsJsonArray("steps").size() > 0) {
            out.workoutJson = w.toString(); // re-validated against the exercise library when shown or saved
        }
        return out;
    }

    private static final java.util.Set<String> SCREENS = new java.util.HashSet<>(java.util.Arrays.asList("hydration", "steps",
            "fasting", "progress", "train", "meals", "weight", "recipes", "ai_workout", "achievements", "scan"));

    /** Re-validates an action from the server (or storage); null when invalid. */
    static AssistantReply.Action parseAction(JsonObject o) {
        try {
            AssistantReply.Action a = new AssistantReply.Action();
            a.type = o.get("type").getAsString();
            switch (a.type) {
                case "log_water":
                    a.ml = o.get("ml").getAsInt();
                    return a.ml >= 50 && a.ml <= 3000 ? a : null;
                case "log_weight":
                    a.kg = o.get("kg").getAsDouble();
                    return a.kg >= 20 && a.kg <= 300 ? a : null;
                case "start_fast":
                    a.hours = o.get("hours").getAsInt();
                    return a.hours >= 8 && a.hours <= 36 ? a : null;
                case "set_step_goal":
                    a.steps = o.get("steps").getAsInt();
                    return a.steps >= 1000 && a.steps <= 50000 ? a : null;
                case "log_food":
                    a.text = AiResultParser.clean(o.get("text").getAsString());
                    a.mealType = o.has("meal_type") ? o.get("meal_type").getAsString() : "snack";
                    if (!a.mealType.matches("breakfast|lunch|dinner|snack")) a.mealType = "snack";
                    return a.text.length() >= 3 && a.text.length() <= 300 ? a : null;
                case "open":
                    a.screen = o.get("screen").getAsString();
                    return SCREENS.contains(a.screen) ? a : null;
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    static AssistantReply.Suggestion parseSuggestion(JsonObject o) throws AiParseException {
        AssistantReply.Suggestion sug = new AssistantReply.Suggestion();
        sug.title = AiResultParser.clean(o.get("title").getAsString());
        if (sug.title.isEmpty() || sug.title.length() > 80) throw new AiParseException("invalid_field", "title");
        String mt = o.has("meal_type") ? o.get("meal_type").getAsString() : "snack";
        sug.mealType = mt.matches("breakfast|lunch|dinner|snack") ? mt : "snack";
        sug.rationale = o.has("rationale") && o.get("rationale").isJsonPrimitive()
                ? AiResultParser.clean(o.get("rationale").getAsString()) : "";
        if (sug.rationale.length() > 200) sug.rationale = sug.rationale.substring(0, 200);
        // Re-use the food-analysis validator for the item list.
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("schema_version", "1.0");
        wrapper.addProperty("status", "success");
        wrapper.addProperty("input_type", "text");
        wrapper.addProperty("is_food", true);
        wrapper.add("items", o.getAsJsonArray("items"));
        AiResult r = AiResultParser.validate(wrapper);
        if (r.items.isEmpty() || r.items.size() > 8) throw new AiParseException("invalid_field", "items");
        sug.items = r.items;
        return sug;
    }
}
