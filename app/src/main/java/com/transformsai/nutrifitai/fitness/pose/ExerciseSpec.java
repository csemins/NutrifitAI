package com.transformsai.nutrifitai.fitness.pose;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.fitness.pose.PoseFeatures.F;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Which body measurements describe each camera-tracked exercise. The numbers themselves are NOT
 * hand-tuned: rest/peak values are learned from labelled pose samples by {@link PoseTrainer}. The
 * defaults below are only used when an exercise has no samples (and are covered by unit tests).
 */
public final class ExerciseSpec {
    public enum Kind { REPS, HOLD }

    public final String id;
    public final Kind kind;
    public final F[] features;
    /** Fallback rest/peak (reps) or centre/tolerance (holds) per feature. */
    public final double[] defA, defB;
    /** Take rest samples from another exercise when this one has none (e.g. plank for mountain climbers). */
    @Nullable public final String restFrom;
    /** Optional gate: the body must satisfy gateFeature in [gateMin, gateMax] to count (e.g. push-ups horizontal). */
    @Nullable public final F gate;
    public final double gateMin, gateMax;
    /** Spoken when the user reaches only part of the range. */
    public final String depthCue;
    /** Which camera view works best: "front" or "side". */
    public final String view;

    private ExerciseSpec(String id, Kind kind, F[] features, double[] defA, double[] defB, @Nullable String restFrom,
                         @Nullable F gate, double gateMin, double gateMax, String depthCue, String view) {
        this.id = id;
        this.kind = kind;
        this.features = features;
        this.defA = defA;
        this.defB = defB;
        this.restFrom = restFrom;
        this.gate = gate;
        this.gateMin = gateMin;
        this.gateMax = gateMax;
        this.depthCue = depthCue;
        this.view = view;
    }

    private static final Map<String, ExerciseSpec> ALL = new ConcurrentHashMap<>();

    private static void reps(String id, F[] f, double[] rest, double[] peak, String restFrom, F gate, double gMin, double gMax,
                             String cue, String view) {
        ALL.put(id, new ExerciseSpec(id, Kind.REPS, f, rest, peak, restFrom, gate, gMin, gMax, cue, view));
    }

    private static void hold(String id, F[] f, double[] centre, double[] tol, String cue, String view) {
        ALL.put(id, new ExerciseSpec(id, Kind.HOLD, f, centre, tol, null, null, 0, 0, cue, view));
    }

    static {
        reps("squat", new F[]{F.KNEE_MEAN, F.HIP}, new double[]{170, 170}, new double[]{90, 85}, null,
                F.TORSO_TILT, 0, 70, "Sit a little deeper", "front");
        reps("jump_squat", new F[]{F.KNEE_MEAN, F.HIP}, new double[]{170, 170}, new double[]{95, 90}, "squat",
                F.TORSO_TILT, 0, 70, "Sit a little deeper", "front");
        reps("pushup", new F[]{F.ELBOW}, new double[]{165}, new double[]{85}, null,
                F.BODY_TILT, 0, 45, "Lower your chest a little more", "side");
        reps("jumping_jack", new F[]{F.ARM_RAISE, F.LEG_SPREAD}, new double[]{20, 0.35}, new double[]{165, 1.3}, null,
                null, 0, 0, "Arms all the way up", "front");
        reps("lunge", new F[]{F.KNEE}, new double[]{168}, new double[]{95}, null,
                F.TORSO_TILT, 0, 60, "Drop the back knee lower", "side");
        reps("high_knees", new F[]{F.KNEE_LIFT}, new double[]{-0.9}, new double[]{-0.05}, "jumping_jack",
                null, 0, 0, "Drive your knees higher", "front");
        reps("situp", new F[]{F.TORSO_TILT, F.HIP}, new double[]{80, 130}, new double[]{25, 60}, null,
                null, 0, 0, "Come up a bit higher", "side");
        reps("crunch", new F[]{F.TORSO_TILT, F.HIP}, new double[]{82, 125}, new double[]{55, 95}, null,
                null, 0, 0, "Curl up a little more", "side");
        reps("glute_bridge", new F[]{F.HIP}, new double[]{130}, new double[]{172}, null,
                null, 0, 0, "Squeeze and lift your hips higher", "side");
        reps("mountain_climber", new F[]{F.HIP_MIN}, new double[]{165}, new double[]{85}, "pushup",
                F.BODY_TILT, 0, 50, "Bring your knee closer to your chest", "side");
        reps("shoulder_press", new F[]{F.ELBOW, F.WRIST_HEIGHT}, new double[]{85, 0.1}, new double[]{165, 0.75}, null,
                null, 0, 0, "Press all the way overhead", "front");
        reps("bicep_curl", new F[]{F.ELBOW_MIN}, new double[]{160}, new double[]{50}, null,
                null, 0, 0, "Curl all the way up", "front");
        reps("lateral_raise", new F[]{F.ARM_RAISE}, new double[]{20}, new double[]{85}, null,
                null, 0, 0, "Raise to shoulder height", "front");
        hold("plank", new F[]{F.BODY_TILT, F.HIP}, new double[]{12, 170}, new double[]{22, 22}, "Keep your hips in line", "side");
        hold("side_plank", new F[]{F.BODY_TILT, F.HIP}, new double[]{22, 165}, new double[]{25, 25}, "Lift your hips", "front");
        hold("wall_sit", new F[]{F.KNEE_MEAN, F.TORSO_TILT}, new double[]{95, 10}, new double[]{25, 25}, "Thighs parallel to the floor", "side");
    }

    /**
     * Registers a camera model derived offline (tools/pose/derive_specs.py: start/end photo landmarks of
     * free-exercise-db, or stated rule-based thresholds) and shipped in guided.json. Built-in specs win.
     */
    public static synchronized void register(String id, boolean hold, F[] features, double[] a, double[] b,
                                             @Nullable F gate, double gateMin, double gateMax, String cue, String view) {
        if (ALL.containsKey(id) || features.length == 0 || a.length != features.length || b.length != features.length) return;
        ALL.put(id, new ExerciseSpec(id, hold ? Kind.HOLD : Kind.REPS, features, a, b, null, gate, gateMin, gateMax,
                cue == null ? "" : cue, view == null ? "front" : view));
    }

    @Nullable
    public static ExerciseSpec forId(String id) {
        return ALL.get(id);
    }

    public static Map<String, ExerciseSpec> all() {
        return ALL;
    }
}
