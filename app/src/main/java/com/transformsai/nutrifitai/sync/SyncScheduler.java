package com.transformsai.nutrifitai.sync;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** WorkManager scheduling for durable, network-constrained sync with exponential backoff. */
public final class SyncScheduler {
    public static final String UNIQUE = "nutrifit-sync";
    public static final String PERIODIC = "nutrifit-sync-periodic";

    private SyncScheduler() {
    }

    private static Constraints net() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }

    public static void requestSync(Context c) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(net())
                .setInitialDelay(1, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(UNIQUE)
                .build();
        WorkManager.getInstance(c).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req);
    }

    /** For frequent small writes (step counts): one upload a few minutes later instead of one per write. */
    public static void requestSyncSoon(Context c) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(net())
                .setInitialDelay(3, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(UNIQUE)
                .build();
        WorkManager.getInstance(c).enqueueUniqueWork(UNIQUE + "-soon", ExistingWorkPolicy.KEEP, req);
    }

    public static void schedulePeriodic(Context c) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(SyncWorker.class, 6, TimeUnit.HOURS)
                .setConstraints(net())
                .build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    public static LiveData<List<WorkInfo>> observe(Context c) {
        return WorkManager.getInstance(c).getWorkInfosForUniqueWorkLiveData(UNIQUE);
    }

    public static void cancelAll(Context c) {
        WorkManager.getInstance(c).cancelUniqueWork(UNIQUE);
        WorkManager.getInstance(c).cancelUniqueWork(PERIODIC);
    }
}
