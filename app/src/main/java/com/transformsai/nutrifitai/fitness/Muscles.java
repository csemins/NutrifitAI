package com.transformsai.nutrifitai.fitness;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Muscle groups shown on the muscle map, and the training load a workout puts on each of them.
 *
 * <p>Load per step = exercise weight for the muscle (0..1) x work done, where work is completed reps
 * (or held seconds / 2, so a 30 s plank weighs like 15 reps). Intensity = load / {@link #FULL_LOAD},
 * capped at 1: about 60 reps-equivalents on a primary muscle reads as a full (100 %) session for it.
 */
public final class Muscles {
    public static final String CHEST = "chest", SHOULDERS = "shoulders", BICEPS = "biceps", TRICEPS = "triceps",
            FOREARMS = "forearms", ABS = "abs", OBLIQUES = "obliques", HIP_FLEXORS = "hip_flexors", QUADS = "quads",
            ADDUCTORS = "adductors", ABDUCTORS = "abductors", HAMSTRINGS = "hamstrings", GLUTES = "glutes",
            CALVES = "calves", TRAPS = "traps", LATS = "lats", MIDDLE_BACK = "middle_back", LOWER_BACK = "lower_back",
            NECK = "neck";

    public static final double FULL_LOAD = 60;

    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    private static final Map<String, String> FROM_DB = new LinkedHashMap<>();

    static {
        LABELS.put(CHEST, "Chest");
        LABELS.put(SHOULDERS, "Shoulders");
        LABELS.put(BICEPS, "Biceps");
        LABELS.put(TRICEPS, "Triceps");
        LABELS.put(FOREARMS, "Forearms");
        LABELS.put(ABS, "Abs");
        LABELS.put(OBLIQUES, "Obliques");
        LABELS.put(HIP_FLEXORS, "Hip flexors");
        LABELS.put(QUADS, "Quads");
        LABELS.put(ADDUCTORS, "Inner thighs");
        LABELS.put(ABDUCTORS, "Outer hips");
        LABELS.put(HAMSTRINGS, "Hamstrings");
        LABELS.put(GLUTES, "Glutes");
        LABELS.put(CALVES, "Calves");
        LABELS.put(TRAPS, "Traps");
        LABELS.put(LATS, "Lats");
        LABELS.put(MIDDLE_BACK, "Upper back");
        LABELS.put(LOWER_BACK, "Lower back");
        LABELS.put(NECK, "Neck");
        FROM_DB.put("abdominals", ABS);
        FROM_DB.put("quadriceps", QUADS);
        FROM_DB.put("lower back", LOWER_BACK);
        FROM_DB.put("middle back", MIDDLE_BACK);
        FROM_DB.put("hamstrings", HAMSTRINGS);
        FROM_DB.put("glutes", GLUTES);
        FROM_DB.put("shoulders", SHOULDERS);
        FROM_DB.put("calves", CALVES);
        FROM_DB.put("triceps", TRICEPS);
        FROM_DB.put("chest", CHEST);
        FROM_DB.put("lats", LATS);
        FROM_DB.put("adductors", ADDUCTORS);
        FROM_DB.put("abductors", ABDUCTORS);
        FROM_DB.put("traps", TRAPS);
        FROM_DB.put("biceps", BICEPS);
        FROM_DB.put("forearms", FOREARMS);
        FROM_DB.put("neck", NECK);
    }

    private Muscles() { }

    public static String label(String id) {
        String l = LABELS.get(id);
        return l == null ? id : l;
    }

    public static boolean known(String id) {
        return LABELS.containsKey(id);
    }

    public static List<String> all() {
        return new ArrayList<>(LABELS.keySet());
    }

    /** free-exercise-db muscle name ("quadriceps") -> map id ("quads"); null when unknown. */
    @Nullable
    public static String fromDb(String name) {
        return name == null ? null : FROM_DB.get(name.toLowerCase(Locale.ROOT));
    }

    /** Work units for one step: completed reps, or held seconds / 2. */
    public static double work(WorkoutEngine.StepResult r) {
        return r.hold ? r.heldMs / 2000.0 : r.reps;
    }

    /** Muscle -> intensity 0..1 for a finished workout; muscles with no load are omitted. */
    public static Map<String, Float> intensity(List<WorkoutEngine.StepResult> results, ExerciseLibrary lib) {
        Map<String, Double> load = new LinkedHashMap<>();
        for (WorkoutEngine.StepResult r : results) {
            Exercise x = lib.byId(r.exerciseId);
            if (x == null) continue;
            double w = work(r);
            if (w <= 0) continue;
            for (Map.Entry<String, Float> t : x.targets.entrySet()) {
                load.merge(t.getKey(), w * t.getValue(), Double::sum);
            }
        }
        return normalise(load);
    }

    /** Planned intensity for a plan (targets x planned work), for previews before a workout. */
    public static Map<String, Float> planned(WorkoutPlan plan, ExerciseLibrary lib) {
        Map<String, Double> load = new LinkedHashMap<>();
        for (WorkoutPlan.Step s : plan.steps) {
            Exercise x = lib.byId(s.exerciseId);
            if (x == null) continue;
            double w = x.isHold() ? s.target / 2.0 : s.target;
            for (Map.Entry<String, Float> t : x.targets.entrySet()) load.merge(t.getKey(), w * t.getValue(), Double::sum);
        }
        return normalise(load);
    }

    static Map<String, Float> normalise(Map<String, Double> load) {
        Map<String, Float> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : load.entrySet()) {
            if (e.getValue() <= 0.05) continue;
            out.put(e.getKey(), (float) Math.min(1.0, e.getValue() / FULL_LOAD));
        }
        return out;
    }

    /** Muscles sorted by intensity, strongest first. */
    public static List<Map.Entry<String, Float>> ranked(Map<String, Float> m) {
        List<Map.Entry<String, Float>> l = new ArrayList<>(m.entrySet());
        Collections.sort(l, (a, b) -> Float.compare(b.getValue(), a.getValue()));
        return l;
    }

    /** "quads, glutes and hamstrings" - the top muscles of a map for a one-line summary. */
    public static String summary(Map<String, Float> m, int max) {
        List<Map.Entry<String, Float>> r = ranked(m);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < Math.min(max, r.size()); i++) names.add(label(r.get(i).getKey()).toLowerCase(Locale.ROOT));
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }
}
