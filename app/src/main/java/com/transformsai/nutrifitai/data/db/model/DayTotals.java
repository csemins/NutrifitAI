package com.transformsai.nutrifitai.data.db.model;

/** Aggregated consumed nutrition for one local date (computed by SQL from canonical items). */
public class DayTotals {
    public String date;
    public double kcal;
    public double protein;
    public double carbs;
    public double fat;
    public double fibre;
    public int itemCount;
}
