package com.transformsai.nutrifitai.fitness;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One exercise: a coached one (guided.json) or an entry of the instrument-free exercise library. */
public final class Exercise {
    public enum Kind { REPS, HOLD }

    public String id;
    public String name;
    public Kind kind = Kind.REPS;
    /** True when the camera can count reps / verify the hold for this exercise. */
    public boolean camera;
    /** Camera profile + form rules to use (a variant such as "wide_pushup" is tracked as "pushup"). */
    @Nullable public String trackAs;
    /** Metabolic equivalent used for the calorie estimate. */
    public double met = 3.5;
    public int defaultTarget = 10;
    public String muscles = "";
    /** Muscle-map id (see {@link Muscles}) -> how strongly this exercise works it, 0..1. */
    public final Map<String, Float> targets = new LinkedHashMap<>();
    public String level;
    public String equipment;
    public String category;
    /** warmup | main | cooldown - used when building balanced plans. */
    public String role = "main";
    /** Archived: needs equipment, hidden from programs, AI plans and the library list. */
    public boolean archived;
    @Nullable public String needs;
    public List<String> cues = new ArrayList<>();
    public List<String> steps = new ArrayList<>();
    @Nullable public String libraryId;
    /** Body-pose keyframes (33 landmarks x,y,visibility each) for the animated demonstration. */
    @Nullable public float[][] motion;
    public boolean guided;

    public boolean isHold() {
        return kind == Kind.HOLD;
    }

    /** Id of the trained camera profile and form rules for this exercise. */
    public String trackId() {
        return trackAs != null ? trackAs : id;
    }
}
