package com.transformsai.nutrifitai.io;

import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.entity.WaterEntity;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;
import com.transformsai.nutrifitai.domain.Nutrients;
import com.transformsai.nutrifitai.domain.NutritionMath;

import java.util.List;
import java.util.Locale;

/** RFC 4180 CSV for spreadsheets. One row per record; a "type" column distinguishes sections. */
public final class CsvWriter {
    private CsvWriter() {
    }

    public static String write(ExportData d) {
        StringBuilder b = new StringBuilder();
        b.append("type,date,meal,status,name,grams,kcal,protein_g,carbs_g,fat_g,fibre_g,provenance,water_ml,weight_kg\n");
        for (MealEntryEntity e : d.entries) {
            List<MealItemEntity> items = d.items.get(e.id);
            if (items == null) continue;
            for (MealItemEntity i : items) {
                Nutrients n = NutritionMath.forItem(i);
                row(b, "meal", e.date, e.mealType, e.status, i.name, f(i.grams()), f(n.kcal), f(n.protein), f(n.carbs),
                        f(n.fat), n.fibre == null ? "" : f(n.fibre), i.provenance, "", "");
            }
        }
        for (WaterEntity w : d.water) row(b, "water", w.date, "", "", "", "", "", "", "", "", "", "", String.valueOf(w.ml), "");
        for (WeightEntity w : d.weights) row(b, "weight", w.date, "", "", "", "", "", "", "", "", "", "", "", f(w.kg));
        return b.toString();
    }

    private static String f(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static void row(StringBuilder b, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) b.append(',');
            b.append(cell(cells[i]));
        }
        b.append('\n');
    }

    public static String cell(String s) {
        if (s == null) return "";
        // Neutralise spreadsheet formula injection.
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
