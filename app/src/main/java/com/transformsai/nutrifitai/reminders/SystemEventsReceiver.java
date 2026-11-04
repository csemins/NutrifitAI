package com.transformsai.nutrifitai.reminders;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

/**
 * System broadcasts the app reacts to while it is on screen (registered at runtime, so no manifest entry):
 * airplane mode on/off, battery low/okay and battery-saver changes. The listener tells the user what changes
 * (offline logging, sync resuming, lighter camera model) and restarts sync when the phone is back online.
 */
public class SystemEventsReceiver extends BroadcastReceiver {
    public enum Event { AIRPLANE_ON, AIRPLANE_OFF, BATTERY_LOW, BATTERY_OKAY, SAVER_ON, SAVER_OFF }

    public interface Listener {
        void onSystemEvent(Event e);
    }

    private final Listener listener;

    public SystemEventsReceiver(Listener listener) {
        this.listener = listener;
    }

    public void register(Context c) {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        f.addAction(Intent.ACTION_BATTERY_LOW);
        f.addAction(Intent.ACTION_BATTERY_OKAY);
        f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        ContextCompat.registerReceiver(c, this, f, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    public void unregister(Context c) {
        try {
            c.unregisterReceiver(this);
        } catch (IllegalArgumentException ignored) {
            // was not registered
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        switch (a) {
            case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                listener.onSystemEvent(i.getBooleanExtra("state", airplaneOn(c)) ? Event.AIRPLANE_ON : Event.AIRPLANE_OFF);
                break;
            case Intent.ACTION_BATTERY_LOW:
                listener.onSystemEvent(Event.BATTERY_LOW);
                break;
            case Intent.ACTION_BATTERY_OKAY:
                listener.onSystemEvent(Event.BATTERY_OKAY);
                break;
            case PowerManager.ACTION_POWER_SAVE_MODE_CHANGED:
                listener.onSystemEvent(powerSaving(c) ? Event.SAVER_ON : Event.SAVER_OFF);
                break;
            default:
                break;
        }
    }

    public static boolean airplaneOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }

    /** Battery saver on, or battery at 15% or less and not charging: the workout camera uses the lighter pose model. */
    public static boolean powerSaving(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm != null && pm.isPowerSaveMode()) return true;
        BatteryManager bm = c.getSystemService(BatteryManager.class);
        if (bm == null || bm.isCharging()) return false;
        int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return pct > 0 && pct <= 15;
    }
}
