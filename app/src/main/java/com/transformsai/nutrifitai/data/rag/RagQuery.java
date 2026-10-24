package com.transformsai.nutrifitai.data.rag;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Understands what a coach question is about, without any network call: which dates (today,
 * yesterday, "last week", "on Monday", "3 days ago", "Nov 3", ISO dates; English and Bangla words),
 * which topics (meals, water, weight, workouts, steps, fasting, goals) and which free terms to look up
 * in the food database. Pure Java and unit-tested.
 */
public final class RagQuery {
    public static final String FOOD = "food", WATER = "water", WEIGHT = "weight", WORKOUT = "workout",
            STEPS = "steps", FASTING = "fasting", GOALS = "goals", PLANNED = "planned";

    /** Inclusive local date range the question is about. */
    public String from, to;
    /** True when the user named a date or period; false = default (today plus a 7-day overview). */
    public boolean explicitDate;
    public final Set<String> topics = new LinkedHashSet<>();
    /** Lower-case words worth looking up (foods, exercises), stop words removed. */
    public final List<String> terms = new ArrayList<>();

    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    private static final Pattern ISO = Pattern.compile("\\b(20\\d\\d)-(\\d\\d)-(\\d\\d)\\b");
    private static final Pattern DAYS_AGO = Pattern.compile("\\b(\\d{1,2})\\s+days?\\s+ago\\b");
    private static final Pattern LAST_N_DAYS = Pattern.compile("\\b(?:last|past)\\s+(\\d{1,2})\\s+days?\\b");
    private static final Pattern DAY_MONTH = Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\b");
    private static final Pattern MONTH_DAY = Pattern.compile("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b");

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "for", "is", "are", "was", "were", "be", "been",
            "i", "me", "my", "mine", "we", "you", "your", "it", "this", "that", "these", "those", "what", "which", "who",
            "how", "much", "many", "did", "do", "does", "have", "has", "had", "can", "could", "should", "would", "will",
            "about", "with", "from", "today", "yesterday", "tomorrow", "week", "month", "day", "days", "ago", "last",
            "past", "this", "eat", "ate", "eaten", "eating", "food", "meal", "meals", "calories", "calorie", "kcal",
            "protein", "carbs", "carb", "fat", "fibre", "fiber", "sugar", "sodium", "water", "drink", "drank", "weight",
            "workout", "workouts", "exercise", "steps", "walk", "walked", "much", "any", "some", "more", "less", "than",
            "so", "far", "now", "please", "tell", "show", "give", "get", "am", "im", "i'm", "much?", "there", "if",
            "good", "bad", "enough", "too", "very", "also", "just", "still", "left", "remaining", "total", "average",
            "morning", "evening", "night", "breakfast", "lunch", "dinner", "snack", "snacks", "time", "times",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "since", "per", "vs"));

    private RagQuery() { }

    public static RagQuery analyse(String text, String todayIso) {
        RagQuery q = new RagQuery();
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        LocalDate today = LocalDate.parse(todayIso);
        LocalDate from = today, to = today;
        boolean explicit = true;
        Matcher m;
        if ((m = ISO.matcher(t)).find()) {
            from = to = safeDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), today);
        } else if ((m = DAY_MONTH.matcher(t)).find()) {
            from = to = monthDay(month(m.group(2)), Integer.parseInt(m.group(1)), today);
        } else if ((m = MONTH_DAY.matcher(t)).find()) {
            from = to = monthDay(month(m.group(1)), Integer.parseInt(m.group(2)), today);
        } else if ((m = DAYS_AGO.matcher(t)).find()) {
            from = to = today.minusDays(Math.min(90, Integer.parseInt(m.group(1))));
        } else if ((m = LAST_N_DAYS.matcher(t)).find()) {
            from = today.minusDays(Math.max(1, Math.min(90, Integer.parseInt(m.group(1)))) - 1);
        } else if (has(t, "day before yesterday", "পরশু")) {
            from = to = today.minusDays(2);
        } else if (has(t, "yesterday", "গতকাল", "গত কাল", "last night")) {
            from = to = today.minusDays(1);
        } else if (has(t, "tomorrow", "আগামীকাল", "আগামী কাল")) {
            from = to = today.plusDays(1);
            q.topics.add(PLANNED);
        } else if (has(t, "last week", "previous week", "গত সপ্তাহ")) {
            from = today.minusDays(13);
            to = today.minusDays(7);
        } else if (has(t, "this week", "past week", "last 7", "7 days", "seven days", "week", "সপ্তাহ", "weekly")) {
            from = today.minusDays(6);
        } else if (has(t, "last month", "this month", "30 days", "month", "মাস", "monthly")) {
            from = today.minusDays(29);
        } else if (has(t, "today", "আজ", "so far", "right now", "tonight", "this morning")) {
            // today
        } else {
            DayOfWeek dow = weekday(t);
            if (dow != null) {
                LocalDate d = today;
                for (int i = 0; i < 7 && d.getDayOfWeek() != dow; i++) d = d.minusDays(1);
                if (d.equals(today) && has(t, "last ")) d = d.minusDays(7);
                from = to = d;
            } else {
                explicit = false;
            }
        }
        if (to.isAfter(today.plusDays(7))) to = today.plusDays(7);
        if (from.isAfter(to)) from = to;
        q.from = from.format(DateTimeFormatter.ISO_LOCAL_DATE);
        q.to = to.format(DateTimeFormatter.ISO_LOCAL_DATE);
        q.explicitDate = explicit;

        if (has(t, "eat", "ate", "food", "meal", "breakfast", "lunch", "dinner", "snack", "calor", "kcal", "protein",
                "carb", "fat", "fibre", "fiber", "sugar", "sodium", "salt", "diet", "nutri", "macro", "খে", "খাবার", "ভাত",
                "cholesterol", "vitamin", "iron", "calcium", "hungry", "craving")) q.topics.add(FOOD);
        if (has(t, "water", "drink", "drank", "hydrat", "পানি", "জল", "thirst")) q.topics.add(WATER);
        if (has(t, "weight", "weigh", " kg", "kilo", "lbs", "bmi", "ওজন", "lose", "losing", "gain", "fat loss", "slim")) q.topics.add(WEIGHT);
        if (has(t, "workout", "exercise", "train", "muscle", "rep", "squat", "push", "plank", "lunge", "cardio", "gym",
                "ব্যায়াম", "fitness", "sore", "burn", "active", " form")) q.topics.add(WORKOUT);
        if (has(t, "step", "walk", "walking", "km", "হাঁট", "distance", "stroll", "run")) q.topics.add(STEPS);
        if (has(" " + t, " fast", "fasting", "রোজা", "intermittent", "16:8", "iftar", "sehri")) q.topics.add(FASTING);
        if (has(t, "goal", "target", "remaining", "left", "budget", "on track", "progress")) q.topics.add(GOALS);
        if (has(t, "plan", "planned", "tomorrow")) q.topics.add(PLANNED);

        for (String w : t.split("[^\\p{L}\\p{N}]+")) {
            if (w.length() < 3 || STOP.contains(w) || w.matches("\\d+")) continue;
            if (!q.terms.contains(w) && q.terms.size() < 8) q.terms.add(w);
        }
        return q;
    }

    /** True when the question names no particular topic (a general coaching question). */
    public boolean general() {
        return topics.isEmpty() || (topics.size() == 1 && topics.contains(GOALS));
    }

    public boolean wants(String topic) {
        return general() || topics.contains(topic);
    }

    private static boolean has(String t, String... keys) {
        for (String k : keys) if (t.contains(k)) return true;
        return false;
    }

    private static int month(String s) {
        for (int i = 0; i < MONTHS.length; i++) if (s.startsWith(MONTHS[i])) return i + 1;
        return 1;
    }

    /** A day of a month without a year: this year, or last year when that date is still in the future. */
    private static LocalDate monthDay(int month, int day, LocalDate today) {
        LocalDate d = safeDate(today.getYear(), month, day, today);
        return d.isAfter(today.plusDays(7)) ? safeDate(today.getYear() - 1, month, day, today) : d;
    }

    private static LocalDate safeDate(int y, int mo, int d, LocalDate fallback) {
        try {
            return LocalDate.of(y, mo, d);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static DayOfWeek weekday(String t) {
        String[] en = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
        String[] bn = {"সোমবার", "মঙ্গলবার", "বুধবার", "বৃহস্পতিবার", "শুক্রবার", "শনিবার", "রবিবার"};
        for (int i = 0; i < 7; i++) if (t.contains(en[i]) || t.contains(bn[i])) return DayOfWeek.of(i + 1);
        return null;
    }
}
