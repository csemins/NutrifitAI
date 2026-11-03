package com.transformsai.nutrifitai.ui.hydration;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.repo.WaterRepository;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.ReminderSettingsEntity;
import com.transformsai.nutrifitai.data.db.entity.WaterEntity;
import com.transformsai.nutrifitai.data.db.model.DayWater;
import com.transformsai.nutrifitai.data.repo.ReminderRepository;
import com.transformsai.nutrifitai.databinding.ActivityHydrationBinding;
import com.transformsai.nutrifitai.databinding.ItemDrinkBinding;
import com.transformsai.nutrifitai.databinding.ViewToggleRowBinding;
import com.transformsai.nutrifitai.reminders.ReminderPlanner;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.widget.BarChartView;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Units;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Water gauge, quick adds, edit/delete, weekly chart and reminder settings. */
public class HydrationActivity extends BaseActivity {
    public static final String EXTRA_SCROLL_REMINDERS = "scroll_reminders";
    private ActivityHydrationBinding b;
    private HydrationViewModel vm;
    private String units = "metric";
    private ReminderSettingsEntity settings;
    private Runnable pendingEnable;
    private boolean binding;

    private final ActivityResultLauncher<String> notifPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted && pendingEnable != null) pendingEnable.run();
                else if (!granted) {
                    Ui.snack(b.getRoot(), getString(R.string.notif_perm_denied))
                            .setAction(R.string.open_settings, v -> Ui.openNotificationSettings(this));
                    render(settings);
                }
                pendingEnable = null;
            });

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityHydrationBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.content, true, true);
        b.bar.title.setText(R.string.hydration);
        b.bar.back.setOnClickListener(v -> finish());
        vm = new ViewModelProvider(this).get(HydrationViewModel.class);
        quick(b.glass, WaterRepository.GLASS_ML, R.string.glass);
        quick(b.bottle, WaterRepository.BOTTLE_ML, R.string.bottle);
        quick(b.flask, WaterRepository.LARGE_BOTTLE_ML, R.string.flask);
        b.custom.setOnClickListener(v -> amountDialog(null));
        vm.profile.observe(this, p -> {
            if (p != null) units = p.units;
            quick(b.glass, WaterRepository.GLASS_ML, R.string.glass);
            quick(b.bottle, WaterRepository.BOTTLE_ML, R.string.bottle);
            quick(b.flask, WaterRepository.LARGE_BOTTLE_ML, R.string.flask);
            renderTotal();
        });
        vm.total.observe(this, t -> {
            renderTotal();
            vm.loadWeek();
        });
        vm.goal.observe(this, g -> renderTotal());
        vm.drinks.observe(this, this::renderDrinks);
        vm.week.observe(this, this::renderWeek);
        vm.reminders.observe(this, r -> render(r == null ? ReminderRepository.defaults(vm.uid) : r));
        if (getIntent().getBooleanExtra(EXTRA_SCROLL_REMINDERS, false)) {
            b.scroll.post(() -> b.scroll.smoothScrollTo(0, b.remindersLabel.getTop()));
        }
    }

    private void quick(android.widget.TextView v, int ml, int label) {
        v.setText(getString(label, Units.volumeSmall(ml, units)));
        Ui.onClick(v, x -> {
            Ui.haptic(x);
            graph().water.add(ml, null);
        });
    }

    private void renderTotal() {
        Integer t = vm.total.getValue();
        int drank = t == null ? 0 : t;
        GoalEntity g = vm.goal.getValue();
        int goal = g == null ? 0 : g.waterMl;
        b.now.setText(Units.volumeNumber(drank, units));
        b.goal.setText(goal > 0 ? "/ " + Units.volumeNumber(goal, units) + " " + Units.volumeUnit(units) : Units.volumeUnit(units));
        b.gauge.setLevel(goal > 0 ? (float) drank / goal : 0);
        b.gauge.setContentDescription(getString(R.string.cd_gauge, Units.volumeSmall(drank, units), Units.volumeSmall(goal, units)));
        b.weekGoal.setText(goal > 0 ? getString(R.string.goal_value, Units.volumeNumber(goal, units) + " " + Units.volumeUnit(units)) : "");
        StringBuilder status = new StringBuilder();
        if (goal > 0) {
            int left = goal - drank;
            if (left <= 0) status.append(getString(R.string.water_done));
            else {
                int glasses = (int) Math.ceil(left / (double) WaterRepository.GLASS_ML);
                status.append(getString(R.string.water_to_go, glasses + (glasses == 1 ? " glass" : " glasses")));
            }
        }
        List<WaterEntity> d = vm.drinks.getValue();
        if (d != null && !d.isEmpty()) status.append(' ').append(getString(R.string.last_drink, Ui.ago(this, d.get(0).loggedAt)));
        b.status.setText(status.toString().trim());
    }

    private void renderDrinks(List<WaterEntity> list) {
        b.drinks.removeAllViews();
        b.noDrinks.setVisibility(list == null || list.isEmpty() ? View.VISIBLE : View.GONE);
        if (list == null) return;
        for (WaterEntity w : list) {
            ItemDrinkBinding ib = ItemDrinkBinding.inflate(getLayoutInflater(), b.drinks, false);
            ib.amount.setText(Units.volumeSmall(w.ml, units));
            ib.time.setText(DateUtil.time(w.loggedAt));
            ib.more.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                    .setItems(new String[]{getString(R.string.edit), getString(R.string.delete)}, (d, which) -> {
                        if (which == 0) amountDialog(w);
                        else {
                            graph().water.setDeleted(w.id, true);
                            Ui.snack(b.getRoot(), getString(R.string.item_deleted, Units.volumeSmall(w.ml, units)))
                                    .setAction(R.string.undo, x -> graph().water.setDeleted(w.id, false));
                        }
                    }).show());
            b.drinks.addView(ib.getRoot());
        }
        renderTotal();
    }

    private void renderWeek(@Nullable List<DayWater> data) {
        Map<String, Integer> byDate = new HashMap<>();
        if (data != null) for (DayWater d : data) byDate.put(d.date, d.ml);
        String from = DateUtil.iso(DateUtil.weekStart(DateUtil.todayDate()));
        List<BarChartView.Bar> bars = new ArrayList<>();
        GoalEntity g = vm.goal.getValue();
        StringBuilder cd = new StringBuilder();
        for (String d : DateUtil.range(from, DateUtil.shift(from, 6))) {
            BarChartView.Bar bar = new BarChartView.Bar();
            bar.label = DateUtil.dayLetter(d);
            Integer ml = byDate.get(d);
            if (ml != null && ml > 0) {
                bar.value = (float) ml;
                bar.valueLabel = Units.volumeNumber(ml, units);
                cd.append(DateUtil.dayShort(d)).append(' ').append(Units.volumeSmall(ml, units)).append(". ");
            }
            boolean met = g != null && ml != null && ml >= g.waterMl;
            bar.highlight = d.equals(DateUtil.today());
            bar.color = ContextCompat.getColor(this, bar.highlight || met ? R.color.nl_water : R.color.nl_water_deep);
            bars.add(bar);
        }
        b.week.setData(bars, g == null ? null : (float) g.waterMl, false);
        b.week.setContentDescription(getString(R.string.this_week) + ": " + cd);
    }

    private void amountDialog(@Nullable WaterEntity existing) {
        TextInputLayout til = new TextInputLayout(this);
        TextInputEditText et = new TextInputEditText(til.getContext());
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        til.setHint(getString(R.string.ml_hint));
        if (existing != null) et.setText(String.valueOf(existing.ml));
        til.addView(et);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(til);
        androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.custom_amount : R.string.edit).setView(box)
                .setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null).show();
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            double ml = Ui.parse(et.getText());
            if (Double.isNaN(ml) || ml < 1 || ml > 5000) {
                til.setError(getString(R.string.err_ml));
                return;
            }
            if (existing == null) graph().water.add((int) ml, null);
            else graph().water.update(existing.id, (int) ml);
            d.dismiss();
        });
    }

    // ---------------- reminders ----------------
    private void render(ReminderSettingsEntity s) {
        settings = s;
        binding = true;
        row(b.remWater, R.drawable.ic_clock, R.color.nl_water,
                getString(R.string.rem_every, intervalText(s.intervalMin)),
                getString(R.string.rem_hours, DateUtil.minutesToClock(s.startMin), DateUtil.minutesToClock(s.endMin)), s.waterEnabled);
        row(b.remMeals, R.drawable.ic_meals, R.color.nl_lime, getString(R.string.rem_meals),
                getString(R.string.rem_meals_sub, DateUtil.minutesToClock(s.breakfastMin), DateUtil.minutesToClock(s.lunchMin),
                        DateUtil.minutesToClock(s.dinnerMin)), s.mealEnabled);
        row(b.remCutoff, R.drawable.ic_moon, R.color.nl_purple, getString(R.string.rem_cutoff),
                getString(R.string.rem_cutoff_sub, DateUtil.minutesToClock(s.cutoffMin)), s.cutoffEnabled);
        binding = false;
        b.remWater.toggle.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            withPermission(on, () -> {
                ReminderSettingsEntity n = copy();
                n.waterEnabled = on;
                graph().reminders.save(n);
            });
        });
        b.remMeals.toggle.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            withPermission(on, () -> {
                ReminderSettingsEntity n = copy();
                n.mealEnabled = on;
                graph().reminders.save(n);
            });
        });
        b.remCutoff.toggle.setOnCheckedChangeListener((v, on) -> {
            if (binding) return;
            ReminderSettingsEntity n = copy();
            n.cutoffEnabled = on;
            graph().reminders.save(n);
        });
        b.remWater.texts.setOnClickListener(v -> editWater());
        b.remMeals.texts.setOnClickListener(v -> editMeals());
        b.remCutoff.texts.setOnClickListener(v -> pickTime(settings.cutoffMin, m -> {
            ReminderSettingsEntity n = copy();
            n.cutoffMin = m;
            n.cutoffEnabled = true;
            graph().reminders.save(n);
        }));
    }

    private static String intervalText(int min) {
        if (min % 60 == 0) return (min / 60) + (min == 60 ? " hour" : " hours");
        return min + " min";
    }

    private void row(ViewToggleRowBinding r, int icon, int tint, String title, String sub, boolean on) {
        r.icon.setImageResource(icon);
        r.icon.setImageTintList(ContextCompat.getColorStateList(this, tint));
        r.title.setText(title);
        r.subtitle.setText(sub);
        r.toggle.setChecked(on);
        r.toggle.setContentDescription(title);
    }

    private ReminderSettingsEntity copy() {
        ReminderSettingsEntity s = settings;
        ReminderSettingsEntity n = new ReminderSettingsEntity();
        n.uid = s.uid;
        n.waterEnabled = s.waterEnabled;
        n.intervalMin = s.intervalMin;
        n.startMin = s.startMin;
        n.endMin = s.endMin;
        n.cutoffEnabled = s.cutoffEnabled;
        n.cutoffMin = s.cutoffMin;
        n.mealEnabled = s.mealEnabled;
        n.breakfastMin = s.breakfastMin;
        n.lunchMin = s.lunchMin;
        n.dinnerMin = s.dinnerMin;
        return n;
    }

    /** Contextual POST_NOTIFICATIONS request (Android 13+) only when enabling a reminder. */
    private void withPermission(boolean enabling, Runnable action) {
        if (!enabling || Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            action.run();
            return;
        }
        pendingEnable = action;
        if (graph().prefs.notifRationaleShown() && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        new MaterialAlertDialogBuilder(this).setTitle(R.string.notif_perm_title).setMessage(R.string.notif_perm_body)
                .setPositiveButton(R.string.next, (d, w) -> {
                    graph().prefs.setNotifRationaleShown();
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                })
                .setNegativeButton(R.string.cancel, (d, w) -> render(settings))
                .setOnCancelListener(d -> render(settings)).show();
    }

    private void editWater() {
        Slider slider = new Slider(this);
        slider.setValueFrom(ReminderPlanner.MIN_INTERVAL);
        slider.setValueTo(ReminderPlanner.MAX_INTERVAL);
        slider.setStepSize(30);
        slider.setValue(Math.max(ReminderPlanner.MIN_INTERVAL, Math.min(ReminderPlanner.MAX_INTERVAL, settings.intervalMin / 30 * 30)));
        slider.setLabelFormatter(v -> intervalText((int) v));
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        box.addView(slider);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.rem_interval_title).setView(box)
                .setPositiveButton(R.string.next, (d, w) -> pickTime(settings.startMin, start -> pickTime(settings.endMin, end -> {
                    ReminderSettingsEntity n = copy();
                    n.intervalMin = (int) slider.getValue();
                    n.startMin = start;
                    n.endMin = end;
                    if (!ReminderPlanner.valid(n)) {
                        Ui.snack(b.getRoot(), getString(R.string.rem_start) + " < " + getString(R.string.rem_end));
                        return;
                    }
                    graph().reminders.save(n);
                }))).setNegativeButton(R.string.cancel, null).show();
    }

    private void editMeals() {
        pickTime(settings.breakfastMin, bf -> pickTime(settings.lunchMin, lu -> pickTime(settings.dinnerMin, di -> {
            ReminderSettingsEntity n = copy();
            n.breakfastMin = bf;
            n.lunchMin = lu;
            n.dinnerMin = di;
            graph().reminders.save(n);
        })));
    }

    private void pickTime(int minutes, java.util.function.IntConsumer onPick) {
        MaterialTimePicker p = new MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(minutes / 60).setMinute(minutes % 60).build();
        p.addOnPositiveButtonClickListener(v -> onPick.accept(p.getHour() * 60 + p.getMinute()));
        p.show(getSupportFragmentManager(), "time");
    }
}
