package com.transformsai.nutrifitai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.transformsai.nutrifitai.domain.GoalCalculator;

import org.junit.Test;

public class GoalCalculatorTest {
    private static GoalCalculator.Input in(String sex, int age, double h, double w, String act, String goal) {
        GoalCalculator.Input i = new GoalCalculator.Input();
        i.sex = sex;
        i.age = age;
        i.heightCm = h;
        i.weightKg = w;
        i.targetWeightKg = w;
        i.activity = act;
        i.goal = goal;
        return i;
    }

    @Test
    public void mifflinStJeorMatchesPublishedFormula() {
        // 10*70 + 6.25*175 - 5*30 + 5 = 1648.75
        assertEquals(1648.75, GoalCalculator.bmr("male", 70, 175, 30), 1e-9);
        assertEquals(1482.75, GoalCalculator.bmr("female", 70, 175, 30), 1e-9);
        assertEquals(1565.75, GoalCalculator.bmr("unspecified", 70, 175, 30), 1e-9);
    }

    @Test
    public void maintainEqualsTdee() {
        GoalCalculator.Plan p = GoalCalculator.calculate(in("male", 30, 175, 70, "moderate", "maintain"));
        assertEquals(Math.round(1648.75 * 1.55 / 10) * 10, p.kcal);
        assertTrue(p.proteinG > 0 && p.carbsG > 0 && p.fatG > 0);
        assertEquals(2300, p.waterMl); // 70*33 = 2310 -> 2300
    }

    @Test
    public void deficitIsModerateAndNeverBelowBmr() {
        GoalCalculator.Plan p = GoalCalculator.calculate(in("female", 40, 155, 50, "sedentary", "lose"));
        assertTrue("kcal " + p.kcal + " bmr " + p.bmr, p.kcal >= p.bmr - 10);
        assertTrue(p.kcal >= 1200);
        GoalCalculator.Plan active = GoalCalculator.calculate(in("male", 25, 185, 95, "active", "lose"));
        assertTrue(active.tdee - active.kcal <= 505);
    }

    @Test
    public void surplusIsSmall() {
        GoalCalculator.Plan p = GoalCalculator.calculate(in("male", 25, 180, 70, "light", "gain"));
        assertTrue(p.kcal - p.tdee <= 305);
    }

    @Test
    public void macrosAddUpToCalories() {
        GoalCalculator.Plan p = GoalCalculator.calculate(in("female", 28, 165, 60, "light", "maintain"));
        int kcalFromMacros = p.proteinG * 4 + p.carbsG * 4 + p.fatG * 9;
        assertEquals(p.kcal, kcalFromMacros, 15);
    }

    @Test
    public void validationRejectsOutOfRange() {
        assertFalse(GoalCalculator.validate(in("male", 12, 175, 70, "light", "maintain")).isEmpty());
        assertFalse(GoalCalculator.validate(in("male", 30, 90, 70, "light", "maintain")).isEmpty());
        assertFalse(GoalCalculator.validate(in("male", 30, 175, 10, "light", "maintain")).isEmpty());
        assertFalse(GoalCalculator.validate(in("male", 30, 175, 70, "couch", "maintain")).isEmpty());
        assertTrue(GoalCalculator.validate(in("male", 30, 175, 70, "light", "maintain")).isEmpty());
    }

    @Test
    public void manualTargetLimits() {
        assertTrue(GoalCalculator.manualTargetsValid(2000, 120, 220, 70, 28, 2400));
        assertFalse(GoalCalculator.manualTargetsValid(600, 120, 220, 70, 28, 2400));
        assertFalse(GoalCalculator.manualTargetsValid(2000, 120, 220, 70, 28, 100));
    }
}
