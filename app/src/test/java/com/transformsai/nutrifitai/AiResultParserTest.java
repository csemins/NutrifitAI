package com.transformsai.nutrifitai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.transformsai.nutrifitai.ai.AiItem;
import com.transformsai.nutrifitai.ai.AiParseException;
import com.transformsai.nutrifitai.ai.AiResult;
import com.transformsai.nutrifitai.ai.AiResultParser;
import com.transformsai.nutrifitai.ai.AssistantReply;
import com.transformsai.nutrifitai.ai.AssistantReplyParser;
import com.transformsai.nutrifitai.domain.Nutrients;

import org.junit.Test;

public class AiResultParserTest {
    static String item(String name, double conf, double weight, double kcal, String extra) {
        return "{\"id\":\"i\",\"name\":\"" + name + "\",\"confidence\":" + conf
                + ",\"portion\":{\"quantity\":1,\"unit\":\"bowl\",\"estimated_weight_g\":" + weight + "},"
                + "\"nutrition_for_portion\":{\"calories_kcal\":" + kcal + ",\"protein_g\":5.4,\"carbohydrates_g\":56.4,"
                + "\"fat_g\":0.6,\"fibre_g\":0.8,\"sugar_g\":null,\"sodium_mg\":null}" + extra
                + ",\"source\":\"ai_estimate\",\"assumptions\":[\"Estimated portion size\"]}";
    }

    static String doc(String status, boolean isFood, String items) {
        return "{\"schema_version\":\"1.0\",\"status\":\"" + status + "\",\"input_type\":\"image\",\"is_food\":" + isFood
                + ",\"needs_confirmation\":true,\"items\":[" + items + "],"
                + "\"totals\":{\"calories_kcal\":99999,\"protein_g\":0,\"carbohydrates_g\":0,\"fat_g\":0,\"fibre_g\":0},"
                + "\"warnings\":[\"Image-based nutrition estimates may be inaccurate.\"],\"clarifying_questions\":[]}";
    }

    @Test
    public void validSuccessRecomputesTotals() throws Exception {
        String json = doc("success", true, item("Cooked white rice", 0.85, 200, 260,
                ",\"calorie_range_kcal\":{\"min\":220,\"max\":310}") + "," + item("Dal", 0.8, 150, 140, ""));
        AiResult r = AiResultParser.parse(json);
        assertEquals("success", r.status);
        assertEquals(2, r.items.size());
        Nutrients t = r.totals();
        assertEquals(400, t.kcal, 1e-9); // model claimed 99999 — ignored
        assertNull(r.items.get(0).sugar);
        assertEquals(220, r.items.get(0).kcalMin, 1e-9);
        assertTrue(r.needsConfirmation);
    }

    @Test
    public void lowConfidenceBecomesUncertain() throws Exception {
        AiResult r = AiResultParser.parse(doc("success", true, item("Blurry curry", 0.3, 200, 260, "")));
        assertEquals("uncertain", r.status);
        assertTrue(r.anyLowConfidence());
    }

    @Test
    public void nonFoodHasNoItems() throws Exception {
        AiResult r = AiResultParser.parse(doc("non_food", false, item("Keyboard", 0.9, 200, 260, "")));
        assertEquals("non_food", r.status);
        assertFalse(r.isFood);
        assertTrue(r.items.isEmpty());
    }

    @Test
    public void recoversFromCodeFenceAndProse() throws Exception {
        String inner = doc("success", true, item("Roti", 0.9, 40, 112, ""));
        assertEquals(1, AiResultParser.parse("```json\n" + inner + "\n```").items.size());
        assertEquals(1, AiResultParser.parse("Here is the JSON: " + inner + " Hope it helps").items.size());
    }

    @Test
    public void malformedJsonFailsWithActionableError() {
        expect("{\"schema_version\": \"1.0\", \"items\": [", "malformed_json");
        expect("", "empty");
        expect("not json at all", "malformed_json");
    }

    @Test
    public void rejectsInvalidValues() {
        expect(doc("success", true, item("Rice", 1.5, 200, 260, "")), "invalid_field");             // confidence > 1
        expect(doc("success", true, item("Rice", 0.8, -5, 260, "")), "invalid_field");              // negative weight
        expect(doc("success", true, item("Rice", 0.8, 200, -1, "")), "invalid_field");              // negative kcal
        expect(doc("success", true, item("Rice", 0.8, 10, 500, "")), "invalid_field");              // 5000 kcal/100g density
        expect(doc("success", true, item("Rice", 0.8, 200, 260,
                ",\"calorie_range_kcal\":{\"min\":300,\"max\":200}")), "invalid_field");             // unordered range
        expect(doc("maybe", true, item("Rice", 0.8, 200, 260, "")), "invalid_status");
        expect(doc("success", true, ""), "no_items");
        expect(doc("success", true, item("Rice", 0.8, 200, 260, "")).replace("\"1.0\"", "\"2.0\""), "unsupported_schema");
        expect(doc("success", true, item("Rice", 0.8, 200, 260, "")).replace("\"calories_kcal\":260.0", "\"calories_kcal\":\"260\""), "invalid_field");
    }

    @Test
    public void tooManyItemsRejected() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            if (i > 0) sb.append(',');
            sb.append(item("Food" + i, 0.8, 100, 100, ""));
        }
        expect(doc("success", true, sb.toString()), "too_many_items");
    }

    @Test
    public void stringsAreSanitisedAndBounded() throws Exception {
        AiResult r = AiResultParser.parse(doc("success", true, item("Rice\\u0000\\n  bowl", 0.9, 200, 260, "")));
        assertEquals("Rice bowl", r.items.get(0).name);
    }

    @Test
    public void editingWeightScalesConsistently() throws Exception {
        AiResult r = AiResultParser.parse(doc("success", true, item("Rice", 0.9, 200, 260, "")));
        AiItem i = r.items.get(0);
        double k100 = i.kcal100();
        i.setWeight(300);
        assertEquals(390, i.kcal, 1e-9);
        assertEquals(k100, i.kcal100(), 1e-9);
        assertEquals(1.5, i.quantity, 1e-9);
    }

    @Test
    public void roundTripThroughStorageJson() throws Exception {
        AiResult r = AiResultParser.parse(doc("success", true, item("Rice", 0.9, 200, 260, "")));
        r.items.get(0).setWeight(100);
        AiResult again = AiResultParser.parse(AiResultParser.toJson(r));
        assertEquals(130, again.items.get(0).kcal, 1e-9);
    }

    @Test
    public void assistantReplyDropsInvalidSuggestions() throws Exception {
        String good = "{\"title\":\"Dal & rice\",\"meal_type\":\"dinner\",\"rationale\":\"fits\",\"items\":[" + item("Rice", 0.9, 200, 260, "") + "]}";
        String bad = "{\"title\":\"\",\"items\":[]}";
        AssistantReply r = AssistantReplyParser.parse("{\"reply\":\"Try this\",\"suggestions\":[" + good + "," + bad + "]}");
        assertEquals("Try this", r.reply);
        assertEquals(1, r.suggestions.size());
        assertEquals("dinner", r.suggestions.get(0).mealType);
    }

    private static void expect(String json, String code) {
        try {
            AiResultParser.parse(json);
            fail("expected " + code);
        } catch (AiParseException e) {
            assertEquals(code, e.code);
        }
    }
}
