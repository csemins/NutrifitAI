package com.transformsai.nutrifitai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.rag.RagQuery;
import com.transformsai.nutrifitai.data.remote.OpenFoodFacts;
import com.transformsai.nutrifitai.fitness.ExerciseLibrary;
import com.transformsai.nutrifitai.fitness.Muscles;
import com.transformsai.nutrifitai.fitness.WorkoutEngine;
import com.transformsai.nutrifitai.fitness.WorkoutMath;

import org.junit.Test;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Coach retrieval query parsing, muscle load maths, barcode product parsing. */
public class RagAndFeaturesTest {
    private static final String TODAY = "2026-11-06"; // a Friday

    @Test
    public void datesFromQuestions() {
        RagQuery q = RagQuery.analyse("What did I eat today?", TODAY);
        assertEquals(TODAY, q.from);
        assertTrue(q.topics.contains(RagQuery.FOOD));
        q = RagQuery.analyse("how much water did i drink yesterday", TODAY);
        assertEquals("2026-11-05", q.from);
        assertTrue(q.topics.contains(RagQuery.WATER));
        q = RagQuery.analyse("How was my protein this week?", TODAY);
        assertEquals("2026-10-31", q.from);
        assertEquals(TODAY, q.to);
        q = RagQuery.analyse("what did I have on Monday", TODAY);
        assertEquals("2026-11-02", q.from);
        q = RagQuery.analyse("lunch on 3 nov", TODAY);
        assertEquals("2026-11-03", q.from);
        q = RagQuery.analyse("3 days ago", TODAY);
        assertEquals("2026-11-03", q.from);
        q = RagQuery.analyse("আজ আমি কী খেয়েছি", TODAY);
        assertEquals(TODAY, q.from);
        assertTrue(q.topics.contains(RagQuery.FOOD));
    }

    @Test
    public void topicsAndTermsAvoidFalsePositives() {
        RagQuery q = RagQuery.analyse("Was my breakfast too heavy?", TODAY);
        assertFalse(q.topics.contains(RagQuery.FASTING)); // "breakfast" contains "fast"
        q = RagQuery.analyse("how many calories in khichuri and dal", TODAY);
        assertTrue(q.terms.contains("khichuri"));
        assertTrue(q.terms.contains("dal"));
        assertFalse(q.explicitDate);
        assertTrue(RagQuery.analyse("tips for better sleep", TODAY).general());
    }

    @Test
    public void muscleLoadFromCompletedWork() throws Exception {
        ExerciseLibrary lib;
        try (FileInputStream g = new FileInputStream("src/main/assets/exercises/guided.json")) {
            lib = ExerciseLibrary.fromStreams(g, null);
        }
        List<WorkoutEngine.StepResult> rs = new ArrayList<>();
        WorkoutEngine.StepResult squat = new WorkoutEngine.StepResult();
        squat.exerciseId = "squat";
        squat.reps = 30;
        rs.add(squat);
        WorkoutEngine.StepResult plank = new WorkoutEngine.StepResult();
        plank.exerciseId = "plank";
        plank.hold = true;
        plank.heldMs = 60_000;
        rs.add(plank);
        Map<String, Float> m = Muscles.intensity(rs, lib);
        assertEquals(0.6f, m.get(Muscles.QUADS), 0.01f);      // (30 squat reps x 1.0 + 30 s-equivalents of plank x 0.2) / 60
        assertEquals(0.5f, m.get(Muscles.ABS), 0.01f);        // 30 s-equivalents x 1.0 / 60
        assertNull(m.get(Muscles.BICEPS));
        assertEquals(Muscles.QUADS, Muscles.ranked(m).get(0).getKey().equals(Muscles.QUADS) ? Muscles.QUADS : Muscles.ABS);
        // saved-session round trip keeps rejected reps for the form score
        squat.rejected = 4;
        squat.camera = true;
        int[] f = WorkoutMath.formCounts(rs);
        assertEquals(30, f[0]);
        assertEquals(4, f[1]);
    }

    @Test
    public void openFoodFactsParsing() {
        String json = "{\"product\":{\"product_name\":\"Digestive biscuits\",\"brands\":\"Olympic, Other\","
                + "\"serving_size\":\"2 biscuits (30 g)\",\"serving_quantity\":30,"
                + "\"nutriments\":{\"energy-kcal_100g\":480,\"proteins_100g\":7,\"carbohydrates_100g\":65,\"fat_100g\":21,"
                + "\"sodium_100g\":0.4}}}";
        FoodEntity f = OpenFoodFacts.parse("8941100500123", json);
        assertNotNull(f);
        assertEquals("Digestive biscuits (Olympic)", f.name);
        assertEquals(480, f.kcal100, 0.01);
        assertEquals(400, f.sodiumMg100, 0.01);
        assertNull(f.fibre100);                  // unknown stays unknown
        assertEquals(30, f.servingGrams, 0.01);
        assertEquals(FoodEntity.PROV_SOURCED, f.provenance);
        // implausible or incomplete products are rejected, never saved
        assertNull(OpenFoodFacts.parse("1", "{\"product\":{\"product_name\":\"X\",\"nutriments\":{\"energy-kcal_100g\":2000,"
                + "\"proteins_100g\":1,\"carbohydrates_100g\":1,\"fat_100g\":1}}}"));
        assertNull(OpenFoodFacts.parse("1", "{\"product\":{\"product_name\":\"X\",\"nutriments\":{\"proteins_100g\":1}}}"));
        assertNull(OpenFoodFacts.parse("1", "{\"status\":0}"));
        assertFalse(OpenFoodFacts.validCode("12ab"));
        assertTrue(OpenFoodFacts.validCode("8941100500123"));
    }
}
