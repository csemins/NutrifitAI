package com.transformsai.nutrifitai.util;

import java.util.Locale;

/** Unit conversion and formatting. Storage is always metric (kg, cm, ml, g). */
public final class Units {
    public static final double KG_PER_LB = 0.45359237;
    public static final double CM_PER_IN = 2.54;
    public static final double ML_PER_FLOZ = 29.5735;

    private Units() {
    }

    public static boolean imperial(String units) {
        return "imperial".equals(units);
    }

    public static double kgToLb(double kg) {
        return kg / KG_PER_LB;
    }

    public static double lbToKg(double lb) {
        return lb * KG_PER_LB;
    }

    public static double mlToOz(double ml) {
        return ml / ML_PER_FLOZ;
    }

    public static double ozToMl(double oz) {
        return oz * ML_PER_FLOZ;
    }

    public static String weight(double kg, String units) {
        if (imperial(units)) return String.format(Locale.getDefault(), "%.1f lb", kgToLb(kg));
        return String.format(Locale.getDefault(), "%.1f kg", kg);
    }

    public static String weightNumber(double kg, String units) {
        return String.format(Locale.getDefault(), "%.1f", imperial(units) ? kgToLb(kg) : kg);
    }

    public static String weightUnit(String units) {
        return imperial(units) ? "lb" : "kg";
    }

    public static String height(double cm, String units) {
        if (imperial(units)) {
            int totalIn = (int) Math.round(cm / CM_PER_IN);
            return (totalIn / 12) + "′ " + (totalIn % 12) + "″";
        }
        return Math.round(cm) + " cm";
    }

    /** Volume for hydration: litres with one decimal, or fl oz. */
    public static String volumeNumber(int ml, String units) {
        if (imperial(units)) return String.valueOf(Math.round(mlToOz(ml)));
        return String.format(Locale.getDefault(), "%.1f", ml / 1000.0);
    }

    public static String volumeUnit(String units) {
        return imperial(units) ? "fl oz" : "L";
    }

    public static String volumeSmall(int ml, String units) {
        if (imperial(units)) return Math.round(mlToOz(ml)) + " fl oz";
        return ml + " ml";
    }

    /** Trim trailing zeros: 1.0 -> "1", 1.5 -> "1.5", 0.25 -> "0.25". */
    public static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        String s = String.format(Locale.US, "%.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static String grams(double g) {
        if (g >= 10) return Math.round(g) + " g";
        return num(Math.round(g * 10) / 10.0) + " g";
    }
}
