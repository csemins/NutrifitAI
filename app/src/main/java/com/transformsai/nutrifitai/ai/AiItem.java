package com.transformsai.nutrifitai.ai;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.domain.NutritionMath;

import java.util.ArrayList;
import java.util.List;

/**
 * One detected food. Nutrient fields hold amounts for the CURRENT portion (weightG). Editing the
 * weight scales every nutrient proportionally, so per-100 g density stays consistent.
 */
public class AiItem {
    public String id;
    public String name;
    @Nullable
    public String nameBn;
    public double confidence;
    public double quantity;
    public String unit;
    public double weightG;
    public double kcal;
    public double protein;
    public double carbs;
    public double fat;
    @Nullable
    public Double fibre;
    @Nullable
    public Double sugar;
    @Nullable
    public Double sodiumMg;
    @Nullable
    public Double kcalMin;
    @Nullable
    public Double kcalMax;
    public String source = "ai_estimate";
    public List<String> assumptions = new ArrayList<>();
    /** Set when the user removes the item in the review screen. */
    public boolean removed;

    /** Scale all nutrients to a new gram weight. */
    public void setWeight(double newWeightG) {
        if (newWeightG <= 0 || weightG <= 0) throw new IllegalArgumentException("weight must be > 0");
        double f = newWeightG / weightG;
        kcal *= f;
        protein *= f;
        carbs *= f;
        fat *= f;
        if (fibre != null) fibre *= f;
        if (sugar != null) sugar *= f;
        if (sodiumMg != null) sodiumMg *= f;
        if (kcalMin != null) kcalMin *= f;
        if (kcalMax != null) kcalMax *= f;
        if (quantity > 0) quantity *= f;
        weightG = newWeightG;
    }

    public double kcal100() {
        return NutritionMath.per100(kcal, weightG);
    }

    public double protein100() {
        return NutritionMath.per100(protein, weightG);
    }

    public double carbs100() {
        return NutritionMath.per100(carbs, weightG);
    }

    public double fat100() {
        return NutritionMath.per100(fat, weightG);
    }

    @Nullable
    public Double fibre100() {
        return NutritionMath.per100(fibre, weightG);
    }

    @Nullable
    public Double sugar100() {
        return NutritionMath.per100(sugar, weightG);
    }

    @Nullable
    public Double sodium100() {
        return NutritionMath.per100(sodiumMg, weightG);
    }

    public boolean lowConfidence() {
        return confidence < AiResultParser.LOW_CONFIDENCE;
    }
}
