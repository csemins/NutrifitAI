package com.transformsai.nutrifitai.ui.common;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import androidx.activity.result.ActivityResultCaller;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.transformsai.nutrifitai.R;

import java.util.function.Supplier;

/**
 * Runtime permissions, asked in context. A permission the user declined is asked again the next time they use
 * the feature that needs it; once Android stops showing the prompt ("Don't ask again"), a short explanation offers
 * the app's settings page instead, so a declined permission can always be recovered.
 */
public final class Permissions {
    public static final String NOTIFICATIONS = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.POST_NOTIFICATIONS : "";
    public static final String CAMERA = Manifest.permission.CAMERA;
    public static final String MIC = Manifest.permission.RECORD_AUDIO;
    public static final String ACTIVITY = Build.VERSION.SDK_INT >= 29 ? Manifest.permission.ACTIVITY_RECOGNITION : "";
    private static final String PREFS = "permissions";

    private Permissions() { }

    public static boolean granted(Context c, String perm) {
        if (perm.isEmpty()) return true; // not a runtime permission on this Android version
        return ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** Notifications can actually be shown (permission on Android 13+, and not switched off in settings). */
    public static boolean notificationsOn(Context c) {
        return granted(c, NOTIFICATIONS) && NotificationManagerCompat.from(c).areNotificationsEnabled();
    }

    static boolean asked(Context c, String perm) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(perm, false);
    }

    static void markAsked(Context c, String perm) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(perm, true).apply();
    }

    /** Asked before and Android will no longer show the prompt: only the settings page can grant it now. */
    public static boolean blocked(Activity a, String perm) {
        return !perm.isEmpty() && !granted(a, perm) && asked(a, perm) && !ActivityCompat.shouldShowRequestPermissionRationale(a, perm);
    }

    /** App settings page (implicit intent), or the notification settings for notifications. */
    public static void openSettings(Context c, String perm) {
        Intent i;
        if (perm.equals(NOTIFICATIONS) || perm.isEmpty()) {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", c.getPackageName(), null));
        }
        try {
            c.startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            Ui.openAppSettings(c);
        }
    }

    static void settingsDialog(Activity a, String perm, @StringRes int why) {
        if (a.isFinishing()) return;
        new MaterialAlertDialogBuilder(a).setTitle(R.string.perm_blocked_title)
                .setMessage(a.getString(why) + "\n\n" + a.getString(R.string.perm_blocked_body))
                .setPositiveButton(R.string.open_settings, (d, w) -> openSettings(a, perm))
                .setNegativeButton(R.string.not_now, null).show();
    }

    /**
     * One permission, asked when a feature needs it. Create it as a field (it registers an activity-result
     * launcher), then call {@link #run} with what to do once it is granted.
     */
    public static final class Asker {
        private final Supplier<Activity> activity;
        private final ActivityResultLauncher<String> launcher;
        private String perm = "";
        @StringRes private int why;
        @Nullable private Runnable onGranted, onDenied;

        public Asker(ActivityResultCaller caller, Supplier<Activity> activity) {
            this.activity = activity;
            this.launcher = caller.registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
                Activity a = activity.get();
                if (a == null) return;
                markAsked(a, perm);
                if (ok) {
                    if (onGranted != null) onGranted.run();
                } else {
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(a, perm)) settingsDialog(a, perm, why);
                    if (onDenied != null) onDenied.run();
                }
            });
        }

        /** Runs {@code granted} now, after the system prompt, or never (with a way to settings when blocked). */
        public void run(String permission, @StringRes int reason, Runnable granted) {
            run(permission, reason, granted, null);
        }

        public void run(String permission, @StringRes int reason, Runnable granted, @Nullable Runnable denied) {
            Activity a = activity.get();
            if (a == null) return;
            if (granted(a, permission)) {
                granted.run();
                return;
            }
            perm = permission;
            why = reason;
            onGranted = granted;
            onDenied = denied;
            if (blocked(a, permission)) {
                settingsDialog(a, permission, reason);
                if (denied != null) denied.run();
                return;
            }
            launcher.launch(permission);
        }
    }
}
