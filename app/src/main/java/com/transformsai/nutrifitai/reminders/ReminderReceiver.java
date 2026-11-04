package com.transformsai.nutrifitai.reminders;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.ReminderSettingsEntity;
import com.transformsai.nutrifitai.data.repo.ReminderRepository;
import com.transformsai.nutrifitai.data.repo.WaterRepository;
import com.transformsai.nutrifitai.ui.main.MainActivity;
import com.transformsai.nutrifitai.util.DateUtil;

/**
 * Handles alarm broadcasts (internal, explicit, not exported): shows the reminder if it is still
 * relevant, then schedules the next one. Also handles the "+ glass" notification action.
 */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        PendingResult pending = goAsync();
        AppGraph g = App.graph();
        g.runIo(() -> {
            try {
                String uid = g.session.uid();
                if (uid == null) return;
                ReminderSettingsEntity s = g.db.reminders().get(uid);
                if (s == null) s = ReminderRepository.defaults(uid);
                String today = DateUtil.today();
                switch (action) {
                    case ReminderScheduler.ACTION_WATER: {
                        GoalEntity goal = g.db.goals().active(uid, today);
                        int drank = g.db.water().dayTotal(uid, today);
                        if (s.waterEnabled && (goal == null || drank < goal.waterMl)) {
                            showWater(context, drank, goal == null ? 0 : goal.waterMl);
                        }
                        break;
                    }
                    case ReminderScheduler.ACTION_MEAL: {
                        String meal = intent.getStringExtra(ReminderScheduler.EXTRA_MEAL);
                        if (s.mealEnabled && meal != null && g.db.diary().consumedEntryCount(uid, today, meal) == 0) {
                            showMeal(context, meal);
                        }
                        break;
                    }
                    case ReminderScheduler.ACTION_ADD_WATER: {
                        int ml = intent.getIntExtra(ReminderScheduler.EXTRA_ML, WaterRepository.GLASS_ML);
                        WaterRepository.addBlocking(g.db, g.outbox, uid, ml);
                        g.outbox.kick();
                        NotificationManagerCompat.from(context).cancel(Notifications.ID_WATER);
                        break;
                    }
                    case ReminderScheduler.ACTION_TRAINING: {
                        if (g.prefs.trainingReminder() && g.db.workouts().daily(uid, today, today).isEmpty()) {
                            showTraining(context);
                        }
                        break;
                    }
                    default:
                        break;
                }
                ReminderScheduler.reschedule(context, s);
            } finally {
                pending.finish();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private static void showWater(Context c, int drank, int goal) {
        if (!Notifications.canPost(c)) return;
        Intent add = new Intent(c, ReminderReceiver.class).setAction(ReminderScheduler.ACTION_ADD_WATER)
                .putExtra(ReminderScheduler.EXTRA_ML, WaterRepository.GLASS_ML);
        PendingIntent addPi = PendingIntent.getBroadcast(c, 21, add, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = goal > 0
                ? c.getString(R.string.notif_water_progress, drank, goal)
                : c.getString(R.string.notif_water_text);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, Notifications.CH_HYDRATION)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(c.getColor(R.color.nl_water))
                .setContentTitle(c.getString(R.string.notif_water_title))
                .setContentText(text)
                .setContentIntent(openApp(c, "hydration"))
                .setAutoCancel(true)
                .addAction(R.drawable.ic_drop, c.getString(R.string.notif_add_glass), addPi);
        NotificationManagerCompat.from(c).notify(Notifications.ID_WATER, b.build());
    }

    @SuppressLint("MissingPermission")
    private static void showMeal(Context c, String meal) {
        if (!Notifications.canPost(c)) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, Notifications.CH_MEALS)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(c.getColor(R.color.nl_lime))
                .setContentTitle(c.getString(R.string.notif_meal_title, MealLabels.label(c, meal)))
                .setContentText(c.getString(R.string.notif_meal_text))
                .setContentIntent(openApp(c, "meals"))
                .setAutoCancel(true);
        NotificationManagerCompat.from(c).notify(Notifications.ID_MEAL, b.build());
    }

    @SuppressLint("MissingPermission")
    private static void showTraining(Context c) {
        if (!Notifications.canPost(c)) return;
        com.transformsai.nutrifitai.fitness.WorkoutPlan p = com.transformsai.nutrifitai.fitness.Programs.forDay(
                java.time.LocalDate.now().getDayOfYear(), App.graph().prefs.fitnessLevel());
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, Notifications.CH_TRAINING)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(c.getColor(R.color.nl_lime))
                .setContentTitle(c.getString(R.string.notif_training_title))
                .setContentText(c.getString(R.string.notif_training_body, p.title))
                .setContentIntent(openApp(c, "train"))
                .setAutoCancel(true);
        NotificationManagerCompat.from(c).notify(Notifications.ID_TRAINING, b.build());
    }

    private static PendingIntent openApp(Context c, String dest) {
        Intent i = new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_DEST, dest)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, dest.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
