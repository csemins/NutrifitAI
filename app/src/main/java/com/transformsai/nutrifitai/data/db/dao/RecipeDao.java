package com.transformsai.nutrifitai.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Upsert;

import com.transformsai.nutrifitai.data.db.entity.RecipeEntity;
import com.transformsai.nutrifitai.data.db.entity.RecipeIngredientEntity;

import java.util.List;

@Dao
public interface RecipeDao {
    @Query("SELECT * FROM recipes WHERE uid = :uid AND deleted = 0 ORDER BY updatedAt DESC")
    LiveData<List<RecipeEntity>> observeAll(String uid);

    @Query("SELECT * FROM recipes WHERE id = :id")
    RecipeEntity get(String id);

    @Query("SELECT * FROM recipe_ingredients WHERE recipeId = :recipeId ORDER BY position")
    List<RecipeIngredientEntity> ingredients(String recipeId);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsert(RecipeEntity r);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsertIngredients(List<RecipeIngredientEntity> list);

    @Query("DELETE FROM recipe_ingredients WHERE recipeId = :recipeId")
    void deleteIngredients(String recipeId);

    @Query("DELETE FROM recipes WHERE uid = :uid")
    void wipe(String uid);
}
