package com.transformsai.nutrifitai.ui.main;

import com.transformsai.nutrifitai.data.db.model.ItemRow;

/** Flattened diary list row: section header, meal item, or empty placeholder. */
public final class DiaryRow {
    public static final int HEADER = 0, ITEM = 1, EMPTY = 2;
    public final int type;
    public final String mealType;
    public final ItemRow item;
    public final double kcal;
    public final double plannedKcal;

    private DiaryRow(int type, String mealType, ItemRow item, double kcal, double plannedKcal) {
        this.type = type;
        this.mealType = mealType;
        this.item = item;
        this.kcal = kcal;
        this.plannedKcal = plannedKcal;
    }

    public static DiaryRow header(String meal, double kcal, double planned) {
        return new DiaryRow(HEADER, meal, null, kcal, planned);
    }

    public static DiaryRow item(ItemRow r) {
        return new DiaryRow(ITEM, r.mealType, r, 0, 0);
    }

    public static DiaryRow empty(String meal) {
        return new DiaryRow(EMPTY, meal, null, 0, 0);
    }

    public String key() {
        return type == ITEM ? "i" + item.item.id : type + mealType;
    }
}
