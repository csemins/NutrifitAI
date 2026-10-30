package com.transformsai.nutrifitai.data.repo;

import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.entity.RecipeEntity;
import com.transformsai.nutrifitai.data.db.entity.RecipeIngredientEntity;
import com.transformsai.nutrifitai.domain.Nutrients;
import com.transformsai.nutrifitai.domain.NutritionMath;
import com.transformsai.nutrifitai.sync.Collections;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Ids;

import java.util.ArrayList;
import java.util.List;

/** Recipes: nutrition is always calculated from ingredient snapshots. */
public class RecipeRepository {
    private final AppGraph g;

    public RecipeRepository(AppGraph g) {
        this.g = g;
    }

    public LiveData<List<RecipeEntity>> observeAll(String uid) {
        return g.db.recipes().observeAll(uid);
    }

    public static final class Full {
        public RecipeEntity recipe;
        public List<RecipeIngredientEntity> ingredients = new ArrayList<>();
    }

    public void load(String id, Result.Callback<Full> cb) {
        g.runIo(() -> {
            Full f = new Full();
            f.recipe = g.db.recipes().get(id);
            if (f.recipe != null) f.ingredients = g.db.recipes().ingredients(id);
            g.postResult(cb, Result.ok(f));
        });
    }

    public static RecipeIngredientEntity ingredientFrom(FoodEntity f, double grams) {
        RecipeIngredientEntity i = new RecipeIngredientEntity();
        i.id = Ids.newId();
        i.foodId = f.id;
        i.name = f.name;
        i.grams = grams;
        i.kcal100 = f.kcal100;
        i.protein100 = f.protein100;
        i.carbs100 = f.carbs100;
        i.fat100 = f.fat100;
        i.fibre100 = f.fibre100;
        return i;
    }

    public static Nutrients total(List<RecipeIngredientEntity> list) {
        List<Nutrients> parts = new ArrayList<>();
        for (RecipeIngredientEntity i : list) {
            parts.add(new Nutrients(NutritionMath.portion(i.kcal100, i.grams), NutritionMath.portion(i.protein100, i.grams),
                    NutritionMath.portion(i.carbs100, i.grams), NutritionMath.portion(i.fat100, i.grams),
                    NutritionMath.portion(i.fibre100, i.grams)));
        }
        return NutritionMath.sum(parts);
    }

    public static double totalGrams(List<RecipeIngredientEntity> list) {
        double t = 0;
        for (RecipeIngredientEntity i : list) t += i.grams;
        return t;
    }

    public void save(RecipeEntity r, List<RecipeIngredientEntity> ingredients, Result.Callback<String> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            long now = DateUtil.nowMillis();
            if (r.id.isEmpty()) {
                r.id = Ids.newId();
                r.createdAt = now;
            }
            r.uid = uid;
            r.updatedAt = now;
            for (int k = 0; k < ingredients.size(); k++) {
                ingredients.get(k).recipeId = r.id;
                ingredients.get(k).position = k;
            }
            g.db.runInTransaction(() -> {
                g.db.recipes().upsert(r);
                g.db.recipes().deleteIngredients(r.id);
                g.db.recipes().upsertIngredients(ingredients);
                g.outbox.enqueue(uid, Collections.RECIPES, r.id);
            });
            g.outbox.kick();
            g.postResult(cb, Result.ok(r.id));
        });
    }

    public void delete(String id) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            RecipeEntity r = g.db.recipes().get(id);
            if (r == null) return;
            r.deleted = true;
            r.updatedAt = DateUtil.nowMillis();
            g.db.runInTransaction(() -> {
                g.db.recipes().upsert(r);
                g.outbox.enqueue(uid, Collections.RECIPES, id);
            });
            g.outbox.kick();
        });
    }

    /** One meal item representing `servings` of the recipe, with per-100 g derived from ingredients. */
    public static MealItemEntity toMealItem(RecipeEntity r, List<RecipeIngredientEntity> ingredients, double servings) {
        double grams = totalGrams(ingredients);
        Nutrients t = total(ingredients);
        MealItemEntity i = new MealItemEntity();
        i.id = Ids.newId();
        i.foodId = null;
        i.name = r.name;
        i.servings = servings;
        i.servingLabel = "serving";
        i.servingGrams = grams / Math.max(r.servings, 0.01);
        i.kcal100 = NutritionMath.per100(t.kcal, grams);
        i.protein100 = NutritionMath.per100(t.protein, grams);
        i.carbs100 = NutritionMath.per100(t.carbs, grams);
        i.fat100 = NutritionMath.per100(t.fat, grams);
        i.fibre100 = NutritionMath.per100(t.fibre, grams);
        i.provenance = FoodEntity.PROV_RECIPE;
        i.emoji = "🍲";
        return i;
    }
}
