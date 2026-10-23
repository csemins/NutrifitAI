package com.transformsai.nutrifitai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.transformsai.nutrifitai.domain.Bmi;
import com.transformsai.nutrifitai.domain.PortionScaler;
import com.transformsai.nutrifitai.social.ProfileCards;

import org.junit.Test;

import java.time.LocalDate;

/** 4.1: AI review portion scaling, BMI and age from birth date, usernames. */
public class ProfileAndPortionTest {
    private static final double E = 1e-6;

    @Test
    public void changingGramsRescalesEveryKnownNutrient() {
        PortionScaler s = new PortionScaler(200, new double[]{260, 5.4, 56.4, 0.6, Double.NaN});
        double[] v = s.setGrams(300);
        assertEquals(390, v[PortionScaler.KCAL], E);
        assertEquals(8.1, v[PortionScaler.PROTEIN], E);
        assertEquals(84.6, v[PortionScaler.CARBS], E);
        assertEquals(0.9, v[PortionScaler.FAT], E);
        assertTrue("unknown fibre stays unknown, not zero", Double.isNaN(v[PortionScaler.FIBRE]));
    }

    @Test
    public void typedNutrientBecomesTheBaseline() {
        PortionScaler s = new PortionScaler(100, new double[]{130, 2.7, 28, 0.3, 0.4});
        s.setValue(PortionScaler.KCAL, 150); // user corrects calories at 100 g
        double[] v = s.setGrams(200);
        assertEquals("scales from the typed value", 300, v[PortionScaler.KCAL], E);
        assertEquals("others still scale from the estimate", 5.4, v[PortionScaler.PROTEIN], E);
    }

    @Test
    public void invalidGramsAreIgnored() {
        PortionScaler s = new PortionScaler(100, new double[]{100, 1, 1, 1, 1});
        s.setGrams(0);
        s.setGrams(Double.NaN);
        s.setGrams(-5);
        assertEquals(100, s.grams(), E);
        assertEquals(100, s.values()[PortionScaler.KCAL], E);
    }

    @Test
    public void clearingANutrientMakesItUnknown() {
        PortionScaler s = new PortionScaler(100, new double[]{100, 1, 1, 1, 1});
        s.setValue(PortionScaler.FIBRE, Double.NaN);
        assertTrue(Double.isNaN(s.setGrams(50)[PortionScaler.FIBRE]));
    }

    @Test
    public void bmiValueAndCategories() {
        assertEquals(22.9, Bmi.of(175, 70), E);
        assertEquals(Bmi.Category.UNDERWEIGHT, Bmi.category(18.4));
        assertEquals(Bmi.Category.HEALTHY, Bmi.category(18.5));
        assertEquals(Bmi.Category.HEALTHY, Bmi.category(24.9));
        assertEquals(Bmi.Category.OVERWEIGHT, Bmi.category(25));
        assertEquals(Bmi.Category.OBESE, Bmi.category(30));
        assertTrue("implausible input gives no BMI", Double.isNaN(Bmi.of(50, 70)));
        assertTrue(Double.isNaN(Bmi.of(175, 0)));
    }

    @Test
    public void ageFromBirthDateRespectsTheBirthday() {
        LocalDate today = LocalDate.of(2026, 11, 7);
        assertEquals(25, Bmi.age("2001-11-07", today));
        assertEquals(24, Bmi.age("2001-11-08", today));
        assertEquals(-1, Bmi.age("2030-01-01", today));
        assertEquals(-1, Bmi.age("not a date", today));
        assertEquals(-1, Bmi.age(null, today));
        assertEquals("leap-day birthday not reached yet", 6, Bmi.age("2020-02-29", LocalDate.of(2027, 2, 28)));
        assertEquals("leap-day birthday counted on 1 March", 7, Bmi.age("2020-02-29", LocalDate.of(2027, 3, 1)));
    }

    @Test
    public void usernameRules() {
        assertEquals("rahman_01", ProfileCards.normalize(" @Rahman_01 "));
        assertTrue(ProfileCards.USERNAME.matcher("abc").matches());
        assertFalse(ProfileCards.USERNAME.matcher("ab").matches());
        assertFalse(ProfileCards.USERNAME.matcher("has space").matches());
        assertFalse(ProfileCards.USERNAME.matcher("UPPER").matches());
        assertFalse(ProfileCards.USERNAME.matcher("a23456789012345678901").matches());
        assertEquals("rahmanhossain", ProfileCards.suggestion("Rahman Hossain!"));
        assertEquals("", ProfileCards.suggestion("A"));
    }
}
