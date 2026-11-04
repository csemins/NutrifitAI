package com.transformsai.nutrifitai.reminders;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.data.db.entity.ReminderSettingsEntity;
import com.transformsai.nutrifitai.data.repo.ReminderRepository;

import java.time.ZonedDateTime;

/**
 * Schedules the next reminder with inexact AlarmManager alarms (setAndAllowWhileIdle). We do not
 * request SCHEDULE_EXACT_ALARM: a hydration nudge a few minutes late is fine and saves battery.
 * Alarms are cleared by reboot, so BootReceiver/TimeChangeReceiver call {@link #rescheduleAsync}.
 */
public final class ReminderScheduler {
    public static final String ACTION_WATER = "com.transformsai.nutrifitai.action.WATER_REMINDER";
    public static final String ACTION_MEAL = "com.transformsai.nutrifitai.action.MEAL_REMINDER";
    public static final String ACTION_ADD_WATER = "com.transformsai.nutrifitai.action.ADD_WATER";
    public static final String ACTION_TRAINING = "com.transformsai.nutrifitai.action.TRAINING_REMINDER";
    public static final String EXTRA_MEAL = "meal_type";
    public static final String EXTRA_ML = "ml";
    private static final int RC_WATER = 11;
    private static final int RC_MEAL = 12;
    private static final int RC_TRAINING = 13;

    private ReminderScheduler() {
    }

    public static void rescheduleAsync(Context c) {
        App.graph().runIo(() -> {
            String uid = App.graph().session.uid();
            if (uid == null) {
                cancelAll(c);
                return;
            }
            ReminderSettingsEntity s = App.graph().db.reminders().get(uid);
            reschedule(c, s == null ? ReminderRepository.defaults(uid) : s);
        });
    }

    public static void reschedule(Context c, ReminderSettingsEntity s) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime water = ReminderPlanner.nextWater(s, now);
        PendingIntent wpi = pi(c, ACTION_WATER, RC_WATER, null);
        am.cancel(wpi);
        if (water != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, water.toInstant().toEpochMilli(), wpi);

        Object[] meal = ReminderPlanner.nextMeal(s, now);
        PendingIntent cancelMeal = pi(c, ACTION_MEAL, RC_MEAL, null);
        am.cancel(cancelMeal);
        if (meal != null) {
            PendingIntent mpi = pi(c, ACTION_MEAL, RC_MEAL, (String) meal[1]);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ((ZonedDateTime) meal[0]).toInstant().toEpochMilli(), mpi);
        }
        scheduleTraining(c, now);
    }

    /** Daily training reminder at the time chosen on the Train tab (inexact, like the others). */
    public static void scheduleTraining(Context c, ZonedDateTime now) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent tpi = pi(c, ACTION_TRAINING, RC_TRAINING, null);
        am.cancel(tpi);
        com.transformsai.nutrifitai.data.Prefs prefs = App.graph().prefs;
        if (!prefs.trainingReminder()) return;
        ZonedDateTime next = nextTraining(prefs.trainingMinute(), now);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), tpi);
    }

    /** Next occurrence of minuteOfDay strictly after now (today or tomorrow, DST-safe via ZonedDateTime). */
    public static ZonedDateTime nextTraining(int minuteOfDay, ZonedDateTime now) {
        ZonedDateTime t = now.toLocalDate().atStartOfDay(now.getZone()).plusMinutes(minuteOfDay);
        return t.isAfter(now) ? t : now.toLocalDate().plusDays(1).atStartOfDay(now.getZone()).plusMinutes(minuteOfDay);
    }

    public static void cancelAll(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(pi(c, ACTION_WATER, RC_WATER, null));
        am.cancel(pi(c, ACTION_MEAL, RC_MEAL, null));
        am.cancel(pi(c, ACTION_TRAINING, RC_TRAINING, null));
    }

    static PendingIntent pi(Context c, String action, int rc, String meal) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(action);
        if (meal != null) i.putExtra(EXTRA_MEAL, meal);
        return PendingIntent.getBroadcast(c, rc, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
