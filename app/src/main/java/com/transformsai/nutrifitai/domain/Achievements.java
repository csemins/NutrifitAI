package com.transformsai.nutrifitai.domain;

import com.transformsai.nutrifitai.data.db.AppDatabase;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.StepDayEntity;
import com.transformsai.nutrifitai.data.db.entity.WorkoutSessionEntity;
import com.transformsai.nutrifitai.data.db.model.DayTotals;
import com.transformsai.nutrifitai.data.db.model.DayWater;
import com.transformsai.nutrifitai.data.db.model.ItemRow;
import com.transformsai.nutrifitai.util.DateUtil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Streaks and badges, computed from saved records only (nothing is stored or faked). A logging day
 * is a day with at least one eaten (not planned) item. The current streak counts back from today, or
 * from yesterday when today has nothing yet, so the streak does not "break" in the morning.
 */
public final class Achievements {
    public static final class Badge {
        public final String id, title, detail;
        public final int progress, goal;

        Badge(String id, String title, String detail, int progress, int goal) {
            this.id = id;
            this.title = title;
            this.detail = detail;
            this.progress = Math.min(progress, goal);
            this.goal = goal;
        }

        public boolean earned() {
            return progress >= goal;
        }
    }

    public static final class Summary {
        public int currentStreak, bestStreak, loggedDays, workouts;
        public final List<Badge> badges = new ArrayList<>();

        public int earned() {
            int n = 0;
            for (Badge b : badges) if (b.earned()) n++;
            return n;
        }
    }

    private Achievements() { }

    /** Call off the main thread. Looks at the last 400 days. */
    public static Summary compute(AppDatabase db, String uid) {
        String today = DateUtil.today();
        String from = DateUtil.shift(today, -400);
        Set<String> logged = new HashSet<>();
        List<DayTotals> totals = db.diary().totalsRange(uid, from, today);
        for (DayTotals t : totals) if (t.itemCount > 0) logged.add(t.date);
        Summary s = new Summary();
        s.loggedDays = logged.size();
        s.currentStreak = currentStreak(logged, today);
        s.bestStreak = Math.max(s.currentStreak, bestStreak(logged, from, today));

        int proteinDays = 0, balancedDays = 0, fibreDays = 0;
        for (DayTotals t : totals) {
            GoalEntity g = db.goals().active(uid, t.date);
            if (g == null) continue;
            if (g.proteinG > 0 && t.protein >= g.proteinG) proteinDays++;
            if (g.fibreG > 0 && t.fibre >= g.fibreG) fibreDays++;
            if (g.kcal > 0 && Math.abs(t.kcal - g.kcal) <= g.kcal * 0.10) balancedDays++;
        }
        int waterDays = 0;
        for (DayWater w : db.water().range(uid, from, today)) {
            GoalEntity g = db.goals().active(uid, w.date);
            if (g != null && g.waterMl > 0 && w.ml >= g.waterMl) waterDays++;
        }
        List<WorkoutSessionEntity> sessions = db.workouts().range(uid, from, today);
        int workouts = 0;
        for (WorkoutSessionEntity w : sessions) if (!"walk".equals(w.mode)) workouts++;
        s.workouts = workouts;
        int bestSteps = 0;
        for (StepDayEntity d : db.activity().stepsRange(uid, from, today)) bestSteps = Math.max(bestSteps, d.steps);
        int aiLogs = 0, earlyBreakfasts = 0;
        Set<String> earlyDays = new HashSet<>();
        Set<String> weekFoods = new HashSet<>();
        String weekStart = DateUtil.shift(today, -6);
        Calendar cal = Calendar.getInstance();
        for (ItemRow r : db.diary().range(uid, DateUtil.shift(today, -120), today)) {
            if (!"consumed".equals(r.status)) continue;
            if (r.date.compareTo(weekStart) >= 0 && r.item != null && r.item.name != null) {
                weekFoods.add(r.item.name.trim().toLowerCase(java.util.Locale.ROOT));
            }
            if ("camera".equals(r.source) || "text".equals(r.source) || "voice".equals(r.source)) aiLogs++;
            if ("breakfast".equals(r.mealType) && r.consumedAt != null && r.consumedAt > 0) {
                cal.setTimeInMillis(r.consumedAt);
                if (cal.get(Calendar.HOUR_OF_DAY) < 9) earlyDays.add(r.date);
            }
        }
        earlyBreakfasts = earlyDays.size();

        s.badges.add(new Badge("first_log", "First bite", "Log your first meal", s.loggedDays, 1));
        s.badges.add(new Badge("streak_3", "On a roll", "Log meals 3 days in a row", s.bestStreak, 3));
        s.badges.add(new Badge("streak_7", "Week warrior", "Log meals 7 days in a row", s.bestStreak, 7));
        s.badges.add(new Badge("streak_30", "Habit master", "Log meals 30 days in a row", s.bestStreak, 30));
        s.badges.add(new Badge("hydration", "Hydration hero", "Reach your water goal on 5 days", waterDays, 5));
        s.badges.add(new Badge("protein", "Protein pro", "Reach your protein target on 3 days", proteinDays, 3));
        s.badges.add(new Badge("balanced", "Balanced", "Stay within 10% of your calorie target on 3 days", balancedDays, 3));
        s.badges.add(new Badge("first_workout", "First sweat", "Finish your first workout", workouts, 1));
        s.badges.add(new Badge("workouts_10", "Ten strong", "Finish 10 workouts", workouts, 10));
        s.badges.add(new Badge("steps_10k", "10K day", "Walk 10,000 steps in a day", bestSteps >= 10000 ? 1 : 0, 1));
        s.badges.add(new Badge("ai_scan", "AI logger", "Log 5 foods with the camera, voice or text AI", aiLogs, 5));
        s.badges.add(new Badge("early_bird", "Early bird", "Eat breakfast before 9 am on 5 days", earlyBreakfasts, 5));
        // 4.1
        s.badges.add(new Badge("fibre_fan", "Fibre fan", "Reach your fibre target on 5 days", fibreDays, 5));
        s.badges.add(new Badge("variety", "Rainbow plate", "Eat 20 different foods in the last 7 days", weekFoods.size(), 20));
        s.badges.add(new Badge("hydration_30", "Ocean mode", "Reach your water goal on 30 days", waterDays, 30));
        s.badges.add(new Badge("steps_15k", "Trailblazer", "Walk 15,000 steps in a day", bestSteps >= 15000 ? 1 : 0, 1));
        s.badges.add(new Badge("workouts_50", "Iron will", "Finish 50 workouts", workouts, 50));
        s.badges.add(new Badge("logged_100", "Century", "Log meals on 100 different days", s.loggedDays, 100));
        s.badges.add(new Badge("streak_100", "Unstoppable", "Log meals 100 days in a row", s.bestStreak, 100));
        return s;
    }

    static int currentStreak(Set<String> logged, String today) {
        String d = logged.contains(today) ? today : DateUtil.shift(today, -1);
        int n = 0;
        while (logged.contains(d)) {
            n++;
            d = DateUtil.shift(d, -1);
        }
        return n;
    }

    static int bestStreak(Set<String> logged, String from, String to) {
        int best = 0, run = 0;
        for (String d : DateUtil.range(from, to)) {
            if (logged.contains(d)) best = Math.max(best, ++run);
            else run = 0;
        }
        return best;
    }
}
