package com.transformsai.nutrifitai.ai;

import com.transformsai.nutrifitai.domain.Nutrients;
import com.transformsai.nutrifitai.domain.NutritionMath;

import java.util.ArrayList;
import java.util.List;

/** Validated AI analysis. Totals are always recomputed from items, never taken from the model. */
public class AiResult {
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_UNCERTAIN = "uncertain";
    public static final String STATUS_NON_FOOD = "non_food";

    public String schemaVersion;
    public String status;
    public String inputType;
    public boolean isFood;
    public boolean needsConfirmation = true;
    public List<AiItem> items = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();
    public List<String> clarifyingQuestions = new ArrayList<>();

    public List<AiItem> activeItems() {
        List<AiItem> out = new ArrayList<>();
        for (AiItem i : items) if (!i.removed) out.add(i);
        return out;
    }

    public Nutrients totals() {
        List<Nutrients> list = new ArrayList<>();
        for (AiItem i : activeItems()) {
            Nutrients n = new Nutrients(i.kcal, i.protein, i.carbs, i.fat, i.fibre);
            list.add(n);
        }
        return NutritionMath.sum(list);
    }

    public boolean anyLowConfidence() {
        for (AiItem i : activeItems()) if (i.lowConfidence()) return true;
        return false;
    }
}
