package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One food inside a meal entry. Nutrition is a snapshot per 100 g so later edits to the
 * source food never rewrite history. Portion = servings x servingGrams.
 */
@Entity(tableName = "meal_items",
        foreignKeys = @ForeignKey(entity = MealEntryEntity.class, parentColumns = "id", childColumns = "entryId",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index("entryId")})
public class MealItemEntity {
    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String entryId = "";
    public String foodId;
    @NonNull
    public String name = "";
    public String nameBn;
    public double servings = 1;
    /** e.g. "1 bowl", "g". */
    public String servingLabel;
    public double servingGrams = 100;
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
    public String provenance;
    @Nullable
    public Double confidence;
    public String emoji;
    public int position;
    public long updatedAt;
    public boolean deleted;

    public double grams() {
        return servings * servingGrams;
    }
}
