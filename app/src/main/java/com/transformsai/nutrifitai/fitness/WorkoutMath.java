package com.transformsai.nutrifitai.fitness;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** Calorie and summary maths for workouts (estimates, clearly labelled in the UI). */
public final class WorkoutMath {
    private WorkoutMath() { }

    /** Compendium-of-Physical-Activities style estimate: kcal = MET x kg x hours of active time. */
    public static double kcal(double met, double kg, long activeMs) {
        if (met <= 0 || kg <= 0 || activeMs <= 0) return 0;
        return met * kg * (activeMs / 3_600_000.0);
    }

    public static double totalKcal(List<WorkoutEngine.StepResult> results, double kg) {
        double k = 0;
        for (WorkoutEngine.StepResult r : results) k += kcal(r.met, kg, r.activeMs);
        return k;
    }

    public static int totalReps(List<WorkoutEngine.StepResult> results) {
        int n = 0;
        for (WorkoutEngine.StepResult r : results) if (!r.hold) n += r.reps;
        return n;
    }

    public static long activeMs(List<WorkoutEngine.StepResult> results) {
        long t = 0;
        for (WorkoutEngine.StepResult r : results) t += r.activeMs;
        return t;
    }

    /** Share of targets achieved (0..1), holds by time and reps by count. */
    public static double completion(List<WorkoutEngine.StepResult> results) {
        if (results.isEmpty()) return 0;
        double s = 0;
        for (WorkoutEngine.StepResult r : results) {
            double done = r.hold ? r.heldMs / 1000.0 : r.reps;
            s += Math.min(1, r.target <= 0 ? 0 : done / r.target);
        }
        return s / results.size();
    }

    /** BMI = kg / m^2 (a population screening measure, not a diagnosis). */
    public static double bmi(double kg, double heightCm) {
        if (kg <= 0 || heightCm <= 0) return Double.NaN;
        double m = heightCm / 100.0;
        return kg / (m * m);
    }

    /** [clean camera reps, reps the form coach rejected] for the camera-tracked rep steps. */
    public static int[] formCounts(List<WorkoutEngine.StepResult> results) {
        int ok = 0, bad = 0;
        for (WorkoutEngine.StepResult r : results) {
            if (!r.camera || r.hold) continue;
            ok += r.reps;
            bad += r.rejected;
        }
        return new int[]{ok, bad};
    }

    /** Rebuilds step results from a saved session's sets JSON (unknown fields default to 0). */
    public static List<WorkoutEngine.StepResult> parseSets(String json) {
        List<WorkoutEngine.StepResult> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                WorkoutEngine.StepResult r = new WorkoutEngine.StepResult();
                r.exerciseId = o.get("exercise_id").getAsString();
                r.name = o.has("name") ? o.get("name").getAsString() : r.exerciseId;
                r.hold = o.has("hold") && o.get("hold").getAsBoolean();
                r.target = o.has("target") ? o.get("target").getAsInt() : 0;
                r.reps = o.has("reps") ? o.get("reps").getAsInt() : 0;
                r.rejected = o.has("rejected") ? o.get("rejected").getAsInt() : 0;
                r.heldMs = o.has("held_ms") ? o.get("held_ms").getAsLong() : 0;
                r.activeMs = o.has("active_ms") ? o.get("active_ms").getAsLong() : 0;
                r.camera = o.has("camera") && o.get("camera").getAsBoolean();
                r.skipped = o.has("skipped") && o.get("skipped").getAsBoolean();
                out.add(r);
            }
        } catch (RuntimeException ignored) {
            // corrupt record: return what was readable
        }
        return out;
    }
}
