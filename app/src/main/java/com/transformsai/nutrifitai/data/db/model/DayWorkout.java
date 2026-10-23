package com.transformsai.nutrifitai.data.db.model;

/** Workout totals for one local date (computed from canonical sessions, never stored). */
public class DayWorkout {
    public String date;
    public long activeMs;
    public double kcal;
    public int sessions;
    public int reps;
}
