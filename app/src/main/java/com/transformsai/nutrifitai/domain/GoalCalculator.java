package com.transformsai.nutrifitai.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Estimates daily targets with the Mifflin-St Jeor equation (Mifflin et al., 1990) and standard
 * activity multipliers. Results are estimates for general wellness, not medical prescriptions.
 *
 * Safety rules: deficit is at most 20% of TDEE and never more than 500 kcal; the target never goes
 * below the estimated BMR or 1200 kcal (1500 for male); surplus is at most 10% / 300 kcal.
 */
public final class GoalCalculator {

    public static final class Input {
        public String sex = "unspecified";
        public int age;
        public double heightCm;
        public double weightKg;
        public double targetWeightKg;
        public String activity = "light";
        public String goal = "maintain";
    }

    public static final class Plan {
        public int bmr;
        public int tdee;
        public int kcal;
        public int proteinG;
        public int carbsG;
        public int fatG;
        public int fibreG;
        public int waterMl;
        public final List<String> notes = new ArrayList<>();
    }

    public static final int MIN_AGE = 16;
    public static final int MAX_AGE = 100;

    private GoalCalculator() {
    }

    /** @return list of validation problems, empty when the input is usable. */
    public static List<String> validate(Input in) {
        List<String> errors = new ArrayList<>();
        if (in.age < MIN_AGE || in.age > MAX_AGE) errors.add("age");
        if (in.heightCm < 120 || in.heightCm > 230) errors.add("height");
        if (in.weightKg < 30 || in.weightKg > 300) errors.add("weight");
        if (in.targetWeightKg < 30 || in.targetWeightKg > 300) errors.add("target_weight");
        if (activityFactor(in.activity) == 0) errors.add("activity");
        if (!"lose".equals(in.goal) && !"maintain".equals(in.goal) && !"gain".equals(in.goal)) errors.add("goal");
        return errors;
    }

    public static double activityFactor(String activity) {
        if (activity == null) return 0;
        switch (activity) {
            case "sedentary": return 1.2;
            case "light": return 1.375;
            case "moderate": return 1.55;
            case "active": return 1.725;
            case "very_active": return 1.9;
            default: return 0;
        }
    }

    public static double bmr(String sex, double weightKg, double heightCm, int age) {
        double base = 10 * weightKg + 6.25 * heightCm - 5 * age;
        if ("male".equals(sex)) return base + 5;
        if ("female".equals(sex)) return base - 161;
        return base - 78; // midpoint of the two published constants
    }

    public static Plan calculate(Input in) {
        List<String> errors = validate(in);
        if (!errors.isEmpty()) throw new IllegalArgumentException("Invalid input: " + errors);
        Plan p = new Plan();
        double bmr = bmr(in.sex, in.weightKg, in.heightCm, in.age);
        double tdee = bmr * activityFactor(in.activity);
        double kcal = tdee;
        int floor = "male".equals(in.sex) ? 1500 : 1200;
        if ("lose".equals(in.goal)) {
            double deficit = Math.min(500, tdee * 0.20);
            kcal = tdee - deficit;
            double minimum = Math.max(floor, bmr);
            if (kcal < minimum) {
                kcal = minimum;
                p.notes.add("floor_applied");
            }
        } else if ("gain".equals(in.goal)) {
            kcal = tdee + Math.min(300, tdee * 0.10);
        }
        kcal = Math.max(kcal, floor);
        p.bmr = (int) Math.round(bmr);
        p.tdee = (int) Math.round(tdee);
        p.kcal = roundTo(kcal, 10);

        double proteinPerKg = "maintain".equals(in.goal) ? 1.2 : 1.6;
        double protein = proteinPerKg * in.weightKg;
        protein = Math.min(protein, p.kcal * 0.30 / 4.0); // keep protein <= 30% energy
        protein = Math.max(protein, 50);
        double fat = p.kcal * 0.30 / 9.0;
        double carbs = (p.kcal - protein * 4 - fat * 9) / 4.0;
        p.proteinG = (int) Math.round(protein);
        p.fatG = (int) Math.round(fat);
        p.carbsG = (int) Math.max(Math.round(carbs), 0);
        p.fibreG = (int) Math.max(21, Math.min(38, Math.round(14.0 * p.kcal / 1000.0)));
        p.waterMl = (int) Math.max(1500, Math.min(4000, roundTo(in.weightKg * 33, 100)));
        return p;
    }

    public static int roundTo(double v, int step) {
        return (int) (Math.round(v / step) * step);
    }

    /** Sanity limits for manually entered targets. */
    public static boolean manualTargetsValid(int kcal, int p, int c, int f, int fibre, int water) {
        return kcal >= 1000 && kcal <= 6000 && p >= 0 && p <= 400 && c >= 0 && c <= 900 && f >= 0 && f <= 300
                && fibre >= 0 && fibre <= 100 && water >= 500 && water <= 6000;
    }
}
