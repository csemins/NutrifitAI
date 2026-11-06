package com.transformsai.nutrifitai.ui.train;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.WorkoutPlanEntity;
import com.transformsai.nutrifitai.databinding.ActivityExerciseDetailBinding;
import com.transformsai.nutrifitai.fitness.Exercise;
import com.transformsai.nutrifitai.fitness.ExerciseLibrary;
import com.transformsai.nutrifitai.fitness.Programs;
import com.transformsai.nutrifitai.fitness.WorkoutPlan;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Ids;

import java.util.Locale;

/** One exercise: looping demo, muscles, form tips, steps, and "Try 3 sets" (voice-coached). */
public class ExerciseDetailActivity extends BaseActivity {
    private static final String EXTRA_ID = "exercise_id";
    private ActivityExerciseDetailBinding b;
    private Exercise x;

    public static Intent intent(Context c, String id) {
        return new Intent(c, ExerciseDetailActivity.class).putExtra(EXTRA_ID, id);
    }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityExerciseDetailBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.topMargin(b.back);
        InsetsUtil.padding(b.startBar, false, true);
        b.back.setOnClickListener(v -> finish());
        b.start.setEnabled(false);
        String id = getIntent().getStringExtra(EXTRA_ID);
        graph().runIo(() -> {
            Exercise e = ExerciseLibrary.get(this).byId(id);
            graph().main.post(() -> {
                if (e == null) {
                    finish();
                    return;
                }
                x = e;
                render();
            });
        });
        Ui.onClick(b.start, v -> trySets());
    }

    private void render() {
        b.image.setExercise(x, true);
        b.name.setText(x.name);
        String level = x.level == null ? "" : cap(x.level);
        String equip = x.equipment == null ? "" : cap(x.equipment);
        b.meta.setText(level.isEmpty() || equip.isEmpty() ? level + equip : getString(R.string.exercise_meta, level, equip));
        b.meta.setVisibility(b.meta.getText().length() == 0 ? View.GONE : View.VISIBLE);
        b.muscles.setText(x.muscles);
        b.muscles.setVisibility(x.muscles == null || x.muscles.isEmpty() ? View.GONE : View.VISIBLE);
        b.tips.setText(bullets(x.cues, "•  "));
        b.tipsTitle.setVisibility(x.cues.isEmpty() ? View.GONE : View.VISIBLE);
        b.tips.setVisibility(x.cues.isEmpty() ? View.GONE : View.VISIBLE);
        StringBuilder st = new StringBuilder();
        for (int i = 0; i < x.steps.size(); i++) st.append(i + 1).append(".  ").append(x.steps.get(i)).append(i < x.steps.size() - 1 ? "\n" : "");
        b.steps.setText(st);
        b.stepsTitle.setVisibility(x.steps.isEmpty() ? View.GONE : View.VISIBLE);
        b.source.setVisibility(View.GONE);
        b.start.setEnabled(true);
    }

    private static String bullets(java.util.List<String> l, String p) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < l.size(); i++) s.append(p).append(l.get(i)).append(i < l.size() - 1 ? "\n" : "");
        return s.toString();
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1);
    }

    /** Saves a 3-set single-exercise plan (so the session player can load it) and starts it. */
    private void trySets() {
        WorkoutPlan p = Programs.single(x, 3);
        graph().runIo(() -> {
            String uid = graph().session.requireUid();
            WorkoutPlanEntity e = new WorkoutPlanEntity();
            e.id = "single_" + Ids.newId();
            e.uid = uid;
            e.title = x.name;
            e.source = "user";
            e.level = "beginner";
            e.stepsJson = p.stepsJson();
            e.createdAt = DateUtil.nowMillis();
            e.updatedAt = e.createdAt;
            e.deleted = true; // a transient plan: kept out of "Your AI plans" and never synced as a saved plan
            graph().db.workouts().upsertPlan(e);
            graph().main.post(() -> startActivity(WorkoutActivity.intent(this, e.id, x.camera && graph().prefs.workoutCamera())));
        });
    }
}
