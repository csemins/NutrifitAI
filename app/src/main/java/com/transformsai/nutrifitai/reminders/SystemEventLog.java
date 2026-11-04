package com.transformsai.nutrifitai.reminders;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The last system broadcasts the app received and what it did about each one (Settings > System events), so the
 * reaction to airplane mode, battery saver and low battery can be seen and demonstrated.
 */
public final class SystemEventLog {
    private static final String PREFS = "system_events", KEY = "log";
    private static final int MAX = 30;

    public static final class Entry {
        public final long at;
        public final String event, action;

        Entry(long at, String event, String action) {
            this.at = at;
            this.event = event;
            this.action = action;
        }
    }

    private SystemEventLog() { }

    public static synchronized void record(Context c, SystemEventsReceiver.Event e, String action) {
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray old = new JSONArray(sp.getString(KEY, "[]"));
            JSONArray out = new JSONArray();
            out.put(new JSONObject().put("at", System.currentTimeMillis()).put("event", e.name()).put("action", action));
            for (int i = 0; i < old.length() && out.length() < MAX; i++) out.put(old.get(i));
            sp.edit().putString(KEY, out.toString()).apply();
        } catch (Exception ignored) {
            sp.edit().remove(KEY).apply(); // unreadable: start over
        }
    }

    public static synchronized List<Entry> entries(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Entry(o.getLong("at"), o.getString("event"), o.getString("action")));
            }
        } catch (Exception ignored) {
            // empty log
        }
        return out;
    }

    public static void clear(Context c) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
