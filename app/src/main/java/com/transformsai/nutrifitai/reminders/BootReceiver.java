package com.transformsai.nutrifitai.reminders;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.transformsai.nutrifitai.sync.SyncScheduler;

/** Alarms do not survive a reboot or app update: restore reminder schedules and pending sync. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            ReminderScheduler.rescheduleAsync(context);
            SyncScheduler.requestSync(context);
        }
    }
}
