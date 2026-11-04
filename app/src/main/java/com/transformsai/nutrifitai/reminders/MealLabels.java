package com.transformsai.nutrifitai.reminders;

import android.content.Context;

import com.transformsai.nutrifitai.R;

public final class MealLabels {
    public static final String[] TYPES = {"breakfast", "lunch", "dinner", "snack"};

    private MealLabels() {
    }

    public static String label(Context c, String type) {
        if (type == null) return c.getString(R.string.meal_snack);
        switch (type) {
            case "breakfast": return c.getString(R.string.meal_breakfast);
            case "lunch": return c.getString(R.string.meal_lunch);
            case "dinner": return c.getString(R.string.meal_dinner);
            default: return c.getString(R.string.meal_snack);
        }
    }

    public static int color(String type) {
        if (type == null) return R.color.nl_purple;
        switch (type) {
            case "breakfast": return R.color.nl_carbs;
            case "lunch": return R.color.nl_protein;
            case "dinner": return R.color.nl_fat;
            default: return R.color.nl_fibre;
        }
    }

    /** Sensible default meal for the current hour. */
    public static String forHour(int hour) {
        if (hour < 11) return "breakfast";
        if (hour < 16) return "lunch";
        if (hour < 18) return "snack";
        return "dinner";
    }
}
