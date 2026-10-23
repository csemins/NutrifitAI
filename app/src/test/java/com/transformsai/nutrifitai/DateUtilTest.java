package com.transformsai.nutrifitai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.transformsai.nutrifitai.util.DateUtil;

import org.junit.After;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

public class DateUtilTest {
    @After
    public void reset() {
        DateUtil.setClock(Clock.systemDefaultZone());
    }

    @Test
    public void todayFollowsLocalZoneAcrossMidnight() {
        // 2026-11-05T18:30Z is 00:30 on Nov 6 in Dhaka (UTC+6) but still Nov 5 in London.
        Instant t = Instant.parse("2026-11-05T18:30:00Z");
        DateUtil.setClock(Clock.fixed(t, ZoneId.of("Asia/Dhaka")));
        assertEquals("2026-11-06", DateUtil.today());
        DateUtil.setClock(Clock.fixed(t, ZoneId.of("Europe/London")));
        assertEquals("2026-11-05", DateUtil.today());
    }

    @Test
    public void storedDateIsStableAfterTimezoneChange() {
        long loggedAt = Instant.parse("2026-11-05T17:50:00Z").toEpochMilli(); // 23:50 Dhaka
        String loggedDate = DateUtil.dateOf(loggedAt, ZoneId.of("Asia/Dhaka"));
        assertEquals("2026-11-05", loggedDate);
        // Same instant viewed after flying to Tokyo would be Nov 6 — but the stored date is kept.
        assertEquals("2026-11-06", DateUtil.dateOf(loggedAt, ZoneId.of("Asia/Tokyo")));
    }

    @Test
    public void rangeAndShiftAcrossMonthAndLeapDay() {
        assertEquals("2028-03-01", DateUtil.shift("2028-02-28", 2));
        assertEquals(3, DateUtil.range("2028-02-28", "2028-03-01").size());
        assertEquals("2026-01-01", DateUtil.shift("2025-12-31", 1));
        assertEquals(-1, DateUtil.daysBetween("2026-11-05", "2026-11-04"));
    }

    @Test
    public void futureDetection() {
        DateUtil.setClock(Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneId.of("Asia/Dhaka")));
        assertTrue(DateUtil.isFuture("2026-11-06"));
        assertFalse(DateUtil.isFuture("2026-11-05"));
    }

    @Test
    public void weekStartsMonday() {
        assertEquals("2026-11-02", DateUtil.iso(DateUtil.weekStart(DateUtil.parse("2026-11-05"))));
        assertEquals("2026-11-02", DateUtil.iso(DateUtil.weekStart(DateUtil.parse("2026-11-02"))));
    }
}
