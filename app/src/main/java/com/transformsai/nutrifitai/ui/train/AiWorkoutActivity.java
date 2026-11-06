package com.transformsai.nutrifitai.ui.train;

import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.repo.WorkoutRepository;
import com.transformsai.nutrifitai.databinding.ActivityAiWorkoutBinding;
import com.transformsai.nutrifitai.databinding.ItemStepBinding;
import com.transformsai.nutrifitai.fitness.Exercise;
import com.transformsai.nutrifitai.fitness.ExerciseLibrary;
import com.transformsai.nutrifitai.fitness.Muscles;
import com.transformsai.nutrifitai.fitness.WorkoutPlan;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.common.AiAccessSheet;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Full-screen AI workout builder (replaces the old half-height sheet): duration, focus, intensity,
 * tap-to-target muscles, warm-up / cool-down / low-impact / camera-only options and notes. The
 * generated plan is previewed inline with its muscle map and steps; Generate / Start stay pinned at
 * the bottom so nothing is ever hidden behind a sheet or the keyboard.
 */
public class AiWorkoutActivity extends BaseActivity {
    private static final int[] MINUTES = {10, 15, 20, 30, 45};
    private static final String[] FOCUS = {"full body", "core", "legs", "upper body", "glutes", "cardio", "mobility"};
    private static final int[] FOCUS_LABEL = {R.string.focus_full, R.string.focus_core, R.string.focus_legs,
            R.string.focus_upper, R.string.focus_glutes, R.string.focus_cardio, R.string.focus_mobility};
    private static final String[] INTENSITY = {"easy", "moderate", "hard"};
    private static final int[] INTENSITY_LABEL = {R.string.intensity_easy, R.string.intensity_moderate, R.string.intensity_hard};

    private ActivityAiWorkoutBinding b;
    @Nullable private WorkoutPlan plan;
    private boolean busy;

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityAiWorkoutBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        AiAccessSheet.attach(this);
        InsetsUtil.padding(b.bar.getRoot(), true, false);
        InsetsUtil.padding(b.actions, false, true);
        b.bar.title.setText(R.string.ai_workout_title);
        b.bar.back.setOnClickListener(v -> finish());
        String level = graph().prefs.fitnessLevel();
        for (int i = 0; i < MINUTES.length; i++) add(b.minutes, getString(R.string.chip_minutes, MINUTES[i]), MINUTES[i], i == 1);
        for (int i = 0; i < FOCUS.length; i++) add(b.focus, getString(FOCUS_LABEL[i]), FOCUS[i], i == 0);
        int defIntensity = "advanced".equals(level) ? 2 : "intermediate".equals(level) ? 1 : 0;
        for (int i = 0; i < INTENSITY.length; i++) add(b.intensity, getString(INTENSITY_LABEL[i]), INTENSITY[i], i == defIntensity);
        b.muscleMap.setSelectable(true, this::onMuscles);
        b.pickList.setOnClickListener(v -> pickFromList());
        Ui.onClick(b.generate, v -> generate());
        Ui.onClick(b.regenerate, v -> generate());
        b.details.setOnClickListener(v -> {
            if (plan != null) startActivity(WorkoutDetailActivity.intent(this, plan.id));
        });
        Ui.onClick(b.startPlan, v -> {
            if (plan != null) startActivity(WorkoutActivity.intent(this, plan.id, graph().prefs.workoutCamera()));
        });
        if (s != null) {
            List<String> sel = s.getStringArrayList("muscles");
            if (sel != null) {
                b.muscleMap.setSelected(new java.util.HashSet<>(sel));
                onMuscles(b.muscleMap.selected());
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (b != null) out.putStringArrayList("muscles", new ArrayList<>(b.muscleMap.selected()));
    }

    private void add(ChipGroup g, String text, Object tag, boolean checked) {
        Chip c = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
        c.setId(View.generateViewId());
        c.setText(text);
        c.setTag(tag);
        c.setCheckable(true);
        c.setCheckedIconVisible(false);
        c.setChipBackgroundColorResource(R.color.chip_bg);
        c.setChipStrokeColorResource(R.color.chip_stroke);
        c.setChipStrokeWidth(getResources().getDisplayMetrics().density);
        c.setTextColor(getColorStateList(R.color.chip_text));
        g.addView(c);
        c.setChecked(checked);
    }

    private Object checked(ChipGroup g, Object fallback) {
        Chip c = g.findViewById(g.getCheckedChipId());
        return c == null ? fallback : c.getTag();
    }

    private void onMuscles(Set<String> sel) {
        if (sel.isEmpty()) b.muscleHint.setText(R.string.ai_target_hint);
        else {
            List<String> names = new ArrayList<>();
            for (String m : sel) names.add(Muscles.label(m));
            b.muscleHint.setText(getString(R.string.ai_target_selected, String.join(", ", names)));
        }
    }

    /** Accessible alternative to tapping shapes: a checklist of muscle groups. */
    private void pickFromList() {
        List<String> ids = Muscles.all();
        String[] labels = new String[ids.size()];
        boolean[] on = new boolean[ids.size()];
        Set<String> sel = b.muscleMap.selected();
        for (int i = 0; i < ids.size(); i++) {
            labels[i] = Muscles.label(ids.get(i));
            on[i] = sel.contains(ids.get(i));
        }
        new MaterialAlertDialogBuilder(this).setTitle(R.string.ai_target_muscles)
                .setMultiChoiceItems(labels, on, (d, w, checked) -> on[w] = checked)
                .setPositiveButton(R.string.done, (d, w) -> {
                    Set<String> out = new java.util.HashSet<>();
                    for (int i = 0; i < ids.size(); i++) if (on[i]) out.add(ids.get(i));
                    b.muscleMap.setSelected(out);
                    onMuscles(out);
                })
                .setNeutralButton(R.string.clear, (d, w) -> {
                    b.muscleMap.setSelected(new java.util.HashSet<>());
                    onMuscles(b.muscleMap.selected());
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void generate() {
        if (busy) return;
        WorkoutRepository.AiRequest r = new WorkoutRepository.AiRequest();
        r.minutes = (int) checked(b.minutes, 15);
        r.focus = (String) checked(b.focus, "full body");
        r.intensity = (String) checked(b.intensity, "moderate");
        r.level = graph().prefs.fitnessLevel();
        r.notes = b.notes.getText() == null ? null : b.notes.getText().toString();
        r.targetMuscles.addAll(b.muscleMap.selected());
        r.warmup = b.warmup.isChecked();
        r.cooldown = b.cooldown.isChecked();
        r.lowImpact = b.lowImpact.isChecked();
        r.cameraOnly = true;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(b.notes.getWindowToken(), 0);
        setBusy(true);
        b.error.setVisibility(View.GONE);
        graph().workouts.generateAiPlan(r, res -> {
            if (isFinishing() || b == null) return;
            setBusy(false);
            if (res.ok()) show(res.value);
            else {
                b.error.setText(res.error + "\n" + getString(R.string.retry_hint));
                b.error.setVisibility(View.VISIBLE);
                b.scroll.post(() -> b.scroll.smoothScrollTo(0, b.error.getTop()));
            }
        });
    }

    private void setBusy(boolean on) {
        busy = on;
        b.progress.setVisibility(on ? View.VISIBLE : View.GONE);
        b.generate.setEnabled(!on);
        b.regenerate.setEnabled(!on);
        b.generate.setText(on ? R.string.generating : plan == null ? R.string.generate : R.string.ai_regenerate);
    }

    private void show(WorkoutPlan p) {
        plan = p;
        graph().runIo(() -> {
            ExerciseLibrary lib = ExerciseLibrary.get(this);
            graph().main.post(() -> {
                if (b == null) return;
                b.result.setVisibility(View.VISIBLE);
                b.planTitle.setText(p.title);
                b.planSummary.setText(p.summary);
                b.planSummary.setVisibility(p.summary == null || p.summary.isEmpty() ? View.GONE : View.VISIBLE);
                b.planMeta.setText(getString(R.string.ai_plan_meta, Math.max(1, Math.round(p.estimatedSeconds(lib) / 60f)),
                        p.steps.size(), p.cameraSteps(lib)));
                b.planMuscles.setIntensities(Muscles.planned(p, lib));
                b.planSteps.removeAllViews();
                String lastPhase = null;
                for (WorkoutPlan.Step st : p.steps) {
                    Exercise x = lib.byId(st.exerciseId);
                    if (x == null) continue;
                    if (!st.phase.equals(lastPhase)) {
                        lastPhase = st.phase;
                        android.widget.TextView h = new android.widget.TextView(this, null, 0, R.style.NL_Text_Overline);
                        h.setText("warmup".equals(st.phase) ? R.string.phase_warmup : "cooldown".equals(st.phase)
                                ? R.string.phase_cooldown : R.string.phase_main);
                        h.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);
                        b.planSteps.addView(h);
                    }
                    ItemStepBinding sb = ItemStepBinding.inflate(getLayoutInflater(), b.planSteps, false);
                    sb.name.setText(x.name);
                    String amount = x.isHold() ? getString(R.string.step_secs, st.target) : getString(R.string.step_reps, st.target);
                    sb.sub.setText(st.restSeconds > 0 ? amount + " · " + getString(R.string.step_rest, st.restSeconds) : amount);
                    sb.image.setExercise(x, false);
                    sb.camera.setVisibility(x.camera ? View.VISIBLE : View.GONE);
                    sb.getRoot().setOnClickListener(v -> startActivity(ExerciseDetailActivity.intent(this, x.id)));
                    b.planSteps.addView(sb.getRoot());
                }
                b.generate.setText(R.string.ai_regenerate);
                b.startPlan.setVisibility(View.VISIBLE);
                b.scroll.post(() -> b.scroll.smoothScrollTo(0, b.result.getTop() - (int) (12 * getResources().getDisplayMetrics().density)));
            });
        });
    }
}
