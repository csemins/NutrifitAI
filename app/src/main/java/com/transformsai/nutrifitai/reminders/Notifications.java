package com.transformsai.nutrifitai.reminders;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;

public final class Notifications {
    public static final String CH_HYDRATION = "hydration";
    public static final String CH_MEALS = "meal_reminders";
    public static final String CH_EXPORT = "exports";
    public static final String CH_TRAINING = "training";
    /** Friends, challenges, messages and calls. */
    public static final String CH_SOCIAL = "social";
    public static final int ID_TRAINING = 1005;
    public static final int ID_WATER = 1001;
    public static final int ID_MEAL = 1002;
    public static final int ID_EXPORT = 1003;
    public static final int ID_EXPORT_DONE = 1004;

    private Notifications() {
    }

    public static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel water = new NotificationChannel(CH_HYDRATION, c.getString(R.string.ch_hydration), NotificationManager.IMPORTANCE_DEFAULT);
        water.setDescription(c.getString(R.string.ch_hydration_desc));
        NotificationChannel meals = new NotificationChannel(CH_MEALS, c.getString(R.string.ch_meals), NotificationManager.IMPORTANCE_DEFAULT);
        meals.setDescription(c.getString(R.string.ch_meals_desc));
        NotificationChannel export = new NotificationChannel(CH_EXPORT, c.getString(R.string.ch_export), NotificationManager.IMPORTANCE_LOW);
        export.setDescription(c.getString(R.string.ch_export_desc));
        nm.createNotificationChannel(water);
        nm.createNotificationChannel(meals);
        nm.createNotificationChannel(export);
        NotificationChannel training = new NotificationChannel(CH_TRAINING, c.getString(R.string.home_training), NotificationManager.IMPORTANCE_DEFAULT);
        training.setDescription(c.getString(R.string.training_reminder));
        nm.createNotificationChannel(training);
        NotificationChannel social = new NotificationChannel(CH_SOCIAL, c.getString(R.string.ch_social), NotificationManager.IMPORTANCE_HIGH);
        social.setDescription(c.getString(R.string.ch_social_desc));
        nm.createNotificationChannel(social);
    }

    public static boolean canPost(Context c) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }
}
