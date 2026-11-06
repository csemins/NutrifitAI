package com.transformsai.nutrifitai.ui.train;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.WorkoutSessionEntity;
import com.transformsai.nutrifitai.databinding.ActivityWorkoutSummaryBinding;
import com.transformsai.nutrifitai.databinding.ViewInfoTileBinding;
import com.transformsai.nutrifitai.fitness.Muscles;
import com.transformsai.nutrifitai.fitness.WorkoutMath;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * "Excellent work today": totals, a muscle heat map (which muscles were trained and how hard), the
 * camera form score and per-exercise results of a saved session, with a share chooser.
 */
public class WorkoutSummaryActivity extends BaseActivity {
    private static final String EXTRA_SESSION = "session_id";
    private ActivityWorkoutSummaryBinding b;
    private WorkoutSessionEntity s;

    public static Intent forSession(Context c, String sessionId) {
        return new Intent(c, WorkoutSummaryActivity.class).putExtra(EXTRA_SESSION, sessionId);
    }

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        if (uidOrFinish() == null) return;
        b = ActivityWorkoutSummaryBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.scroll, true, false);
        InsetsUtil.padding(b.actions, false, true);
        b.done.setOnClickListener(v -> finish());
        b.share.setOnClickListener(v -> share());
        String id = getIntent().getStringExtra(EXTRA_SESSION);
        graph().runIo(() -> {
            WorkoutSessionEntity x = graph().db.workouts().session(id);
            graph().main.post(() -> {
                if (x == null || x.deleted) {
                    finish();
                    return;
                }
                s = x;
                render();
            });
        });
    }

    private void render() {
        b.sub.setText(getString(R.string.workout_complete_sub, s.title));
        long min = Math.max(1, Math.round(s.activeMs / 60000.0));
        tile(b.tTime, R.string.stat_time, getString(R.string.chip_minutes, (int) min));
        tile(b.tReps, R.string.stat_reps, String.valueOf(s.totalReps));
        tile(b.tKcal, R.string.stat_kcal, "~" + Math.round(s.kcalEstimate));
        tile(b.tDone, R.string.stat_done, Math.round(s.completion * 100) + "%");
        renderMuscles();
        b.sets.removeAllViews();
        try {
            for (JsonElement e : JsonParser.parseString(s.setsJson).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                boolean hold = o.get("hold").getAsBoolean();
                boolean skipped = o.get("skipped").getAsBoolean();
                String value = hold
                        ? getString(R.string.set_line_hold, Math.round(o.get("held_ms").getAsLong() / 1000f), o.get("target").getAsInt())
                        : getString(R.string.set_line_reps, o.get("reps").getAsInt(), o.get("target").getAsInt());
                if (skipped) value = getString(R.string.skipped);
                b.sets.addView(row(o.get("name").getAsString(), value, o.has("camera") && o.get("camera").getAsBoolean(), skipped));
            }
        } catch (Exception ignored) {
            b.sets.setVisibility(View.GONE);
        }
    }

    @androidx.annotation.Nullable private java.util.Map<String, Float> trained;

    /** Heat map of the muscles this session trained, how hard, and the camera form score. */
    private void renderMuscles() {
        java.util.List<com.transformsai.nutrifitai.fitness.WorkoutEngine.StepResult> results = WorkoutMath.parseSets(s.setsJson);
        graph().runIo(() -> {
            com.transformsai.nutrifitai.fitness.ExerciseLibrary lib = com.transformsai.nutrifitai.fitness.ExerciseLibrary.get(this);
            java.util.Map<String, Float> m = Muscles.intensity(results, lib);
            graph().main.post(() -> {
                if (b == null) return;
                if (m.isEmpty()) {
                    b.muscleCard.setVisibility(View.GONE);
                    return;
                }
                b.muscleMap.setIntensities(m);
                trained = m;
                b.muscleSummary.setText(getString(R.string.muscles_mostly, Muscles.summary(m, 3)));
                b.muscleList.removeAllViews();
                java.util.List<java.util.Map.Entry<String, Float>> ranked = Muscles.ranked(m);
                for (int i = 0; i < Math.min(6, ranked.size()); i++) {
                    b.muscleList.addView(MuscleRows.row(this, ranked.get(i).getKey(), ranked.get(i).getValue()));
                }
                int[] form = WorkoutMath.formCounts(results);
                if (form[0] + form[1] > 0) {
                    int pct = Math.round(100f * form[0] / (form[0] + form[1]));
                    b.formScore.setText(form[1] == 0 ? getString(R.string.form_perfect, pct)
                            : getString(R.string.form_score, pct, form[1]));
                    b.formScore.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private View row(String name, String value, boolean camera, boolean skipped) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setMinimumHeight((int) (44 * getResources().getDisplayMetrics().density));
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView n = new TextView(this, null, 0, R.style.NL_Text_Body);
        n.setText(camera ? name + "  ·  " + getString(R.string.tracked) : name);
        n.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView v = new TextView(this, null, 0, R.style.NL_Text_BodyStrong);
        v.setText(value);
        v.setTextColor(ContextCompat.getColor(this, skipped ? R.color.nl_text_3 : R.color.nl_lime));
        l.addView(n);
        l.addView(v);
        l.setContentDescription(name + ", " + value);
        return l;
    }

    private void tile(ViewInfoTileBinding t, int label, String value) {
        t.label.setText(label);
        t.value.setText(value);
        t.getRoot().setContentDescription(getString(label) + ": " + value);
    }

    /** Implicit intent: shares an image card of this workout through Android's chooser. */
    private void share() {
        if (s == null) return;
        com.transformsai.nutrifitai.ui.common.ShareCards.Card c = new com.transformsai.nutrifitai.ui.common.ShareCards.Card();
        c.kicker = getString(R.string.share_kicker_workout);
        c.title = s.title;
        CharSequence muscles = b.muscleSummary.getText();
        if (b.muscleCard.getVisibility() == View.VISIBLE && muscles.length() > 0) c.subtitle = muscles.toString();
        if (b.formScore.getVisibility() == View.VISIBLE) c.caption = b.formScore.getText().toString();
        c.art = R.drawable.ic3d_fit;
        c.fitArt = true;
        c.muscles = trained; // the body map of what was trained, when known
        c.stat(String.valueOf((int) Math.max(1, Math.round(s.activeMs / 60000.0))), getString(R.string.unit_min))
                .stat(String.valueOf(s.totalReps), getString(R.string.reps_label))
                .stat(String.valueOf(s.exercisesDone), getString(R.string.exercises_label))
                .stat(Ui.kcal(Math.round(s.kcalEstimate)), getString(R.string.unit_kcal));
        com.transformsai.nutrifitai.ui.common.ShareCards.share(this, c, getString(R.string.share_workout));
    }
}
