package com.transformsai.nutrifitai.ui.food;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.entity.ServingEntity;
import com.transformsai.nutrifitai.data.db.model.ItemRow;
import com.transformsai.nutrifitai.data.repo.DiaryRepository;
import com.transformsai.nutrifitai.databinding.ActivityFoodDetailBinding;
import com.transformsai.nutrifitai.databinding.ItemMicroBinding;
import com.transformsai.nutrifitai.databinding.ViewGoalRowBinding;
import com.transformsai.nutrifitai.databinding.ViewInfoTileBinding;
import com.transformsai.nutrifitai.domain.Nutrients;
import com.transformsai.nutrifitai.domain.NutritionMath;
import com.transformsai.nutrifitai.reminders.MealLabels;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Units;
import com.bumptech.glide.Glide;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Food detail with editable portion (servings or grams). Two modes:
 *  - FOOD: add a catalog/custom food to a meal (explicit intent from search).
 *  - ITEM: edit or delete an existing diary item (values come from the item's snapshot).
 * Unknown nutrients are shown as "Unavailable", never as zero.
 */
public class FoodDetailActivity extends BaseActivity {
    private static final String EXTRA_FOOD = "food_id";
    private static final String EXTRA_ITEM = "item_id";

    public static Intent forFood(Context c, String foodId, String date, String meal, boolean planned) {
        return new Intent(c, FoodDetailActivity.class).putExtra(EXTRA_FOOD, foodId)
                .putExtra(FoodSearchActivity.EXTRA_DATE, date).putExtra(FoodSearchActivity.EXTRA_MEAL, meal)
                .putExtra(FoodSearchActivity.EXTRA_PLANNED, planned);
    }

    public static Intent forItem(Context c, String itemId) {
        return new Intent(c, FoodDetailActivity.class).putExtra(EXTRA_ITEM, itemId);
    }

    /** A selectable unit: label and grams per unit ("g" = 1). */
    static final class Unit {
        final String label;
        final double grams;

        Unit(String l, double g) {
            label = l;
            grams = g;
        }
    }

    private ActivityFoodDetailBinding b;
    private FoodEntity food;          // FOOD mode, or source food in ITEM mode (may be null for AI items)
    private ItemRow row;              // ITEM mode
    private final List<Unit> units = new ArrayList<>();
    private Unit unit;
    private double amount = 1;
    private String date;
    private String meal;
    private GoalEntity goal;
    private boolean bindingAmount;

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityFoodDetailBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.topMargin(b.back);
        InsetsUtil.topMargin(b.fav);
        InsetsUtil.topMargin(b.editFood);
        InsetsUtil.padding(b.bottomBar, false, true);
        b.back.setOnClickListener(v -> finish());
        date = getIntent().getStringExtra(FoodSearchActivity.EXTRA_DATE);
        if (date == null) date = DateUtil.today();
        meal = getIntent().getStringExtra(FoodSearchActivity.EXTRA_MEAL);
        if (meal == null) meal = MealLabels.forHour(java.time.LocalTime.now().getHour());
        b.planned.setChecked(getIntent().getBooleanExtra(FoodSearchActivity.EXTRA_PLANNED, false));
        if (s != null) {
            amount = s.getDouble("amount", 1);
            date = s.getString("date", date);
            meal = s.getString("meal", meal);
        }
        b.minus.setOnClickListener(v -> step(-1));
        b.plus.setOnClickListener(v -> step(1));
        b.unit.setOnClickListener(v -> chooseUnit());
        b.mealChip.setOnClickListener(v -> chooseMeal());
        b.dateChip.setOnClickListener(v -> chooseDate());
        b.amount.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int a, int c, int d) {
            }

            @Override
            public void onTextChanged(CharSequence x, int a, int c, int d) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (bindingAmount) return;
                double v = Ui.parse(e);
                if (!Double.isNaN(v) && v > 0 && v * (unit == null ? 1 : unit.grams) <= 5000) {
                    amount = v;
                    renderNutrition();
                }
            }
        });
        graph().profile.activeGoal(DateUtil.today(), r -> {
            goal = r.value;
            if (food != null || row != null) renderNutrition();
        });
        String itemId = getIntent().getStringExtra(EXTRA_ITEM);
        if (itemId != null) loadItem(itemId, s == null);
        else loadFood(getIntent().getStringExtra(EXTRA_FOOD));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putDouble("amount", amount);
        out.putString("date", date);
        out.putString("meal", meal);
    }

    private void loadFood(String id) {
        graph().foods.load(id, r -> {
            if (r.value == null) {
                finish();
                return;
            }
            food = r.value;
            graph().foods.servings(food.id, sr -> {
                units.clear();
                units.add(new Unit(food.servingLabel == null ? "100 g" : food.servingLabel, food.servingGrams));
                if (sr.value != null) for (ServingEntity se : sr.value) units.add(new Unit(se.label, se.grams));
                units.add(new Unit("g", 1));
                unit = units.get(0);
                setupFoodMode();
                bindAll();
            });
        });
    }

    private void loadItem(String id, boolean fresh) {
        graph().diary.row(id, r -> {
            if (r.value == null) {
                finish();
                return;
            }
            row = r.value;
            date = row.date;
            meal = row.mealType;
            if (fresh) amount = row.item.servings;
            units.clear();
            units.add(new Unit(row.item.servingLabel, row.item.servingGrams));
            Runnable done = () -> {
                if (!"g".equals(row.item.servingLabel)) units.add(new Unit("g", 1));
                unit = units.get(0);
                setupItemMode();
                bindAll();
            };
            if (row.item.foodId != null) {
                graph().foods.load(row.item.foodId, fr -> {
                    food = fr.value;
                    if (food == null) {
                        done.run();
                        return;
                    }
                    graph().foods.servings(food.id, sr -> {
                        if (food.servingLabel != null && !food.servingLabel.equals(row.item.servingLabel))
                            units.add(new Unit(food.servingLabel, food.servingGrams));
                        if (sr.value != null) for (ServingEntity se : sr.value) {
                            if (!se.label.equals(row.item.servingLabel)) units.add(new Unit(se.label, se.grams));
                        }
                        done.run();
                    });
                });
            } else done.run();
        });
    }

    private void setupFoodMode() {
        graph().foods.observeFavorite(graph().session.requireUid(), food.id).observe(this, fav -> {
            boolean f = Boolean.TRUE.equals(fav);
            b.fav.setImageResource(f ? R.drawable.ic_heart : R.drawable.ic_heart_outline);
            b.fav.setImageTintList(ContextCompat.getColorStateList(this, f ? R.color.nl_protein : R.color.nl_text));
            b.fav.setContentDescription(getString(f ? R.string.unfavourite : R.string.favourite));
        });
        b.fav.setOnClickListener(v -> {
            Ui.haptic(v);
            graph().foods.toggleFavorite(food.id);
        });
        if (food.ownerUid != null) {
            b.editFood.setVisibility(View.VISIBLE);
            b.editFood.setOnClickListener(v -> startActivity(CustomFoodActivity.edit(this, food.id)));
        }
        b.delete.setVisibility(View.GONE);
        Ui.onClick(b.primary, v -> addToMeal());
        b.planned.setOnCheckedChangeListener((x, on) -> renderTarget());
    }

    private void setupItemMode() {
        b.fav.setVisibility(food == null ? View.GONE : View.VISIBLE);
        if (food != null) {
            b.fav.setOnClickListener(v -> graph().foods.toggleFavorite(food.id));
            graph().foods.observeFavorite(graph().session.requireUid(), food.id).observe(this, fav -> {
                boolean f = Boolean.TRUE.equals(fav);
                b.fav.setImageResource(f ? R.drawable.ic_heart : R.drawable.ic_heart_outline);
                b.fav.setImageTintList(ContextCompat.getColorStateList(this, f ? R.color.nl_protein : R.color.nl_text));
            });
        }
        b.targetRow.setVisibility(View.GONE);
        b.delete.setVisibility(View.VISIBLE);
        b.primary.setText(R.string.save_changes);
        Ui.onClick(b.primary, v -> saveItem());
        b.delete.setOnClickListener(v -> new MaterialAlertDialogBuilder(this).setTitle(R.string.delete)
                .setMessage(row.item.name).setPositiveButton(R.string.delete, (d, w) ->
                        graph().diary.deleteItem(row.item.id, r -> finish()))
                .setNegativeButton(R.string.cancel, null).show());
    }

    private void bindAll() {
        String name = row != null ? row.item.name : food.name;
        String bn = row != null ? row.item.nameBn : food.nameBn;
        b.name.setText(name);
        b.nameBn.setText(bn);
        b.nameBn.setVisibility(bn == null ? View.GONE : View.VISIBLE);
        String emoji = row != null ? row.item.emoji : food.emoji;
        String imagePath = row != null ? row.imageLocalPath : null;
        b.image.setRadius(0);
        if (imagePath != null && new File(imagePath).exists()) Glide.with(this).load(new File(imagePath)).centerCrop().into(b.image);
        else b.image.setFood(row != null ? row.item.foodId : food.id, name, emoji, food == null ? null : food.category);
        String prov = row != null ? row.item.provenance : food.provenance;
        b.source.setText(Ui.provenance(this, prov));
        String ref = food != null && row == null ? food.sourceRef : null;
        if (row != null && row.item.confidence != null) ref = getString(R.string.confidence_chip, Math.round(row.item.confidence * 100) + "%") + " · " + getString(R.string.ai_disclaimer);
        b.sourceRef.setText(ref == null ? "" : ref);
        b.sourceRef.setVisibility(row != null && row.item.confidence != null ? View.VISIBLE : View.GONE);
        bindAmount();
        renderTarget();
        renderNutrition();
    }

    private void bindAmount() {
        bindingAmount = true;
        b.amount.setText(Units.num(amount));
        bindingAmount = false;
        b.unit.setText("g".equals(unit.label) ? getString(R.string.grams) : unit.label + " (" + Units.grams(unit.grams) + ")");
    }

    private void step(int dir) {
        double st = "g".equals(unit.label) ? 10 : (amount < 1 || (amount == 1 && dir < 0)) ? 0.25 : 0.5;
        double min = "g".equals(unit.label) ? 1 : 0.25;
        double n = Math.max(min, Math.round((amount + dir * st) * 100) / 100.0);
        if (n * unit.grams > 5000) return;
        amount = n;
        bindAmount();
        renderNutrition();
    }

    private void chooseUnit() {
        if (units.size() < 2) return;
        String[] labels = new String[units.size()];
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            labels[i] = "g".equals(u.label) ? getString(R.string.grams) : u.label + " (" + Units.grams(u.grams) + ")";
        }
        new MaterialAlertDialogBuilder(this).setTitle(R.string.unit).setItems(labels, (d, w) -> {
            double grams = amount * unit.grams;
            unit = units.get(w);
            // Keep the same gram weight when switching units.
            amount = "g".equals(unit.label) ? Math.round(grams) : Math.max(0.25, Math.round(grams / unit.grams * 100) / 100.0);
            bindAmount();
            renderNutrition();
        }).show();
    }

    private void chooseMeal() {
        String[] labels = getResources().getStringArray(R.array.meal_types);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.choose_meal).setItems(labels, (d, w) -> {
            meal = MealLabels.TYPES[w];
            renderTarget();
        }).show();
    }

    private void chooseDate() {
        long sel = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        MaterialDatePicker<Long> p = MaterialDatePicker.Builder.datePicker().setSelection(sel).build();
        p.addOnPositiveButtonClickListener(ms -> {
            date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString();
            renderTarget();
        });
        p.show(getSupportFragmentManager(), "date");
    }

    private void renderTarget() {
        if (row != null) return;
        boolean future = DateUtil.isFuture(date);
        if (future) b.planned.setChecked(true);
        b.planned.setEnabled(!future);
        b.mealChip.setText(MealLabels.label(this, meal));
        b.dateChip.setText(date.equals(DateUtil.today()) ? getString(R.string.today) : DateUtil.monthDay(date));
        String m = MealLabels.label(this, meal);
        b.primary.setText(b.planned.isChecked() ? getString(R.string.add_to_plan, m) : getString(R.string.add_to_meal, m));
    }

    /** Per-100 g snapshot for the current mode. */
    private MealItemEntity basis() {
        if (row != null) return row.item;
        return DiaryRepository.itemFromFood(food, 1, unit.label, unit.grams);
    }

    private void renderNutrition() {
        if (unit == null) return;
        MealItemEntity basis = basis();
        double grams = amount * unit.grams;
        Nutrients n = new Nutrients(NutritionMath.portion(basis.kcal100, grams), NutritionMath.portion(basis.protein100, grams),
                NutritionMath.portion(basis.carbs100, grams), NutritionMath.portion(basis.fat100, grams),
                NutritionMath.portion(basis.fibre100, grams));
        n.sugar = NutritionMath.portion(basis.sugar100, grams);
        n.sodiumMg = NutritionMath.portion(basis.sodiumMg100, grams);
        b.kcal.setText(Ui.kcal(n.kcal));
        b.kcalBox.setContentDescription(Math.round(n.kcal) + " kcal total");
        String unitText = "g".equals(unit.label) ? Units.grams(grams) : Units.num(amount) + " × " + unit.label + " · " + Units.grams(grams);
        b.serving.setText(unitText);
        macroRow(b.rowP, R.string.protein, n.protein, goal == null ? 0 : goal.proteinG, R.color.nl_protein);
        macroRow(b.rowC, R.string.carbs, n.carbs, goal == null ? 0 : goal.carbsG, R.color.nl_carbs);
        macroRow(b.rowF, R.string.fat, n.fat, goal == null ? 0 : goal.fatG, R.color.nl_fat);
        if (n.fibre == null) {
            b.rowFib.label.setText(R.string.fibre);
            b.rowFib.value.setText(R.string.unavailable);
            b.rowFib.bar.setFraction(0);
        } else macroRow(b.rowFib, R.string.fibre, n.fibre, goal == null ? 0 : goal.fibreG, R.color.nl_fibre);
        tile(b.tileSugar, R.string.sugar, n.sugar == null ? null : Units.grams(n.sugar));
        tile(b.tileSodium, R.string.sodium, n.sodiumMg == null ? null : Math.round(n.sodiumMg) + " mg");
        tile(b.tileDensity, R.string.energy_density, Math.round(basis.kcal100) + " kcal");
        double density = NutritionMath.proteinPer100Kcal(n.protein, n.kcal);
        b.densityValue.setText(String.format(Locale.getDefault(), "%.1f g", density));
        b.density.setContentDescription(getString(R.string.p_density_help) + " " + b.densityValue.getText());
        renderMicros(grams);
    }

    private void macroRow(ViewGoalRowBinding r, int label, double g, int target, int color) {
        r.label.setText(label);
        r.value.setText(Units.grams(g));
        r.bar.setColor(ContextCompat.getColor(this, color));
        r.bar.setFraction(target > 0 ? (float) (g / target) : 0);
        r.getRoot().setContentDescription(getString(label) + " " + Units.grams(g)
                + (target > 0 ? ", " + Math.round(g * 100 / target) + "% of daily target" : ""));
    }

    private void tile(ViewInfoTileBinding t, int label, @Nullable String value) {
        t.label.setText(label);
        t.value.setText(value == null ? getString(R.string.unavailable) : value);
        t.value.setTextColor(ContextCompat.getColor(this, value == null ? R.color.nl_text_3 : R.color.nl_text));
        t.getRoot().setContentDescription(getString(label) + ": " + t.value.getText());
    }

    private static final String[][] DV = {
            {"vitamin_c_mg", "Vitamin C", "90", "mg"}, {"iron_mg", "Iron", "18", "mg"},
            {"calcium_mg", "Calcium", "1300", "mg"}, {"potassium_mg", "Potassium", "4700", "mg"}};

    private void renderMicros(double grams) {
        b.micros.removeAllViews();
        String json = food != null ? food.microsJson : null;
        if (json == null) {
            b.microsNote.setText(R.string.no_micros);
            return;
        }
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        int shown = 0;
        for (String[] d : DV) {
            JsonElement e = o.get(d[0]);
            if (e == null || e.isJsonNull()) continue;
            double amt = e.getAsDouble() * grams / 100.0;
            double pct = amt * 100 / Double.parseDouble(d[2]);
            ItemMicroBinding mb = ItemMicroBinding.inflate(getLayoutInflater(), b.micros, false);
            mb.name.setText(d[1]);
            mb.value.setText(String.format(Locale.getDefault(), "%.1f %s · %d%%", amt, d[3], Math.round(pct)));
            mb.bar.setFraction((float) (pct / 100));
            mb.getRoot().setContentDescription(d[1] + " " + mb.value.getText() + " of daily value");
            b.micros.addView(mb.getRoot());
            shown++;
        }
        b.microsNote.setText(shown == 0 ? getString(R.string.no_micros) : getString(R.string.dv_note));
    }

    private void addToMeal() {
        MealItemEntity item = DiaryRepository.itemFromFood(food, amount, unit.label, unit.grams);
        b.primary.setEnabled(false);
        graph().diary.addItems(date, meal, b.planned.isChecked(), "manual", Collections.singletonList(item), null, null, r -> {
            Ui.snack(b.getRoot(), getString(R.string.added_to, MealLabels.label(this, meal)));
            b.getRoot().postDelayed(this::finish, 450);
        });
    }

    private void saveItem() {
        b.primary.setEnabled(false);
        graph().diary.updatePortion(row.item.id, amount, unit.label, unit.grams);
        finish();
    }

    @SuppressWarnings("unused")
    private static boolean planned(ItemRow r) {
        return MealEntryEntity.STATUS_PLANNED.equals(r.status);
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> none() {
        return null;
    }
}
