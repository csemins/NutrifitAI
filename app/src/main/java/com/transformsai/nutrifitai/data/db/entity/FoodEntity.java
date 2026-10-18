package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.transformsai.nutrifitai.data.db.Syncable;

/**
 * A food definition with values per 100 g (or 100 ml for drinks, treated as grams).
 * ownerUid == null means the bundled starter catalog (read-only). Unknown nutrients are null.
 */
@Entity(tableName = "foods", indices = {@Index("ownerUid"), @Index("name"), @Index("category")})
public class FoodEntity implements Syncable {
    public static final String PROV_CURATED = "curated";
    /** From a published dataset (USDA FoodData Central SR Legacy); sourceRef holds the FDC id. */
    public static final String PROV_SOURCED = "sourced";
    public static final String PROV_USER = "user";
    public static final String PROV_AI = "ai_estimate";
    public static final String PROV_RECIPE = "recipe";
    public static final String PROV_IMPORTED = "imported";

    @PrimaryKey
    @NonNull
    public String id = "";
    @Nullable
    public String ownerUid;
    @NonNull
    public String name = "";
    public String nameBn;
    /** Lower-case search aliases and transliterations separated by '|'. */
    public String aliases;
    public String category;
    public double kcal100;
    public double protein100;
    public double carbs100;
    public double fat100;
    @Nullable
    public Double fibre100;
    @Nullable
    public Double sugar100;
    @Nullable
    public Double sodiumMg100;
    /** JSON object of micronutrients per 100 g, e.g. {"vitamin_c_mg":2.1}. Null when unknown. */
    @Nullable
    public String microsJson;
    public String provenance = PROV_CURATED;
    public String sourceRef;
    public String servingLabel;
    public double servingGrams = 100;
    public String emoji;
    public boolean bangladeshi;
    public long updatedAt;
    public boolean deleted;

    @Override
    public String syncId() {
        return id;
    }

    @Override
    public long syncUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean syncDeleted() {
        return deleted;
    }
}
