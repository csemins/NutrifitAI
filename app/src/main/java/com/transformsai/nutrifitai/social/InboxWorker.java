package com.transformsai.nutrifitai.social;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.transformsai.nutrifitai.App;

import java.util.concurrent.TimeUnit;

/**
 * Shows new social notifications while the app is closed. WorkManager runs it about every 15 minutes (its
 * minimum), so these arrive with that delay; while the app is open they appear at once through the listener.
 */
public class InboxWorker extends Worker {
    private static final String NAME = "inbox-check";

    public InboxWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    public static void schedule(Context c) {
        PeriodicWorkRequest r = new PeriodicWorkRequest.Builder(InboxWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, r);
    }

    public static void cancel(Context c) {
        WorkManager.getInstance(c).cancelUniqueWork(NAME);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            App.graph().inbox.checkBlocking();
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
