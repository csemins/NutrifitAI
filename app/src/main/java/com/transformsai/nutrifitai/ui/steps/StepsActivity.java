package com.transformsai.nutrifitai.ui.steps;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.StepDayEntity;
import com.transformsai.nutrifitai.data.db.entity.WorkoutSessionEntity;
import com.transformsai.nutrifitai.data.repo.StepsRepository;
import com.transformsai.nutrifitai.databinding.ActivityStepsBinding;
import com.transformsai.nutrifitai.databinding.ViewInfoTileBinding;
import com.transformsai.nutrifitai.databinding.ViewSettingRowBinding;
import com.transformsai.nutrifitai.health.HealthConnect;
import com.transformsai.nutrifitai.health.StepSensor;
import com.transformsai.nutrifitai.health.StepWorker;
import com.transformsai.nutrifitai.health.WalkService;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.widget.BarChartView;
import com.transformsai.nutrifitai.util.DateUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Daily walk tracker: today's steps against the goal (ring), distance and energy estimates, a
 * user-started walk session (foreground service), the last 7 days, and Health Connect for steps
 * from Google Fit / Samsung Health / watches. Live sensor updates while the screen is visible;
 * the listener is removed in onStop.
 */
public class StepsActivity extends BaseActivity {
    private ActivityStepsBinding b;
    private String uid;
    private StepSensor sensor;
    private int todaySteps;
    private final NumberFormat nf = NumberFormat.getIntegerInstance();

    private final ActivityResultLauncher<String> activityPerm = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    graph().prefs.setStepTracking(true);
                    StepWorker.schedule(this);
                    startLive();
                } else if (!shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)) {
                    Ui.snack(b.getRoot(), getString(R.string.steps_perm_denied)).setAction(R.string.open_settings, v -> Ui.openAppSettings(this));
                }
                renderBanner();
            });

    private final ActivityResultLauncher<String[]> hcPerm = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), res -> {
                boolean any = HealthConnect.canReadSteps(this) || HealthConnect.granted(this);
                graph().prefs.setHealthConnect(any);
                if (any) {
                    StepWorker.schedule(this);
                    syncHealthNow();
                } else {
                    Ui.snack(b.getRoot(), getString(R.string.hc_denied));
                }
                renderHealth();
                renderBanner();
            });

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        uid = uidOrFinish();
        if (uid == null) return;
        b = ActivityStepsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.bar.getRoot(), true, false);
        InsetsUtil.padding(b.content, false, true);
        b.bar.title.setText(R.string.walk_tracker);
        b.bar.back.setOnClickListener(v -> finish());
        b.ring.setColors(ContextCompat.getColor(this, R.color.nl_lime), ContextCompat.getColor(this, R.color.nl_fibre));
        sensor = new StepSensor(this);
        row(b.rowGoal, R.drawable.ic_flame, R.string.step_goal, this::pickGoal);
        row(b.rowAdd, R.drawable.ic_add, R.string.steps_add_manual, this::addManual);
        b.walkButton.setOnClickListener(v -> toggleWalk());
        b.hcConnect.setOnClickListener(v -> connectHealth());
        b.hcManage.setOnClickListener(v -> {
            try {
                startActivity(HealthConnect.manageIntent());
            } catch (Exception e) {
                Ui.snack(b.getRoot(), getString(R.string.hc_unavailable));
            }
        });

        String today = DateUtil.today();
        graph().steps.observeDay(uid, today).observe(this, this::renderToday);
        graph().steps.observeRange(uid, DateUtil.shift(today, -6), today).observe(this, this::renderWeek);
        LiveData<List<WorkoutSessionEntity>> walks = graph().db.workouts().observeRange(uid, today, today);
        walks.observe(this, list -> {
            long ms = 0;
            int n = 0;
            if (list != null) for (WorkoutSessionEntity w : list) if ("walk".equals(w.mode)) {
                ms += w.activeMs;
                n++;
            }
            tile(b.tWalks, R.string.walks_today, n == 0 ? "0" : getString(R.string.chip_minutes, (int) Math.max(1, ms / 60000)));
        });
        WalkService.state().observe(this, this::renderWalk);
        graph().workouts.observeWalks(uid).observe(this, this::renderWalks);
        renderBanner();
        renderHealth();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startLive();
        if (graph().prefs.healthConnect() && HealthConnect.canReadSteps(this)) syncHealthNow();
    }

    @Override
    protected void onStop() {
        if (sensor != null) sensor.stop(); // never keep the sensor registered in the background from here
        super.onStop();
    }

    private void startLive() {
        if (!graph().prefs.stepTracking() || !StepsRepository.hasPermission(this) || !sensor.available()) return;
        sensor.stop();
        sensor.listen(v -> graph().runIo(() -> graph().steps.onCounter(uid, v, System.currentTimeMillis())));
    }

    // ---------------------------------------------------------------- render
    private void renderToday(@Nullable StepDayEntity d) {
        todaySteps = d == null ? 0 : d.steps;
        int goal = graph().prefs.stepGoal();
        b.steps.setText(nf.format(todaySteps));
        b.goal.setText(getString(R.string.steps_of_goal, nf.format(goal)));
        b.ring.setProgress(goal <= 0 ? 0 : todaySteps / (float) goal, true);
        double km = d == null ? 0 : d.distanceM / 1000.0;
        tile(b.tDistance, R.string.distance, String.format(Locale.getDefault(), "%.1f km", km));
        graph().runIo(() -> {
            double kg = graph().workouts.bodyWeightKg(uid);
            int kcal = (int) Math.round(StepsRepository.kcalForSteps(todaySteps, kg));
            graph().main.post(() -> {
                if (b != null) tile(b.tKcal, R.string.stat_kcal, "~" + kcal);
            });
        });
        b.ring.setContentDescription(getString(R.string.cd_steps_ring, todaySteps, goal));
        b.rowGoal.subtitle.setText(getString(R.string.steps_per_day, nf.format(goal)));
    }

    private void renderWeek(@Nullable List<StepDayEntity> list) {
        String today = DateUtil.today();
        List<BarChartView.Bar> bars = new ArrayList<>();
        int sum = 0, days = 0;
        int goal = graph().prefs.stepGoal();
        for (int i = 6; i >= 0; i--) {
            String d = DateUtil.shift(today, -i);
            BarChartView.Bar bar = new BarChartView.Bar();
            bar.label = DateUtil.dayLetter(d);
            bar.highlight = i == 0;
            bar.color = ContextCompat.getColor(this, R.color.nl_lime);
            if (list != null) for (StepDayEntity s : list) if (d.equals(s.date) && s.steps > 0) {
                bar.value = (float) s.steps;
                bar.valueLabel = s.steps >= 1000 ? Math.round(s.steps / 100f) / 10f + "k" : String.valueOf(s.steps);
                if (s.steps >= goal) bar.color = ContextCompat.getColor(this, R.color.nl_lime_bright);
                sum += s.steps;
                days++;
            }
            bars.add(bar);
        }
        b.week.setLine(true, ContextCompat.getColor(this, R.color.nl_lime)); // walking trend
        b.week.setData(bars, (float) goal, true);
        b.weekSub.setText(days == 0 ? getString(R.string.steps_week_empty) : getString(R.string.steps_week_avg, nf.format(sum / days), days));
        b.week.setContentDescription(b.weekSub.getText());
    }

    private long shownStopAt;

    private void renderWalk(@Nullable WalkService.State st) {
        if (st != null && st.stoppedBy != null && st.startedAt != shownStopAt) {
            shownStopAt = st.startedAt;
            Ui.snack(b.getRoot(), st.stoppedBy + ". " + getString(R.string.walk_stopped_body, st.steps));
        }
        boolean running = st != null && st.running;
        b.walkTitle.setText(running ? getString(R.string.walk_running, WalkService.formatElapsed(st.elapsedMs)) : getString(R.string.walk_start_mapped));
        b.walkSub.setText(running ? getString(R.string.walk_row, WalkService.formatKm(st.distanceM), WalkService.formatElapsed(st.elapsedMs),
                com.transformsai.nutrifitai.fitness.RouteTracker.pace(st.avgSpeedMps)) : getString(R.string.walk_start_mapped_sub));
        b.walkSub.setVisibility(View.VISIBLE);
        b.walkButton.setText(running ? R.string.walk_open : R.string.walk_start);
    }

    /** Recorded walks, newest first, each with its route drawn as a thumbnail. */
    private void renderWalks(@Nullable java.util.List<WorkoutSessionEntity> walks) {
        b.walks.removeAllViews();
        int n = walks == null ? 0 : Math.min(20, walks.size());
        b.walksEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
        for (int i = 0; i < n; i++) {
            WorkoutSessionEntity w = walks.get(i);
            com.transformsai.nutrifitai.databinding.ItemWalkBinding r =
                    com.transformsai.nutrifitai.databinding.ItemWalkBinding.inflate(getLayoutInflater(), b.walks, false);
            r.art.setRoute(com.transformsai.nutrifitai.fitness.RouteTracker.decode(w.routePolyline));
            r.art.setShowGrid(false);
            r.art.setClipToOutline(true);
            r.title.setText(w.title);
            r.date.setText(df.format(new java.util.Date(w.startedAt)));
            long moving = w.movingMs > 0 ? w.movingMs : w.activeMs;
            double avg = w.movingMs <= 0 ? 0 : w.distanceM / (w.movingMs / 1000.0);
            r.stats.setText(w.distanceM > 0 ? getString(R.string.walk_row, WalkService.formatKm(w.distanceM), WalkService.formatElapsed(moving),
                    com.transformsai.nutrifitai.fitness.RouteTracker.pace(avg))
                    : getString(R.string.walk_row_steps, nf.format(w.steps), WalkService.formatElapsed(moving)));
            r.getRoot().setContentDescription(w.title + ", " + r.date.getText() + ", " + r.stats.getText());
            r.getRoot().setOnClickListener(v -> startActivity(WalkSummaryActivity.intent(this, w.id)));
            b.walks.addView(r.getRoot());
        }
    }

    private void renderBanner() {
        boolean hasSensor = StepsRepository.hasSensor(this);
        boolean hcOn = graph().prefs.healthConnect() && HealthConnect.canReadSteps(this);
        if (!hasSensor && hcOn) {
            b.banner.setVisibility(View.GONE); // steps come from Health Connect
        } else if (!hasSensor) {
            banner(getString(R.string.steps_no_sensor), HealthConnect.available(this) ? getString(R.string.hc_connect) : null, this::connectHealth);
        } else if (!StepsRepository.hasPermission(this) || !graph().prefs.stepTracking()) {
            banner(getString(R.string.steps_need_perm), getString(R.string.allow), this::enableTracking);
        } else {
            b.banner.setVisibility(View.GONE);
        }
    }

    private void banner(String text, @Nullable String action, Runnable r) {
        b.banner.setVisibility(View.VISIBLE);
        b.bannerText.setText(text);
        b.bannerAction.setVisibility(action == null ? View.GONE : View.VISIBLE);
        if (action != null) b.bannerAction.setText(action);
        b.bannerAction.setOnClickListener(v -> r.run());
    }

    private void renderHealth() {
        boolean avail = HealthConnect.available(this);
        boolean on = graph().prefs.healthConnect() && (HealthConnect.canReadSteps(this) || HealthConnect.granted(this));
        if (!avail) {
            b.hcStatus.setText(R.string.hc_needs_14);
            b.hcConnect.setEnabled(false);
            b.hcManage.setVisibility(View.GONE);
            return;
        }
        long last = graph().prefs.healthLastSync();
        b.hcStatus.setText(on ? (last == 0 ? getString(R.string.hc_connected) : getString(R.string.hc_synced, Ui.ago(this, last)))
                : getString(R.string.hc_not_connected));
        b.hcConnect.setText(on ? R.string.sync_now : R.string.hc_connect);
        b.hcManage.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void tile(ViewInfoTileBinding t, int label, String value) {
        t.label.setText(label);
        t.value.setText(value);
        t.getRoot().setContentDescription(getString(label) + ": " + value);
    }

    private void row(ViewSettingRowBinding r, int icon, int title, Runnable action) {
        r.icon.setImageResource(icon);
        r.icon.setImageTintList(ContextCompat.getColorStateList(this, R.color.nl_lime));
        r.title.setText(title);
        r.subtitle.setVisibility(View.VISIBLE);
        r.getRoot().setOnClickListener(v -> action.run());
    }

    // ---------------------------------------------------------------- actions
    private void enableTracking() {
        if (Build.VERSION.SDK_INT >= 29 && !StepsRepository.hasPermission(this)) {
            activityPerm.launch(Manifest.permission.ACTIVITY_RECOGNITION);
        } else {
            graph().prefs.setStepTracking(true);
            StepWorker.schedule(this);
            startLive();
            renderBanner();
        }
    }

    /** The mapped walk screen (it asks for location and starts the walk itself). */
    private void toggleWalk() {
        startActivity(WalkActivity.intent(this));
    }

    private void connectHealth() {
        if (!HealthConnect.available(this)) {
            Ui.snack(b.getRoot(), getString(R.string.hc_needs_14));
            return;
        }
        if (graph().prefs.healthConnect() && HealthConnect.canReadSteps(this)) {
            syncHealthNow();
            return;
        }
        new MaterialAlertDialogBuilder(this).setTitle(R.string.hc_title).setMessage(R.string.hc_explain)
                .setPositiveButton(R.string.hc_continue, (d, w) -> hcPerm.launch(HealthConnect.PERMISSIONS))
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void syncHealthNow() {
        b.hcStatus.setText(R.string.syncing);
        graph().runIo(() -> {
            String r = HealthConnect.syncBlocking(this, graph(), uid);
            graph().main.post(() -> {
                if (b == null) return;
                renderHealth();
                if ("unavailable".equals(r)) b.hcStatus.setText(R.string.hc_unavailable);
            });
        });
    }

    private void pickGoal() {
        int[] goals = {4000, 6000, 7500, 8000, 10000, 12000, 15000};
        String[] labels = new String[goals.length];
        int sel = 3;
        for (int i = 0; i < goals.length; i++) {
            labels[i] = getString(R.string.steps_per_day, nf.format(goals[i]));
            if (goals[i] == graph().prefs.stepGoal()) sel = i;
        }
        new MaterialAlertDialogBuilder(this).setTitle(R.string.step_goal)
                .setSingleChoiceItems(labels, sel, (d, w) -> {
                    graph().prefs.setStepGoal(goals[w]);
                    d.dismiss();
                    recreate();
                }).show();
    }

    /** Steps walked without the phone (e.g. a watch that does not use Health Connect). */
    private void addManual() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint(R.string.steps_add_hint);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.steps_add_manual).setView(box)
                .setPositiveButton(R.string.add, (d, w) -> {
                    int n;
                    try {
                        n = Integer.parseInt(et.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        return;
                    }
                    if (n <= 0 || n > 50000) {
                        Ui.snack(b.getRoot(), getString(R.string.steps_add_invalid));
                        return;
                    }
                    graph().runIo(() -> graph().steps.addSteps(uid, DateUtil.today(), n, "manual"));
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    public static Intent intent(android.content.Context c) {
        return new Intent(c, StepsActivity.class);
    }
}
