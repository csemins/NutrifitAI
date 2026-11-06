package com.transformsai.nutrifitai.ui.train;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.repo.WorkoutRepository;
import com.transformsai.nutrifitai.databinding.ActivityWorkoutDetailBinding;
import com.transformsai.nutrifitai.databinding.ItemStepBinding;
import com.transformsai.nutrifitai.fitness.Exercise;
import com.transformsai.nutrifitai.fitness.ExerciseLibrary;
import com.transformsai.nutrifitai.fitness.WorkoutMath;
import com.transformsai.nutrifitai.fitness.WorkoutPlan;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Plan overview: duration, exercises with photos, camera/voice switches and Start (explicit intent). */
public class WorkoutDetailActivity extends BaseActivity {
    public static final String EXTRA_PLAN = "plan_id";
    private ActivityWorkoutDetailBinding b;
    private WorkoutPlan plan;
    private ExerciseLibrary lib;

    public static Intent intent(Context c, String planId) {
        return new Intent(c, WorkoutDetailActivity.class).putExtra(EXTRA_PLAN, planId);
    }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityWorkoutDetailBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.bar.getRoot(), true, false);
        InsetsUtil.padding(b.startBar, false, true);
        b.bar.back.setOnClickListener(v -> finish());
        b.cameraSwitch.setChecked(graph().prefs.workoutCamera());
        b.voiceSwitch.setChecked(graph().prefs.voiceCoach());
        b.cameraSwitch.setOnCheckedChangeListener((v, on) -> graph().prefs.setWorkoutCamera(on));
        b.voiceSwitch.setOnCheckedChangeListener((v, on) -> graph().prefs.setVoiceCoach(on));
        b.start.setEnabled(false);
        String id = getIntent().getStringExtra(EXTRA_PLAN);
        graph().runIo(() -> {
            lib = ExerciseLibrary.get(this);
            graph().main.post(() -> graph().workouts.loadPlan(id, r -> {
                if (!r.ok()) {
                    Ui.snack(b.getRoot(), r.error);
                    return;
                }
                plan = r.value;
                render();
            }));
        });
        Ui.onClick(b.start, v -> {
            if (plan == null) return;
            startActivity(WorkoutActivity.intent(this, plan.id, b.cameraSwitch.isChecked()));
        });
    }

    private void render() {
        b.bar.title.setText(plan.title);
        b.planTitle.setText(plan.title);
        b.hero.setIntensities(com.transformsai.nutrifitai.fitness.Muscles.planned(plan, lib));
        b.summary.setText(plan.summary);
        b.summary.setVisibility(plan.summary.isEmpty() ? View.GONE : View.VISIBLE);
        int min = Math.max(1, Math.round(plan.estimatedSeconds(lib) / 60f));
        tile(b.tileMin, R.string.stat_time, getString(R.string.chip_minutes, min));
        tile(b.tileCount, R.string.plan_steps, String.valueOf(plan.steps.size()));
        double kg = 65;
        double kcal = 0;
        for (WorkoutPlan.Step st : plan.steps) {
            Exercise x = lib.byId(st.exerciseId);
            if (x != null) kcal += WorkoutMath.kcal(x.met, kg, (x.isHold() ? st.target : st.target * 3L) * 1000L);
        }
        tile(b.tileKcal, R.string.stat_kcal, "~" + Math.round(kcal));
        int tracked = plan.cameraSteps(lib);
        b.cameraSwitch.setVisibility(tracked > 0 ? View.VISIBLE : View.GONE);
        b.steps.removeAllViews();
        for (WorkoutPlan.Step st : plan.steps) {
            Exercise x = lib.byId(st.exerciseId);
            if (x == null) continue;
            ItemStepBinding sb = ItemStepBinding.inflate(getLayoutInflater(), b.steps, false);
            sb.name.setText(x.name);
            String target = x.isHold() ? getString(R.string.step_secs, st.target) : getString(R.string.step_reps, st.target);
            sb.sub.setText(st.restSeconds > 0 ? target + " · " + getString(R.string.step_rest, st.restSeconds) : target);
            sb.camera.setVisibility(x.camera ? View.VISIBLE : View.GONE);
            sb.image.setExercise(x, false);
            sb.getRoot().setContentDescription(x.name + ", " + sb.sub.getText() + (x.camera ? ", " + getString(R.string.tracked) : ""));
            sb.getRoot().setOnClickListener(v -> startActivity(ExerciseDetailActivity.intent(this, x.id)));
            b.steps.addView(sb.getRoot());
        }
        if ("ai".equals(plan.source)) {
            b.bar.action.setVisibility(View.VISIBLE);
            b.bar.action.setImageResource(R.drawable.ic_delete);
            b.bar.action.setContentDescription(getString(R.string.delete_plan));
            b.bar.action.setOnClickListener(v -> new MaterialAlertDialogBuilder(this).setTitle(R.string.delete_plan)
                    .setMessage(plan.title).setPositiveButton(R.string.delete, (d, w) -> {
                        graph().workouts.deletePlan(plan.id);
                        finish();
                    }).setNegativeButton(R.string.cancel, null).show());
        }
        b.start.setEnabled(!plan.steps.isEmpty());
    }

    private void tile(com.transformsai.nutrifitai.databinding.ViewInfoTileBinding t, int label, String value) {
        t.label.setText(label);
        t.value.setText(value);
        t.getRoot().setContentDescription(getString(label) + ": " + value);
    }

    /** Used by the AI sheet tests and to keep a hard reference for the linter. */
    static WorkoutPlan parse(String raw, ExerciseLibrary lib) {
        return WorkoutRepository.parseAiPlan(raw, lib);
    }
}
