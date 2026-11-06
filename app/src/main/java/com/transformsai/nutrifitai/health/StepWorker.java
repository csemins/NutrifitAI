package com.transformsai.nutrifitai.health;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.repo.StepsRepository;

import java.util.concurrent.TimeUnit;

/**
 * Every ~15 minutes (WorkManager's minimum, timing is inexact under Doze): reads the step counter
 * into today's total and, when connected, pulls Health Connect steps and pushes new workouts,
 * weights and water. Cheap: one sensor reading and a few small queries.
 */
public class StepWorker extends Worker {
    public static final String UNIQUE = "nutrifit-steps";

    public StepWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    public static void schedule(Context c) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(StepWorker.class, 15, TimeUnit.MINUTES).build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    public static void cancel(Context c) {
        WorkManager.getInstance(c).cancelUniqueWork(UNIQUE);
    }

    @NonNull
    @Override
    public Result doWork() {
        AppGraph g = App.graph();
        String uid = g.session.uid();
        if (uid == null) return Result.success();
        Context c = getApplicationContext();
        if (g.prefs.stepTracking() && StepsRepository.hasPermission(c)) {
            float v = StepSensor.readOnce(c, 4000);
            if (!Float.isNaN(v)) g.steps.onCounter(uid, v, System.currentTimeMillis());
        }
        if (g.prefs.healthConnect() && HealthConnect.available(c)) {
            HealthConnect.syncBlocking(c, g, uid);
        }
        return Result.success();
    }
}
