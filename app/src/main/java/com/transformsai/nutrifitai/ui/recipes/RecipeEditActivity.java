package com.transformsai.nutrifitai.ui.recipes;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.RecipeEntity;
import com.transformsai.nutrifitai.data.db.entity.RecipeIngredientEntity;
import com.transformsai.nutrifitai.data.repo.RecipeRepository;
import com.transformsai.nutrifitai.databinding.ActivityRecipeEditBinding;
import com.transformsai.nutrifitai.databinding.ItemDrinkBinding;
import com.transformsai.nutrifitai.domain.Nutrients;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.food.FoodSearchActivity;
import com.transformsai.nutrifitai.util.Units;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

/** Recipe editor: ingredients picked from the food database; nutrition always computed. */
public class RecipeEditActivity extends BaseActivity {
    private static final String EXTRA_ID = "id";

    public static Intent edit(Context c, String id) {
        return new Intent(c, RecipeEditActivity.class).putExtra(EXTRA_ID, id);
    }

    private ActivityRecipeEditBinding b;
    private RecipeEntity recipe = new RecipeEntity();
    private final List<RecipeIngredientEntity> ingredients = new ArrayList<>();

    private final ActivityResultLauncher<Intent> pickFood = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), res -> {
                if (res.getResultCode() != Activity.RESULT_OK || res.getData() == null) return;
                String id = res.getData().getStringExtra(FoodSearchActivity.RESULT_FOOD_ID);
                graph().foods.load(id, r -> {
                    if (r.value != null) askGrams(r.value);
                });
            });

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityRecipeEditBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, true);
        b.bar.title.setText(R.string.new_recipe);
        b.bar.back.setOnClickListener(v -> finish());
        b.servings.setText("1");
        String id = getIntent().getStringExtra(EXTRA_ID);
        if (id != null) {
            graph().recipes.load(id, r -> {
                if (r.value == null || r.value.recipe == null) return;
                recipe = r.value.recipe;
                ingredients.clear();
                ingredients.addAll(r.value.ingredients);
                b.bar.title.setText(recipe.name);
                b.name.setText(recipe.name);
                b.servings.setText(Units.num(recipe.servings));
                b.delete.setVisibility(android.view.View.VISIBLE);
                render();
            });
        }
        b.addIngredient.setOnClickListener(v -> pickFood.launch(FoodSearchActivity.pick(this)));
        b.servings.setOnFocusChangeListener((v, f) -> render());
        Ui.onClick(b.save, v -> save());
        b.delete.setOnClickListener(v -> new MaterialAlertDialogBuilder(this).setMessage(R.string.delete_recipe_q)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    graph().recipes.delete(recipe.id);
                    finish();
                }).setNegativeButton(R.string.cancel, null).show());
        render();
    }

    private void askGrams(FoodEntity f) {
        TextInputLayout til = new TextInputLayout(this);
        TextInputEditText et = new TextInputEditText(til.getContext());
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setText(Units.num(f.servingGrams));
        til.setHint(getString(R.string.ingredient_grams, f.name));
        til.addView(et);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(til);
        new MaterialAlertDialogBuilder(this).setTitle(f.name).setView(box)
                .setPositiveButton(R.string.add, (d, w) -> {
                    double g = Ui.parse(et.getText());
                    if (Double.isNaN(g) || g <= 0 || g > 5000) return;
                    ingredients.add(RecipeRepository.ingredientFrom(f, g));
                    render();
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private double servings() {
        double v = Ui.parse(b.servings.getText());
        return Double.isNaN(v) || v <= 0 ? 1 : v;
    }

    private void render() {
        b.ingredients.removeAllViews();
        for (RecipeIngredientEntity i : new ArrayList<>(ingredients)) {
            ItemDrinkBinding row = ItemDrinkBinding.inflate(getLayoutInflater(), b.ingredients, false);
            row.amount.setText(i.name);
            row.time.setText(Units.grams(i.grams) + " · " + Math.round(i.kcal100 * i.grams / 100) + " kcal");
            row.more.setImageResource(R.drawable.ic_close);
            row.more.setContentDescription(getString(R.string.remove_item));
            row.more.setOnClickListener(v -> {
                ingredients.remove(i);
                render();
            });
            b.ingredients.addView(row.getRoot());
        }
        Nutrients t = RecipeRepository.total(ingredients);
        double s = servings();
        Nutrients per = new Nutrients(t.kcal / s, t.protein / s, t.carbs / s, t.fat / s, t.fibre == null ? null : t.fibre / s);
        b.perServing.setText(Ui.kcal(per.kcal) + " kcal");
        b.perServingMacros.setText(Ui.macroChips(this, per));
    }

    private void save() {
        String name = b.name.getText() == null ? "" : b.name.getText().toString().trim();
        if (name.length() < 2) {
            b.nameLayout.setError(getString(R.string.cf_err_required));
            return;
        }
        double s = Ui.parse(b.servings.getText());
        if (Double.isNaN(s) || s <= 0 || s > 50) {
            b.servingsLayout.setError(getString(R.string.cf_err_range));
            return;
        }
        if (ingredients.isEmpty()) {
            Ui.snack(b.getRoot(), getString(R.string.recipe_need_ing));
            return;
        }
        recipe.name = name;
        recipe.servings = s;
        b.save.setEnabled(false);
        graph().recipes.save(recipe, new ArrayList<>(ingredients), r -> finish());
    }
}
