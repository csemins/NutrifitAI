package com.transformsai.nutrifitai.domain;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;

/**
 * Body mass index (weight in kg divided by height in metres squared) with the WHO adult categories:
 * under 18.5 underweight, 18.5-24.9 healthy, 25-29.9 overweight, 30 and over obesity. Shown for information only;
 * BMI does not account for muscle, age or body shape.
 */
public final class Bmi {
    public static final double UNDER = 18.5, OVER = 25, OBESE = 30;

    public enum Category { UNDERWEIGHT, HEALTHY, OVERWEIGHT, OBESE }

    private Bmi() { }

    /** NaN when the inputs are outside plausible adult ranges. */
    public static double of(double heightCm, double weightKg) {
        if (!(heightCm >= 100 && heightCm <= 250) || !(weightKg >= 25 && weightKg <= 350)) return Double.NaN;
        double m = heightCm / 100.0;
        return Math.round(weightKg / (m * m) * 10.0) / 10.0;
    }

    public static Category category(double bmi) {
        if (bmi < UNDER) return Category.UNDERWEIGHT;
        if (bmi < OVER) return Category.HEALTHY;
        if (bmi < OBESE) return Category.OVERWEIGHT;
        return Category.OBESE;
    }

    /** Whole years between a yyyy-MM-dd birth date and {@code today}; -1 if the date is missing or invalid. */
    public static int age(String birthDate, LocalDate today) {
        if (birthDate == null || birthDate.isEmpty()) return -1;
        try {
            LocalDate d = LocalDate.parse(birthDate);
            if (d.isAfter(today)) return -1;
            return Period.between(d, today).getYears();
        } catch (DateTimeParseException e) {
            return -1;
        }
    }
}
