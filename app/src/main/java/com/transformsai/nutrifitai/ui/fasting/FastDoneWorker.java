package com.transformsai.nutrifitai.ui.fasting;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.FastEntity;
import com.transformsai.nutrifitai.reminders.Notifications;

import java.util.concurrent.TimeUnit;

/** One notification when a running fast reaches its target (inexact under Doze, documented). */
public class FastDoneWorker extends Worker {
    private static final String UNIQUE = "nutrifit-fast-done";

    public FastDoneWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    public static void schedule(Context c, FastEntity f) {
        long delay = Math.max(0, f.startAt + f.targetHours * 3_600_000L - System.currentTimeMillis());
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(FastDoneWorker.class)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(new Data.Builder().putString("id", f.id).putInt("hours", f.targetHours).build())
                .build();
        WorkManager.getInstance(c).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, req);
    }

    public static void cancel(Context c) {
        WorkManager.getInstance(c).cancelUniqueWork(UNIQUE);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        String id = getInputData().getString("id");
        FastEntity f = id == null ? null : App.graph().db.activity().fast(id);
        if (f == null || !f.running() || !Notifications.canPost(c)) return Result.success();
        Notifications.createChannels(c);
        PendingIntent open = PendingIntent.getActivity(c, 21, new Intent(c, FastingActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            NotificationManagerCompat.from(c).notify(1011, new NotificationCompat.Builder(c, Notifications.CH_MEALS)
                    .setSmallIcon(R.drawable.ic_moon)
                    .setContentTitle(c.getString(R.string.fasting_done_title))
                    .setContentText(c.getString(R.string.fasting_done_body, f.targetHours))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build());
        } catch (SecurityException ignored) {
            // permission revoked
        }
        return Result.success();
    }
}
