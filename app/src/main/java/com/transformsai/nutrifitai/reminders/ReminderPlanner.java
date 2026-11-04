package com.transformsai.nutrifitai.reminders;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.data.db.entity.ReminderSettingsEntity;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Pure scheduling math. Water reminders fire at start, start+interval, ... while inside active
 * hours. When the evening cut-off is enabled the window ends at min(end, cutoff).
 */
public final class ReminderPlanner {
    public static final int MIN_INTERVAL = 30;
    public static final int MAX_INTERVAL = 360;

    private ReminderPlanner() {
    }

    public static int effectiveEnd(ReminderSettingsEntity s) {
        return s.cutoffEnabled ? Math.min(s.endMin, s.cutoffMin) : s.endMin;
    }

    public static boolean valid(ReminderSettingsEntity s) {
        return s.intervalMin >= MIN_INTERVAL && s.intervalMin <= MAX_INTERVAL && s.startMin >= 0
                && s.endMin <= 24 * 60 && s.startMin < effectiveEnd(s);
    }

    @Nullable
    public static ZonedDateTime nextWater(ReminderSettingsEntity s, ZonedDateTime now) {
        if (!s.waterEnabled || !valid(s)) return null;
        int end = effectiveEnd(s);
        for (int dayOffset = 0; dayOffset < 2; dayOffset++) {
            LocalDate day = now.toLocalDate().plusDays(dayOffset);
            for (int m = s.startMin; m <= end; m += s.intervalMin) {
                ZonedDateTime t = at(day, m, now);
                if (t.isAfter(now)) return t;
            }
        }
        return null;
    }

    /** Next meal nudge and which meal it is for. */
    @Nullable
    public static Object[] nextMeal(ReminderSettingsEntity s, ZonedDateTime now) {
        if (!s.mealEnabled) return null;
        int[] mins = {s.breakfastMin, s.lunchMin, s.dinnerMin};
        String[] types = {"breakfast", "lunch", "dinner"};
        for (int dayOffset = 0; dayOffset < 2; dayOffset++) {
            LocalDate day = now.toLocalDate().plusDays(dayOffset);
            ZonedDateTime best = null;
            String bestType = null;
            for (int i = 0; i < 3; i++) {
                ZonedDateTime t = at(day, mins[i], now);
                if (t.isAfter(now) && (best == null || t.isBefore(best))) {
                    best = t;
                    bestType = types[i];
                }
            }
            if (best != null) return new Object[]{best, bestType};
        }
        return null;
    }

    private static ZonedDateTime at(LocalDate day, int minutes, ZonedDateTime now) {
        int m = Math.min(minutes, 24 * 60 - 1);
        return ZonedDateTime.of(day, LocalTime.of(m / 60, m % 60), now.getZone());
    }
}
