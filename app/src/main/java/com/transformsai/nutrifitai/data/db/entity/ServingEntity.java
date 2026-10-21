package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Additional named serving sizes for a food (e.g. "1 piece = 40 g"). */
@Entity(tableName = "servings",
        foreignKeys = @ForeignKey(entity = FoodEntity.class, parentColumns = "id", childColumns = "foodId",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index("foodId")})
public class ServingEntity {
    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String foodId = "";
    public String label;
    public double grams;
}
