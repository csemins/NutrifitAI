package com.transformsai.nutrifitai.domain;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;
import com.transformsai.nutrifitai.data.db.model.DayTotals;
import com.transformsai.nutrifitai.util.DateUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Period statistics. Missing days are NOT zero intake: they are excluded from every average and
 * shown as gaps. "In range" means within +/-10% of that day's historical calorie target; eating less
 * is never scored as better than eating on target.
 */
public final class ProgressStats {
    public static final double RANGE_TOLERANCE = 0.10;

    public static final class Day {
        public String date;
        @Nullable
        public DayTotals totals; // null = nothing logged
        @Nullable
        public GoalEntity goal;
        public int waterMl;
        public boolean logged() {
            return totals != null && totals.itemCount > 0;
        }
        /** -1 under range, 0 in range, 1 over range; null when not logged or no goal. */
        @Nullable
        public Integer band() {
            if (!logged() || goal == null || goal.kcal <= 0) return null;
            double ratio = totals.kcal / goal.kcal;
            if (ratio < 1 - RANGE_TOLERANCE) return -1;
            if (ratio > 1 + RANGE_TOLERANCE) return 1;
            return 0;
        }
    }

    public final List<Day> days = new ArrayList<>();
    public int loggedDays;
    public int inRangeDays;
    public int proteinMetDays;
    public int fibreMetDays;
    public int waterMetDays;
    public int waterDaysWithGoal;
    public double avgKcal;
    public double avgProtein;
    public double avgCarbs;
    public double avgFat;
    public double avgWaterMl;
    public int waterLoggedDays;
    /** Transparent consistency score 0-100, null when nothing logged. See README. */
    @Nullable
    public Integer consistencyScore;

    public static ProgressStats compute(String from, String to, List<DayTotals> totals, GoalTimeline goals,
                                        Map<String, Integer> waterByDate) {
        ProgressStats s = new ProgressStats();
        Map<String, DayTotals> byDate = new HashMap<>();
        for (DayTotals t : totals) byDate.put(t.date, t);
        double kcal = 0, p = 0, c = 0, f = 0, water = 0;
        double scoreSum = 0;
        for (String date : DateUtil.range(from, to)) {
            Day d = new Day();
            d.date = date;
            d.totals = byDate.get(date);
            d.goal = goals.at(date);
            Integer w = waterByDate.get(date);
            d.waterMl = w == null ? 0 : w;
            s.days.add(d);
            if (d.waterMl > 0) {
                s.waterLoggedDays++;
                water += d.waterMl;
            }
            if (d.goal != null && d.waterMl > 0) {
                s.waterDaysWithGoal++;
                if (d.waterMl >= d.goal.waterMl) s.waterMetDays++;
            }
            if (!d.logged()) continue;
            s.loggedDays++;
            kcal += d.totals.kcal;
            p += d.totals.protein;
            c += d.totals.carbs;
            f += d.totals.fat;
            Integer band = d.band();
            boolean inRange = band != null && band == 0;
            boolean proteinMet = d.goal != null && d.totals.protein >= 0.9 * d.goal.proteinG;
            boolean waterMet = d.goal != null && d.waterMl >= d.goal.waterMl;
            if (inRange) s.inRangeDays++;
            if (proteinMet) s.proteinMetDays++;
            if (d.goal != null && d.totals.fibre >= 0.9 * d.goal.fibreG) s.fibreMetDays++;
            scoreSum += (inRange ? 0.5 : 0) + (proteinMet ? 0.25 : 0) + (waterMet ? 0.25 : 0);
        }
        if (s.loggedDays > 0) {
            s.avgKcal = kcal / s.loggedDays;
            s.avgProtein = p / s.loggedDays;
            s.avgCarbs = c / s.loggedDays;
            s.avgFat = f / s.loggedDays;
            s.consistencyScore = (int) Math.round(scoreSum * 100 / s.loggedDays);
        }
        if (s.waterLoggedDays > 0) s.avgWaterMl = water / s.waterLoggedDays;
        return s;
    }

    /** Weight change across the interval: last entry in range minus the reference before/at start. */
    @Nullable
    public static Double weightChange(List<WeightEntity> inRangeAsc, @Nullable WeightEntity before) {
        if (inRangeAsc.isEmpty()) return null;
        WeightEntity last = inRangeAsc.get(inRangeAsc.size() - 1);
        WeightEntity first = before != null ? before : inRangeAsc.get(0);
        if (first == last) return null;
        return last.kg - first.kg;
    }
}
