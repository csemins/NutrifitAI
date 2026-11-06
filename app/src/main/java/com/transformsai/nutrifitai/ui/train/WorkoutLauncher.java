package com.transformsai.nutrifitai.ui.train;

import android.app.Activity;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.db.entity.WorkoutPlanEntity;
import com.transformsai.nutrifitai.fitness.Exercise;
import com.transformsai.nutrifitai.fitness.Programs;
import com.transformsai.nutrifitai.fitness.WorkoutPlan;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Ids;

/** Starts a quick single-exercise session (3 sets) from the library, the camera or the coach. */
public final class WorkoutLauncher {
    private WorkoutLauncher() { }

    /**
     * Saves a transient plan (so the player can reload it after process death) and opens the player.
     * The plan is marked deleted so it never shows up as a saved plan or syncs as one.
     */
    public static void startSingle(Activity a, Exercise x, int sets, boolean camera, boolean finishCaller) {
        AppGraph g = App.graph();
        WorkoutPlan p = Programs.single(x, sets);
        g.runIo(() -> {
            String uid = g.session.requireUid();
            WorkoutPlanEntity e = new WorkoutPlanEntity();
            e.id = "single_" + Ids.newId();
            e.uid = uid;
            e.title = x.name;
            e.source = "user";
            e.level = g.prefs.fitnessLevel();
            e.stepsJson = p.stepsJson();
            e.createdAt = DateUtil.nowMillis();
            e.updatedAt = e.createdAt;
            e.deleted = true;
            g.db.workouts().upsertPlan(e);
            g.main.post(() -> {
                if (a.isFinishing()) return;
                a.startActivity(WorkoutActivity.intent(a, e.id, camera && x.camera));
                if (finishCaller) a.finish();
            });
        });
    }
}
