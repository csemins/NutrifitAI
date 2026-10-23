package com.transformsai.nutrifitai.util;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * All diary records are keyed by the user's LOCAL calendar date (yyyy-MM-dd) at the moment of
 * logging, plus an absolute epoch timestamp. A meal logged at 23:50 stays on that day even if the
 * device later changes timezone; "today" is recomputed from the current zone on every query.
 */
public final class DateUtil {
    public static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static Clock clock = Clock.systemDefaultZone();

    private DateUtil() {
    }

    /** Test hook. */
    public static void setClock(Clock c) {
        clock = c;
    }

    public static Clock clock() {
        return clock;
    }

    public static ZoneId zone() {
        return clock.getZone();
    }

    public static LocalDate todayDate() {
        return LocalDate.now(clock);
    }

    public static String today() {
        return iso(todayDate());
    }

    public static long nowMillis() {
        return clock.millis();
    }

    public static String iso(LocalDate d) {
        return d.format(ISO);
    }

    public static LocalDate parse(String iso) {
        return LocalDate.parse(iso, ISO);
    }

    public static String shift(String iso, int days) {
        return iso(parse(iso).plusDays(days));
    }

    /** Local date for an absolute instant in the given zone. */
    public static String dateOf(long epochMillis, ZoneId zone) {
        return iso(Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate());
    }

    /** Local date for an instant in the device's current zone. */
    public static String dateOf(long epochMillis) {
        return dateOf(epochMillis, ZoneId.systemDefault());
    }

    public static boolean isFuture(String iso) {
        return parse(iso).isAfter(todayDate());
    }

    public static long daysBetween(String fromIso, String toIso) {
        return ChronoUnit.DAYS.between(parse(fromIso), parse(toIso));
    }

    /** Inclusive list of dates. */
    public static List<String> range(String fromIso, String toIso) {
        List<String> out = new ArrayList<>();
        LocalDate d = parse(fromIso);
        LocalDate end = parse(toIso);
        while (!d.isAfter(end)) {
            out.add(iso(d));
            d = d.plusDays(1);
        }
        return out;
    }

    public static LocalDate weekStart(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public static String dayLetter(String iso) {
        return parse(iso).getDayOfWeek().getDisplayName(TextStyle.NARROW, Locale.getDefault());
    }

    public static String dayShort(String iso) {
        return parse(iso).getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault());
    }

    public static String dayLong(String iso) {
        return parse(iso).getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.getDefault());
    }

    public static String monthDay(String iso) {
        return parse(iso).format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()));
    }

    public static String headerDate(LocalDate d) {
        return d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()));
    }

    public static String time(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone())
                .format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()));
    }

    public static String minutesToClock(int minutes) {
        return String.format(Locale.US, "%02d:%02d", (minutes / 60) % 24, minutes % 60);
    }

    /** Greeting based on local hour. */
    public static String greetingKey() {
        int h = ZonedDateTime.now(clock).getHour();
        if (h < 5) return "evening";
        if (h < 12) return "morning";
        if (h < 17) return "afternoon";
        return "evening";
    }
}
