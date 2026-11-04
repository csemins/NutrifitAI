package com.transformsai.nutrifitai.reminders;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.transformsai.nutrifitai.App;

/**
 * Clock or timezone changed: reminder times are local wall-clock times, so recompute them, and
 * refresh "today" for any visible screen. Stored records keep their original local date.
 */
public class TimeChangeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_TIMEZONE_CHANGED.equals(a) || Intent.ACTION_TIME_CHANGED.equals(a)) {
            java.util.TimeZone.setDefault(null);
            com.transformsai.nutrifitai.util.DateUtil.setClock(java.time.Clock.systemDefaultZone());
            ReminderScheduler.rescheduleAsync(context);
            App.graph().main.post(() -> App.graph().today.refresh());
        }
    }
}
