package com.transformsai.nutrifitai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.transformsai.nutrifitai.data.db.entity.ReminderSettingsEntity;
import com.transformsai.nutrifitai.reminders.ReminderPlanner;
import com.transformsai.nutrifitai.sync.ConflictResolver;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

public class SyncAndReminderTest {
    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");

    @Test
    public void conflictResolution() {
        assertTrue(ConflictResolver.applyRemote(null, false, 5));        // new record
        assertTrue(ConflictResolver.applyRemote(4L, false, 5));          // remote newer
        assertFalse(ConflictResolver.applyRemote(6L, false, 5));         // local newer
        assertFalse(ConflictResolver.applyRemote(5L, false, 5));         // same version: idempotent no-op
        assertFalse(ConflictResolver.applyRemote(5L, true, 5));          // pending local edit wins ties
        assertTrue(ConflictResolver.applyRemote(4L, true, 9));           // newer remote (e.g. tombstone) beats stale pending
    }

    private static ReminderSettingsEntity water(int start, int end, int interval) {
        ReminderSettingsEntity s = new ReminderSettingsEntity();
        s.waterEnabled = true;
        s.startMin = start;
        s.endMin = end;
        s.intervalMin = interval;
        return s;
    }

    @Test
    public void nextWaterReminderWithinActiveHours() {
        ReminderSettingsEntity s = water(8 * 60, 20 * 60, 120);
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 5, 9, 15, 0, 0, DHAKA);
        assertEquals(ZonedDateTime.of(2026, 11, 5, 10, 0, 0, 0, DHAKA), ReminderPlanner.nextWater(s, now));
        ZonedDateTime late = ZonedDateTime.of(2026, 11, 5, 20, 30, 0, 0, DHAKA);
        assertEquals(ZonedDateTime.of(2026, 11, 6, 8, 0, 0, 0, DHAKA), ReminderPlanner.nextWater(s, late));
    }

    @Test
    public void eveningCutoffShortensWindow() {
        ReminderSettingsEntity s = water(8 * 60, 22 * 60, 60);
        s.cutoffEnabled = true;
        s.cutoffMin = 19 * 60;
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 5, 19, 30, 0, 0, DHAKA);
        assertEquals(ZonedDateTime.of(2026, 11, 6, 8, 0, 0, 0, DHAKA), ReminderPlanner.nextWater(s, now));
        assertEquals(19 * 60, ReminderPlanner.effectiveEnd(s));
    }

    @Test
    public void disabledOrInvalidMeansNoAlarm() {
        ReminderSettingsEntity s = water(8 * 60, 20 * 60, 120);
        s.waterEnabled = false;
        assertNull(ReminderPlanner.nextWater(s, ZonedDateTime.now(DHAKA)));
        ReminderSettingsEntity bad = water(20 * 60, 8 * 60, 120);
        assertFalse(ReminderPlanner.valid(bad));
        assertNull(ReminderPlanner.nextWater(bad, ZonedDateTime.now(DHAKA)));
        assertFalse(ReminderPlanner.valid(water(8 * 60, 20 * 60, 10)));
    }

    @Test
    public void nextMealPicksEarliestUpcoming() {
        ReminderSettingsEntity s = new ReminderSettingsEntity();
        s.mealEnabled = true;
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 5, 12, 0, 0, 0, DHAKA);
        Object[] next = ReminderPlanner.nextMeal(s, now);
        assertEquals("lunch", next[1]);
        Object[] night = ReminderPlanner.nextMeal(s, ZonedDateTime.of(2026, 11, 5, 23, 0, 0, 0, DHAKA));
        assertEquals("breakfast", night[1]);
        assertEquals(6, ((ZonedDateTime) night[0]).getDayOfMonth());
    }
}
