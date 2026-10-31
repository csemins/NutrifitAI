package com.transformsai.nutrifitai.ui.food;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.databinding.ActivityCustomFoodBinding;
import com.transformsai.nutrifitai.domain.NutritionMath;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.util.Units;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

/** Create/edit a personal food with label values per 100 g. Validated before saving. */
public class CustomFoodActivity extends BaseActivity {
    private static final String EXTRA_ID = "id";
    private static final String EXTRA_NAME = "name";

    public static Intent create(Context c, String name) {
        return new Intent(c, CustomFoodActivity.class).putExtra(EXTRA_NAME, name);
    }

    public static Intent edit(Context c, String id) {
        return new Intent(c, CustomFoodActivity.class).putExtra(EXTRA_ID, id);
    }

    private ActivityCustomFoodBinding b;
    private FoodEntity editing;

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityCustomFoodBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, true);
        b.bar.title.setText(R.string.custom_title);
        b.bar.back.setOnClickListener(v -> finish());
        String id = getIntent().getStringExtra(EXTRA_ID);
        if (id != null) {
            graph().foods.load(id, r -> {
                if (r.value == null) return;
                editing = r.value;
                if (s == null) fill(editing);
                b.delete.setVisibility(View.VISIBLE);
            });
        } else if (s == null) {
            b.name.setText(getIntent().getStringExtra(EXTRA_NAME));
            b.servingLabel.setText("1 serving");
            b.servingG.setText("100");
        }
        Ui.onClick(b.save, v -> save());
        b.delete.setOnClickListener(v -> new MaterialAlertDialogBuilder(this).setMessage(R.string.cf_delete_q)
                .setPositiveButton(R.string.delete, (d, w) -> graph().foods.deleteCustom(editing.id, r -> finish()))
                .setNegativeButton(R.string.cancel, null).show());
    }

    private void fill(FoodEntity f) {
        b.name.setText(f.name);
        b.nameBn.setText(f.nameBn);
        b.servingLabel.setText(f.servingLabel);
        b.servingG.setText(Units.num(f.servingGrams));
        b.kcal.setText(Units.num(f.kcal100));
        b.protein.setText(Units.num(f.protein100));
        b.carbs.setText(Units.num(f.carbs100));
        b.fat.setText(Units.num(f.fat100));
        if (f.fibre100 != null) b.fibre.setText(Units.num(f.fibre100));
        if (f.sugar100 != null) b.sugar.setText(Units.num(f.sugar100));
        if (f.sodiumMg100 != null) b.sodium.setText(Units.num(f.sodiumMg100));
    }

    private double req(EditText e, TextInputLayout l, double min, double max) {
        double v = Ui.parse(e.getText());
        if (Double.isNaN(v)) {
            l.setError(getString(R.string.cf_err_required));
            return Double.NaN;
        }
        if (v < min || v > max) {
            l.setError(getString(R.string.cf_err_range));
            return Double.NaN;
        }
        l.setError(null);
        return v;
    }

    @Nullable
    private Double opt(EditText e, TextInputLayout l, double max, boolean[] ok) {
        if (e.getText() == null || e.getText().toString().trim().isEmpty()) {
            l.setError(null);
            return null;
        }
        double v = Ui.parse(e.getText());
        if (Double.isNaN(v) || v < 0 || v > max) {
            l.setError(getString(R.string.cf_err_range));
            ok[0] = false;
            return null;
        }
        l.setError(null);
        return v;
    }

    private void save() {
        String name = b.name.getText() == null ? "" : b.name.getText().toString().trim();
        boolean[] ok = {true};
        if (name.length() < 2) {
            b.nameLayout.setError(getString(R.string.cf_err_required));
            ok[0] = false;
        } else b.nameLayout.setError(null);
        double sg = req(b.servingG, b.servingGLayout, 1, 3000);
        double kcal = req(b.kcal, b.kcalLayout, 0, 900);
        double p = req(b.protein, b.proteinLayout, 0, 100);
        double c = req(b.carbs, b.carbsLayout, 0, 100);
        double f = req(b.fat, b.fatLayout, 0, 100);
        Double fib = opt(b.fibre, b.fibreLayout, 100, ok);
        Double sug = opt(b.sugar, b.sugarLayout, 100, ok);
        Double na = opt(b.sodium, b.sodiumLayout, 40000, ok);
        if (Double.isNaN(sg) || Double.isNaN(kcal) || Double.isNaN(p) || Double.isNaN(c) || Double.isNaN(f) || !ok[0]) return;
        if (p + c + f > 100.5) {
            b.warning.setVisibility(View.VISIBLE);
            b.warning.setText(R.string.cf_err_macros);
            return;
        }
        double atw = NutritionMath.atwaterKcal(p, c, f);
        if (b.warning.getVisibility() != View.VISIBLE && Math.abs(atw - kcal) > Math.max(40, 0.3 * kcal)) {
            // First press shows the warning; pressing Save again confirms.
            b.warning.setVisibility(View.VISIBLE);
            b.warning.setText(getString(R.string.cf_warn_energy, Math.round(atw)));
            return;
        }
        FoodEntity e = editing != null ? editing : new FoodEntity();
        e.name = name;
        e.nameBn = b.nameBn.getText() == null || b.nameBn.getText().toString().trim().isEmpty() ? null : b.nameBn.getText().toString().trim();
        e.servingLabel = b.servingLabel.getText() == null || b.servingLabel.getText().toString().trim().isEmpty() ? "1 serving" : b.servingLabel.getText().toString().trim();
        e.servingGrams = sg;
        e.kcal100 = kcal;
        e.protein100 = p;
        e.carbs100 = c;
        e.fat100 = f;
        e.fibre100 = fib;
        e.sugar100 = sug;
        e.sodiumMg100 = na;
        e.category = "custom";
        e.provenance = FoodEntity.PROV_USER;
        e.sourceRef = "Entered by you";
        b.save.setEnabled(false);
        graph().foods.saveCustom(e, r -> finish());
    }
}
