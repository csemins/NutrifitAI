package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Ingredient with a nutrition snapshot per 100 g taken when it was added. */
@Entity(tableName = "recipe_ingredients",
        foreignKeys = @ForeignKey(entity = RecipeEntity.class, parentColumns = "id", childColumns = "recipeId",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index("recipeId")})
public class RecipeIngredientEntity {
    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String recipeId = "";
    public String foodId;
    public String name;
    public double grams;
    public double kcal100;
    public double protein100;
    public double carbs100;
    public double fat100;
    @Nullable
    public Double fibre100;
    public int position;
}
