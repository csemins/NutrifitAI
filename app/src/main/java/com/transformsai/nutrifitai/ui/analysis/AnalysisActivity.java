package com.transformsai.nutrifitai.ui.analysis;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.ai.AiItem;
import com.transformsai.nutrifitai.ai.AiResult;
import com.transformsai.nutrifitai.data.db.entity.AiAnalysisEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.repo.DiaryRepository;
import com.transformsai.nutrifitai.databinding.ActivityAnalysisBinding;
import com.transformsai.nutrifitai.databinding.DialogAiItemBinding;
import com.transformsai.nutrifitai.domain.Nutrients;
import com.transformsai.nutrifitai.domain.PortionScaler;
import com.transformsai.nutrifitai.reminders.MealLabels;
import com.transformsai.nutrifitai.ui.camera.CameraActivity;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.main.MainActivity;
import com.transformsai.nutrifitai.util.Units;
import com.transformsai.nutrifitai.ui.common.AiAccessSheet;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * Shows analysis progress (survives rotation and process death via Room + WorkManager), then an
 * editable review. Nothing is written to the diary until the user taps Save.
 */
public class AnalysisActivity extends BaseActivity implements AiItemsAdapter.Listener {
    private static final String EXTRA_ID = "analysis_id";

    public static Intent intent(Context c, String id) {
        return new Intent(c, AnalysisActivity.class).putExtra(EXTRA_ID, id);
    }

    private ActivityAnalysisBinding b;
    private AnalysisViewModel vm;
    private AiItemsAdapter adapter;
    private AiAnalysisEntity current;
    private String id;

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityAnalysisBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        AiAccessSheet.attach(this);
        InsetsUtil.padding(b.root, true, false);
        InsetsUtil.padding(b.saveBar, false, true);
        InsetsUtil.padding(b.loading, false, true);
        InsetsUtil.padding(b.message, false, true);
        id = getIntent().getStringExtra(EXTRA_ID);
        vm = new ViewModelProvider(this).get(AnalysisViewModel.class);
        adapter = new AiItemsAdapter(this);
        b.items.setLayoutManager(new LinearLayoutManager(this));
        b.items.setAdapter(adapter);
        b.bar.title.setText(R.string.review_title);
        b.bar.back.setOnClickListener(v -> finish());
        b.cancel.setOnClickListener(v -> graph().analysis.cancel(id));
        b.mealChip.setOnClickListener(v -> new MaterialAlertDialogBuilder(this).setTitle(R.string.choose_meal)
                .setItems(getResources().getStringArray(R.array.meal_types), (d, w) -> {
                    vm.meal = MealLabels.TYPES[w];
                    vm.persist();
                    renderTotals();
                }).show());
        Ui.onClick(b.save, v -> save());
        vm.observe(id).observe(this, this::render);
    }

    private void render(@Nullable AiAnalysisEntity a) {
        if (a == null) {
            finish();
            return;
        }
        current = a;
        if (vm.meal == null) vm.meal = a.mealType;
        b.loading.setVisibility(View.GONE);
        b.message.setVisibility(View.GONE);
        b.reviewBox.setVisibility(View.GONE);
        switch (a.status) {
            case AiAnalysisEntity.QUEUED:
            case AiAnalysisEntity.RUNNING:
                b.loading.setVisibility(View.VISIBLE);
                b.bar.title.setText(R.string.analysing);
                b.loadingSub.setText(AiAnalysisEntity.QUEUED.equals(a.status) ? R.string.waiting_network : R.string.analysing_sub);
                if (a.imageLocalPath != null) com.bumptech.glide.Glide.with(this).load(new java.io.File(a.imageLocalPath)).centerCrop().into(b.loadingImage);
                else b.loadingImage.setEmoji("📝", null);
                break;
            case AiAnalysisEntity.ERROR:
                message(R.drawable.ic_warning, getString(R.string.ai_error_title), a.errorMessage, R.string.retry,
                        () -> graph().analysis.retry(id));
                break;
            case AiAnalysisEntity.CANCELLED:
                message(R.drawable.ic_close, getString(R.string.ai_cancelled), "", R.string.retry, () -> graph().analysis.retry(id));
                break;
            case AiAnalysisEntity.NON_FOOD:
                message(R.drawable.ic_camera, getString(R.string.ai_non_food_title), getString(R.string.ai_non_food_body),
                        R.string.retake, () -> {
                            startActivity(new Intent(this, CameraActivity.class));
                            finish();
                        });
                break;
            case AiAnalysisEntity.SAVED:
                if (!vm.saving) {
                    message(R.drawable.ic_check, getString(R.string.saved_to_diary), "", R.string.done, this::finish);
                }
                break;
            default:
                AiResult r = vm.result(a);
                if (r == null) {
                    message(R.drawable.ic_warning, getString(R.string.ai_error_title), getString(R.string.retry), R.string.retry,
                            () -> graph().analysis.retry(id));
                    return;
                }
                b.bar.title.setText(R.string.review_title);
                b.reviewBox.setVisibility(View.VISIBLE);
                adapter.set(r, a.imageLocalPath);
                renderTotals();
        }
    }

    private void message(int icon, String title, String body, int primary, Runnable action) {
        b.bar.title.setText(R.string.ai_screen_title);
        b.message.setVisibility(View.VISIBLE);
        b.messageIcon.setImageResource(icon);
        b.messageTitle.setText(title);
        b.messageBody.setText(body);
        b.messageBody.setVisibility(body == null || body.isEmpty() ? View.GONE : View.VISIBLE);
        b.messagePrimary.setText(primary);
        b.messagePrimary.setOnClickListener(v -> action.run());
        b.messageSecondary.setOnClickListener(v -> finish());
    }

    private void renderTotals() {
        if (current == null) return;
        AiResult r = vm.result(current);
        if (r == null) return;
        Nutrients t = r.totals();
        b.total.setText(Ui.kcal(t.kcal) + " kcal");
        b.totalMacros.setText(Ui.macroChips(this, t));
        b.mealChip.setText(MealLabels.label(this, vm.meal));
        int n = r.activeItems().size();
        b.save.setEnabled(n > 0);
        b.save.setText(n == 1 ? getString(R.string.save_one, MealLabels.label(this, vm.meal))
                : getString(R.string.save_to_diary, n, MealLabels.label(this, vm.meal)));
    }

    @Override
    public void onEdit(AiItem i) {
        showItemDialog(i);
    }

    @Override
    public void onRemove(AiItem i) {
        i.removed = true;
        vm.persist();
        adapter.set(vm.result(current), current.imageLocalPath);
        renderTotals();
    }

    @Override
    public void onAddManual() {
        showItemDialog(null);
    }

    /**
     * Edit name/portion/nutrients. Changing the grams rescales the nutrient fields live; a nutrient the user types
     * becomes the baseline that later portion changes scale from (see {@link PortionScaler}).
     */
    private void showItemDialog(@Nullable AiItem existing) {
        DialogAiItemBinding d = DialogAiItemBinding.inflate(getLayoutInflater());
        if (existing != null) {
            d.name.setText(existing.name);
            d.weight.setText(Units.num(Math.round(existing.weightG)));
            d.kcal.setText(Units.num(Math.round(existing.kcal)));
            d.protein.setText(Units.num(Math.round(existing.protein * 10) / 10.0));
            d.carbs.setText(Units.num(Math.round(existing.carbs * 10) / 10.0));
            d.fat.setText(Units.num(Math.round(existing.fat * 10) / 10.0));
            if (existing.fibre != null) d.fibre.setText(Units.num(Math.round(existing.fibre * 10) / 10.0));
        }
        wireScaling(d, existing);
        androidx.appcompat.app.AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.add_item : R.string.edit_item).setView(d.getRoot())
                .setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null).show();
        dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = d.name.getText() == null ? "" : d.name.getText().toString().trim();
            double w = Ui.parse(d.weight.getText()), k = Ui.parse(d.kcal.getText()), p = Ui.parse(d.protein.getText()),
                    c = Ui.parse(d.carbs.getText()), f = Ui.parse(d.fat.getText());
            double fib = Ui.parse(d.fibre.getText());
            boolean ok = true;
            if (name.length() < 2) { d.nameLayout.setError(getString(R.string.cf_err_required)); ok = false; } else d.nameLayout.setError(null);
            if (Double.isNaN(w) || w <= 0 || w > 3000) { d.weightLayout.setError(getString(R.string.cf_err_range)); ok = false; } else d.weightLayout.setError(null);
            if (Double.isNaN(k) || k < 0 || k > 5000 || k * 100 / Math.max(w, 1) > 950) { d.kcalLayout.setError(getString(R.string.cf_err_range)); ok = false; } else d.kcalLayout.setError(null);
            if (Double.isNaN(p) || p < 0 || p > 500) { d.proteinLayout.setError("!"); ok = false; } else d.proteinLayout.setError(null);
            if (Double.isNaN(c) || c < 0 || c > 500) { d.carbsLayout.setError("!"); ok = false; } else d.carbsLayout.setError(null);
            if (Double.isNaN(f) || f < 0 || f > 500) { d.fatLayout.setError("!"); ok = false; } else d.fatLayout.setError(null);
            if (!Double.isNaN(fib) && (fib < 0 || fib > 150)) { d.fibreLayout.setError("!"); ok = false; }
            if (!ok) return;
            AiResult r = vm.result(current);
            if (existing == null) {
                AiItem n = new AiItem();
                n.id = "manual_" + System.currentTimeMillis();
                n.name = name;
                n.confidence = 1;
                n.quantity = 1;
                n.unit = "portion";
                n.weightG = w;
                n.kcal = k;
                n.protein = p;
                n.carbs = c;
                n.fat = f;
                n.fibre = Double.isNaN(fib) ? null : fib;
                n.assumptions.add("Added manually");
                r.items.add(n);
            } else {
                existing.name = name;
                // the fields already hold the rescaled (or typed) values; sugar, sodium and the range follow the portion
                double scaledKcal = existing.kcal * w / existing.weightG;
                if (Math.abs(existing.weightG - w) > 0.01) existing.setWeight(w);
                if (Math.abs(scaledKcal - k) > 1) { // calories typed by hand: the AI range no longer applies
                    existing.kcalMin = null;
                    existing.kcalMax = null;
                }
                existing.kcal = k;
                existing.protein = p;
                existing.carbs = c;
                existing.fat = f;
                existing.fibre = Double.isNaN(fib) ? null : fib;
                existing.confidence = Math.max(existing.confidence, 0.99); // user-verified
            }
            vm.persist();
            adapter.set(r, current.imageLocalPath);
            renderTotals();
            dlg.dismiss();
        });
    }

    /** Live portion scaling inside the edit dialog. */
    private void wireScaling(DialogAiItemBinding d, @Nullable AiItem existing) {
        android.widget.EditText[] fields = {d.kcal, d.protein, d.carbs, d.fat, d.fibre};
        double grams = existing == null ? Ui.parse(d.weight.getText()) : existing.weightG;
        double[] start = existing == null ? new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN}
                : new double[]{existing.kcal, existing.protein, existing.carbs, existing.fat,
                existing.fibre == null ? Double.NaN : existing.fibre};
        PortionScaler scaler = new PortionScaler(Double.isNaN(grams) ? 0 : grams, start);
        boolean[] updating = {false};
        d.weight.addTextChangedListener(new SimpleWatcher(() -> {
            if (updating[0]) return;
            double w = Ui.parse(d.weight.getText());
            if (Double.isNaN(w) || w <= 0) return;
            if (!(scaler.grams() > 0)) { // first grams for a new item: whatever was typed becomes the baseline
                startScaling(w, fields, scaler);
                return;
            }
            double[] v = scaler.setGrams(w);
            updating[0] = true;
            for (int i = 0; i < fields.length; i++) {
                if (Double.isNaN(v[i])) continue;
                double shown = i == PortionScaler.KCAL ? Math.round(v[i]) : Math.round(v[i] * 10) / 10.0;
                fields[i].setText(Units.num(shown));
            }
            updating[0] = false;
        }));
        for (int i = 0; i < fields.length; i++) {
            final int n = i;
            fields[i].addTextChangedListener(new SimpleWatcher(() -> {
                if (updating[0]) return;
                if (!(scaler.grams() > 0)) return;
                scaler.setValue(n, Ui.parse(fields[n].getText()));
            }));
        }
    }

    private static void startScaling(double w, android.widget.EditText[] fields, PortionScaler scaler) {
        scaler.setGrams(w);
        double[] typed = readAll(fields);
        for (int i = 0; i < typed.length; i++) scaler.setValue(i, typed[i]);
    }

    private static double[] readAll(android.widget.EditText[] fields) {
        double[] out = new double[fields.length];
        for (int i = 0; i < fields.length; i++) out[i] = Ui.parse(fields[i].getText());
        return out;
    }

    /** TextWatcher that only cares that the text changed. */
    private static final class SimpleWatcher implements android.text.TextWatcher {
        private final Runnable r;

        SimpleWatcher(Runnable r) {
            this.r = r;
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int c, int x) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

        @Override
        public void afterTextChanged(android.text.Editable e) {
            r.run();
        }
    }

    private void save() {
        AiResult r = vm.result(current);
        if (r == null || r.activeItems().isEmpty()) {
            Ui.snack(b.getRoot(), getString(R.string.nothing_to_save));
            return;
        }
        vm.saving = true;
        b.save.setEnabled(false);
        List<MealItemEntity> items = DiaryRepository.itemsFromAi(r);
        String source = "image".equals(current.inputType) ? "camera" : "text";
        graph().diary.addItems(current.date, vm.meal, false, source, items, current.imageLocalPath, current.id, res -> {
            graph().analysis.markSaved(id, com.transformsai.nutrifitai.ai.AiResultParser.toJson(r));
            vm.reset();
            startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_MEALS)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
    }
}
