package com.transformsai.nutrifitai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;
import com.transformsai.nutrifitai.data.db.model.DayTotals;
import com.transformsai.nutrifitai.domain.GoalTimeline;
import com.transformsai.nutrifitai.domain.ProgressStats;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProgressStatsTest {
    static DayTotals day(String d, double kcal, double protein) {
        DayTotals t = new DayTotals();
        t.date = d;
        t.kcal = kcal;
        t.protein = protein;
        t.itemCount = 1;
        return t;
    }

    static GoalEntity goal(String eff, int kcal, int protein, long created) {
        GoalEntity g = new GoalEntity();
        g.effectiveDate = eff;
        g.kcal = kcal;
        g.proteinG = protein;
        g.waterMl = 2000;
        g.createdAt = created;
        return g;
    }

    @Test
    public void missingDaysAreNotZero() {
        List<DayTotals> t = Arrays.asList(day("2026-11-02", 2000, 100), day("2026-11-04", 1800, 80));
        GoalTimeline goals = new GoalTimeline(Collections.singletonList(goal("2026-01-01", 2000, 100, 1)));
        ProgressStats s = ProgressStats.compute("2026-11-02", "2026-11-08", t, goals, new HashMap<>());
        assertEquals(7, s.days.size());
        assertEquals(2, s.loggedDays);
        assertEquals(1900, s.avgKcal, 1e-9); // not (3800 / 7)
        assertNull(s.days.get(1).band());
    }

    @Test
    public void historicalGoalSnapshotsAreRespected() {
        // goal changes on Nov 4: 2500 -> 2000. Nov 3 at 2400 is in range of the OLD goal.
        List<GoalEntity> g = new ArrayList<>(Arrays.asList(goal("2026-01-01", 2500, 100, 1), goal("2026-11-04", 2000, 100, 2)));
        GoalTimeline tl = new GoalTimeline(g);
        assertEquals(2500, tl.at("2026-11-03").kcal);
        assertEquals(2000, tl.at("2026-11-04").kcal);
        assertNull(tl.at("2025-12-31"));
        List<DayTotals> t = Arrays.asList(day("2026-11-03", 2400, 100), day("2026-11-04", 2400, 100));
        ProgressStats s = ProgressStats.compute("2026-11-03", "2026-11-04", t, tl, new HashMap<>());
        assertEquals(Integer.valueOf(0), s.days.get(0).band());
        assertEquals(Integer.valueOf(1), s.days.get(1).band());
        assertEquals(1, s.inRangeDays);
    }

    @Test
    public void eatingLessIsNotScoredBetter() {
        GoalTimeline tl = new GoalTimeline(Collections.singletonList(goal("2026-01-01", 2000, 100, 1)));
        ProgressStats under = ProgressStats.compute("2026-11-02", "2026-11-02",
                Collections.singletonList(day("2026-11-02", 1000, 100)), tl, new HashMap<>());
        ProgressStats on = ProgressStats.compute("2026-11-02", "2026-11-02",
                Collections.singletonList(day("2026-11-02", 2000, 100)), tl, new HashMap<>());
        assertEquals(Integer.valueOf(-1), under.days.get(0).band());
        assertEquals(0, under.inRangeDays);
        assertEquals(true, on.consistencyScore > under.consistencyScore);
    }

    @Test
    public void consistencyScoreFormula() {
        GoalTimeline tl = new GoalTimeline(Collections.singletonList(goal("2026-01-01", 2000, 100, 1)));
        Map<String, Integer> water = new HashMap<>();
        water.put("2026-11-02", 2500);
        ProgressStats s = ProgressStats.compute("2026-11-02", "2026-11-03",
                Arrays.asList(day("2026-11-02", 2000, 100), day("2026-11-03", 3000, 50)), tl, water);
        // day1: 50+25+25 = 100; day2: 0 -> avg 50
        assertEquals(Integer.valueOf(50), s.consistencyScore);
        assertEquals(1, s.waterMetDays);
    }

    @Test
    public void weightChangeUsesReferenceBeforePeriod() {
        WeightEntity before = new WeightEntity();
        before.kg = 85;
        WeightEntity a = new WeightEntity();
        a.kg = 84;
        WeightEntity b = new WeightEntity();
        b.kg = 83.2;
        assertEquals(-1.8, ProgressStats.weightChange(Arrays.asList(a, b), before), 1e-9);
        assertEquals(-0.8, ProgressStats.weightChange(Arrays.asList(a, b), null), 1e-9);
        assertNull(ProgressStats.weightChange(Collections.singletonList(a), null));
        assertNull(ProgressStats.weightChange(new ArrayList<>(), before));
    }
}
