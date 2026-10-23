package com.transformsai.nutrifitai.domain;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;

import java.util.List;

/**
 * Single source of truth for nutrition arithmetic. All stored values are per 100 g; a portion is
 * grams = servings x servingGrams; amount = per100 x grams / 100.
 */
public final class NutritionMath {
    public static final double KCAL_PER_G_PROTEIN = 4;
    public static final double KCAL_PER_G_CARB = 4;
    public static final double KCAL_PER_G_FAT = 9;

    private NutritionMath() {
    }

    public static double portion(double per100, double grams) {
        return per100 * grams / 100.0;
    }

    @Nullable
    public static Double portion(@Nullable Double per100, double grams) {
        return per100 == null ? null : per100 * grams / 100.0;
    }

    /** Inverse of {@link #portion}: per-100 g value from an amount for a known gram weight. */
    public static double per100(double amount, double grams) {
        if (grams <= 0) throw new IllegalArgumentException("grams must be > 0");
        return amount * 100.0 / grams;
    }

    @Nullable
    public static Double per100(@Nullable Double amount, double grams) {
        return amount == null ? null : per100(amount.doubleValue(), grams);
    }

    public static double grams(double servings, double servingGrams) {
        return servings * servingGrams;
    }

    /** Convert a gram amount to servings of a given size. */
    public static double servingsFor(double grams, double servingGrams) {
        if (servingGrams <= 0) throw new IllegalArgumentException("servingGrams must be > 0");
        return grams / servingGrams;
    }

    public static Nutrients forItem(MealItemEntity i) {
        double g = i.grams();
        Nutrients n = new Nutrients(portion(i.kcal100, g), portion(i.protein100, g), portion(i.carbs100, g),
                portion(i.fat100, g), portion(i.fibre100, g));
        n.sugar = portion(i.sugar100, g);
        n.sodiumMg = portion(i.sodiumMg100, g);
        return n;
    }

    public static Nutrients forFood(FoodEntity f, double grams) {
        Nutrients n = new Nutrients(portion(f.kcal100, grams), portion(f.protein100, grams),
                portion(f.carbs100, grams), portion(f.fat100, grams), portion(f.fibre100, grams));
        n.sugar = portion(f.sugar100, grams);
        n.sodiumMg = portion(f.sodiumMg100, grams);
        return n;
    }

    /** Sum of items. Fibre is summed over items where it is known; null if none known. */
    public static Nutrients sum(List<Nutrients> list) {
        Nutrients t = new Nutrients();
        boolean anyFibre = false;
        double fibre = 0;
        for (Nutrients n : list) {
            t.kcal += n.kcal;
            t.protein += n.protein;
            t.carbs += n.carbs;
            t.fat += n.fat;
            if (n.fibre != null) {
                anyFibre = true;
                fibre += n.fibre;
            }
        }
        t.fibre = anyFibre ? fibre : null;
        return t;
    }

    /** Energy share of each macro in percent (protein, carbs, fat). Zeros if no energy. */
    public static int[] macroEnergySplit(double protein, double carbs, double fat) {
        double p = protein * KCAL_PER_G_PROTEIN;
        double c = carbs * KCAL_PER_G_CARB;
        double f = fat * KCAL_PER_G_FAT;
        double total = p + c + f;
        if (total <= 0) return new int[]{0, 0, 0};
        int pp = (int) Math.round(p * 100 / total);
        int cp = (int) Math.round(c * 100 / total);
        int fp = 100 - pp - cp;
        return new int[]{pp, cp, Math.max(fp, 0)};
    }

    /** kcal from macros, used to flag inconsistent estimates. */
    public static double atwaterKcal(double protein, double carbs, double fat) {
        return protein * KCAL_PER_G_PROTEIN + carbs * KCAL_PER_G_CARB + fat * KCAL_PER_G_FAT;
    }

    /** Grams of protein per 100 kcal: a transparent, factual density figure (no "health score"). */
    public static double proteinPer100Kcal(double protein, double kcal) {
        if (kcal <= 0) return 0;
        return protein * 100.0 / kcal;
    }

    public static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
