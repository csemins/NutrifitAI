package com.transformsai.nutrifitai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertArrayEquals;

import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.domain.Nutrients;
import com.transformsai.nutrifitai.domain.NutritionMath;

import org.junit.Test;

import java.util.Arrays;

public class NutritionMathTest {
    private static MealItemEntity rice(double servings, double servingGrams) {
        MealItemEntity i = new MealItemEntity();
        i.kcal100 = 130;
        i.protein100 = 2.7;
        i.carbs100 = 28.2;
        i.fat100 = 0.3;
        i.fibre100 = 0.4;
        i.servings = servings;
        i.servingGrams = servingGrams;
        return i;
    }

    @Test
    public void portionScalesPer100g() {
        assertEquals(260, NutritionMath.portion(130, 200), 1e-9);
        assertEquals(0.0, NutritionMath.portion(130, 0), 1e-9);
        assertNull(NutritionMath.portion((Double) null, 200));
    }

    @Test
    public void fractionalServingsAndGramsAgree() {
        // 1.5 cups of 158 g == 237 g
        Nutrients a = NutritionMath.forItem(rice(1.5, 158));
        Nutrients b = NutritionMath.forItem(rice(237, 1)); // gram-based entry
        assertEquals(a.kcal, b.kcal, 1e-9);
        assertEquals(308.1, a.kcal, 0.01);
        assertEquals(NutritionMath.servingsFor(237, 158), 1.5, 1e-9);
    }

    @Test
    public void per100RoundTrip() {
        double per100 = NutritionMath.per100(85, 340);
        assertEquals(25, per100, 1e-9);
        assertEquals(85, NutritionMath.portion(per100, 340), 1e-9);
    }

    @Test(expected = IllegalArgumentException.class)
    public void per100RejectsZeroGrams() {
        NutritionMath.per100(10, 0);
    }

    @Test
    public void sumKeepsUnknownFibreUnknown() {
        Nutrients x = new Nutrients(100, 1, 2, 3, null);
        Nutrients y = new Nutrients(50, 1, 1, 1, null);
        Nutrients t = NutritionMath.sum(Arrays.asList(x, y));
        assertEquals(150, t.kcal, 1e-9);
        assertNull(t.fibre);
        Nutrients z = new Nutrients(10, 0, 0, 0, 2.5);
        assertEquals(2.5, NutritionMath.sum(Arrays.asList(x, z)).fibre, 1e-9);
    }

    @Test
    public void macroSplitSumsTo100() {
        int[] s = NutritionMath.macroEnergySplit(85, 131, 45);
        assertEquals(100, s[0] + s[1] + s[2]);
        assertArrayEquals(new int[]{0, 0, 0}, NutritionMath.macroEnergySplit(0, 0, 0));
    }

    @Test
    public void proteinDensity() {
        assertEquals(10, NutritionMath.proteinPer100Kcal(20, 200), 1e-9);
        assertEquals(0, NutritionMath.proteinPer100Kcal(20, 0), 1e-9);
    }
}
