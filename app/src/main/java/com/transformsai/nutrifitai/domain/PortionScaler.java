package com.transformsai.nutrifitai.domain;

/**
 * Keeps an edited food's nutrients in step with its portion. Each nutrient has a density (amount per gram):
 * changing the grams rescales every nutrient from its density; typing a nutrient value makes that value the new
 * baseline (its density is recalculated at the current grams), so later portion changes scale from what the user
 * entered rather than from the original estimate.
 */
public final class PortionScaler {
    public static final int KCAL = 0, PROTEIN = 1, CARBS = 2, FAT = 3, FIBRE = 4, COUNT = 5;

    private double grams;
    private final double[] density = new double[COUNT];
    private final boolean[] known = new boolean[COUNT];

    /** {@code values} may contain NaN for unknown nutrients (they stay unknown until typed). */
    public PortionScaler(double grams, double[] values) {
        this.grams = grams;
        for (int i = 0; i < COUNT; i++) {
            double v = i < values.length ? values[i] : Double.NaN;
            known[i] = !Double.isNaN(v) && grams > 0;
            density[i] = known[i] ? v / grams : 0;
        }
    }

    public double grams() {
        return grams;
    }

    /** The portion changed: returns every nutrient for the new grams (NaN where unknown). */
    public double[] setGrams(double newGrams) {
        if (!(newGrams > 0) || Double.isInfinite(newGrams)) return values();
        grams = newGrams;
        return values();
    }

    /** The user typed a nutrient: it becomes the baseline for that nutrient. */
    public void setValue(int nutrient, double value) {
        if (Double.isNaN(value) || value < 0 || !(grams > 0)) {
            known[nutrient] = false;
            return;
        }
        known[nutrient] = true;
        density[nutrient] = value / grams;
    }

    public double[] values() {
        double[] out = new double[COUNT];
        for (int i = 0; i < COUNT; i++) out[i] = known[i] ? density[i] * grams : Double.NaN;
        return out;
    }
}
