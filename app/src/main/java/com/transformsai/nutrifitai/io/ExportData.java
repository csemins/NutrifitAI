package com.transformsai.nutrifitai.io;

import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.entity.WaterEntity;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything a NutriFit AI export contains. Also the result of parsing an import. */
public class ExportData {
    public int formatVersion = XmlCodec.FORMAT_VERSION;
    public String exportedAt;
    public final List<MealEntryEntity> entries = new ArrayList<>();
    public final Map<String, List<MealItemEntity>> items = new LinkedHashMap<>();
    public final List<WaterEntity> water = new ArrayList<>();
    public final List<WeightEntity> weights = new ArrayList<>();
    public final List<GoalEntity> goals = new ArrayList<>();
    public final List<FoodEntity> foods = new ArrayList<>();
    /** Validation problems found while importing (line: message). */
    public final List<String> errors = new ArrayList<>();

    public int itemCount() {
        int n = 0;
        for (List<MealItemEntity> l : items.values()) n += l.size();
        return n;
    }

    public int recordCount() {
        return entries.size() + water.size() + weights.size() + goals.size() + foods.size();
    }
}
