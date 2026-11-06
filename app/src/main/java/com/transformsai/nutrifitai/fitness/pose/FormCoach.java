package com.transformsai.nutrifitai.fitness.pose;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.fitness.pose.PoseFeatures.F;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict form checks on top of rep counting. A rep only counts when it passes every rule for the
 * exercise; otherwise the coach names what went wrong ("Keep your back straight") and the rep is
 * rejected. Holds pause their timer while a posture fault persists.
 *
 * <p>Rules are simple, documented thresholds on view-tolerant pose features (joint angles, hip offset
 * from the shoulder-ankle line in torso lengths). Strictness shifts each threshold by its slack:
 * relaxed = +slack (easier), standard = as written, strict = -0.6 x slack (harder).
 *
 * <p>Pure Java: unit-tested with synthetic feature streams.
 */
public final class FormCoach {
    public enum Level { RELAXED, STANDARD, STRICT }

    /** Feature source for one frame (a pose, or a map in tests). NaN = not measurable. */
    public interface Features {
        double get(F f);
    }

    public static final class Fault {
        public final String id;
        public final String cue;

        Fault(String id, String cue) {
            this.id = id;
            this.cue = cue;
        }
    }

    enum Kind {
        /** Lowest value in the rep must reach the threshold (depth). */
        MIN_AT_MOST,
        /** Highest value in the rep must reach the threshold (height / extension). */
        MAX_AT_LEAST,
        /** Highest value in the rep must stay under the threshold (e.g. forward lean). */
        MAX_AT_MOST,
        /** Value must not be above the threshold for a sustained part of the rep / hold. */
        NOT_ABOVE,
        /** Value must not be below the threshold for a sustained part of the rep / hold. */
        NOT_BELOW,
        /** Before the rep (rest position) the value must reach the threshold (full lockout). */
        PRE_MAX_AT_LEAST
    }

    static final class Rule {
        final String id;
        final F f;
        final Kind kind;
        final double t, slack;
        final String cue;

        Rule(String id, F f, Kind kind, double t, double slack, String cue) {
            this.id = id;
            this.f = f;
            this.kind = kind;
            this.t = t;
            this.slack = slack;
            this.cue = cue;
        }

        double threshold(Level level) {
            boolean upperBound = kind == Kind.MIN_AT_MOST || kind == Kind.MAX_AT_MOST || kind == Kind.NOT_ABOVE;
            double d = level == Level.RELAXED ? slack : level == Level.STRICT ? -0.6 * slack : 0;
            return upperBound ? t + d : t - d;
        }
    }

    /** Fraction of rep frames that may violate a posture rule before the rep is rejected. */
    static final double MAX_BAD_FRACTION = 0.35;
    /** A hold fault must persist this long before the timer pauses. */
    public static final long HOLD_FAULT_MS = 700;

    private static final Map<String, List<Rule>> RULES = new HashMap<>();
    private static final Map<String, Long> MIN_REP_MS = new HashMap<>();

    private static void r(String ex, String id, F f, Kind k, double t, double slack, String cue) {
        RULES.computeIfAbsent(ex, x -> new ArrayList<>()).add(new Rule(id, f, k, t, slack, cue));
    }

    static {
        // push-ups (and the wide / close-grip variants tracked as push-ups)
        // Standard thresholds are calibrated on the labelled push-up samples (pose_samples.csv): at least 90 %
        // of correct reps from many people and camera angles pass; clear faults fail.
        r("pushup", "sag", F.HIP_SAG, Kind.NOT_ABOVE, 0.22, 0.08, "Keep your back straight, don't let your hips sag");
        r("pushup", "pike", F.HIP_SAG, Kind.NOT_BELOW, -0.40, 0.10, "Lower your hips into one straight line");
        r("pushup", "depth", F.ELBOW, Kind.MIN_AT_MOST, 112, 15, "Go lower, bend your elbows to 90 degrees");
        r("pushup", "lockout", F.ELBOW, Kind.PRE_MAX_AT_LEAST, 148, 12, "Straighten your arms fully at the top");
        // squats
        r("squat", "depth", F.KNEE_MEAN, Kind.MIN_AT_MOST, 112, 14, "Squat lower, thighs towards parallel");
        r("squat", "chest", F.TORSO_TILT, Kind.MAX_AT_MOST, 55, 10, "Keep your chest up and your back straight");
        r("jump_squat", "depth", F.KNEE_MEAN, Kind.MIN_AT_MOST, 125, 14, "Sink lower before you jump");
        r("jump_squat", "chest", F.TORSO_TILT, Kind.MAX_AT_MOST, 55, 10, "Keep your chest up");
        // lunges (and split squats)
        r("lunge", "depth", F.KNEE, Kind.MIN_AT_MOST, 112, 14, "Drop your back knee lower");
        r("lunge", "upright", F.TORSO_TILT, Kind.MAX_AT_MOST, 35, 10, "Keep your torso upright");
        // floor work
        r("glute_bridge", "height", F.HIP, Kind.MAX_AT_LEAST, 158, 10, "Push your hips all the way up and squeeze");
        r("situp", "height", F.TORSO_TILT, Kind.MIN_AT_MOST, 38, 12, "Sit all the way up");
        r("crunch", "height", F.TORSO_TILT, Kind.MIN_AT_MOST, 68, 10, "Lift your shoulders off the floor");
        r("mountain_climber", "pike", F.HIP_SAG, Kind.NOT_BELOW, -0.32, 0.10, "Keep your hips down, level with your shoulders");
        r("mountain_climber", "drive", F.HIP_MIN, Kind.MIN_AT_MOST, 105, 15, "Drive your knee closer to your chest");
        // cardio
        r("jumping_jack", "arms", F.ARM_RAISE, Kind.MAX_AT_LEAST, 135, 20, "Bring your arms all the way overhead");
        r("jumping_jack", "feet", F.LEG_SPREAD, Kind.MAX_AT_LEAST, 0.85, 0.2, "Jump your feet wider");
        r("high_knees", "lift", F.KNEE_LIFT, Kind.MAX_AT_LEAST, -0.2, 0.15, "Drive your knees up to hip height");
        // holds
        r("plank", "sag", F.HIP_SAG, Kind.NOT_ABOVE, 0.16, 0.07, "Straighten your back, lift your hips slightly");
        r("plank", "pike", F.HIP_SAG, Kind.NOT_BELOW, -0.22, 0.08, "Lower your hips, keep a straight line");
        r("side_plank", "sag", F.HIP_SAG, Kind.NOT_ABOVE, 0.18, 0.08, "Lift your hips, keep your body straight");
        r("wall_sit", "high", F.KNEE_MEAN, Kind.NOT_ABOVE, 118, 12, "Slide lower, thighs parallel to the floor");
        r("wall_sit", "low", F.KNEE_MEAN, Kind.NOT_BELOW, 68, 8, "Come up a little, knees at 90 degrees");
        r("wall_sit", "back", F.TORSO_TILT, Kind.NOT_ABOVE, 28, 8, "Keep your back flat against the wall");
        // controlled tempo for strength moves
        for (String ex : new String[]{"pushup", "squat", "lunge", "situp", "crunch", "glute_bridge"}) MIN_REP_MS.put(ex, 650L);
    }

    private final String exercise;
    private final List<Rule> rules;
    private final Level level;
    // per-rep statistics, index-aligned with rules
    private final double[] min, max, preMax;
    private final int[] bad, n;
    private long cycleStart = -1;
    // hold fault persistence
    @Nullable private Rule holdRule;
    private long holdSince = -1;

    public FormCoach(String exerciseTrackId, Level level) {
        this.exercise = exerciseTrackId;
        List<Rule> l = RULES.get(exerciseTrackId);
        this.rules = l == null ? new ArrayList<>() : l;
        this.level = level;
        int k = rules.size();
        min = new double[k];
        max = new double[k];
        preMax = new double[k];
        bad = new int[k];
        n = new int[k];
        resetCycle();
        resetPre();
    }

    public boolean hasRules() {
        return !rules.isEmpty();
    }

    public static Level level(String pref) {
        if ("relaxed".equals(pref)) return Level.RELAXED;
        if ("strict".equals(pref)) return Level.STRICT;
        return Level.STANDARD;
    }

    // ---------------------------------------------------------------- reps
    /** A frame at the rest position, between reps. */
    public void observeRest(Features fs) {
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if (r.kind != Kind.PRE_MAX_AT_LEAST) continue;
            double v = fs.get(r.f);
            if (!Double.isNaN(v)) preMax[i] = Math.max(preMax[i], v);
        }
    }

    /** A frame during the movement (left the rest position, not yet back). */
    public void observeCycle(Features fs, long ts) {
        if (cycleStart < 0) cycleStart = ts;
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            double v = fs.get(r.f);
            if (Double.isNaN(v)) continue;
            n[i]++;
            min[i] = Math.min(min[i], v);
            max[i] = Math.max(max[i], v);
            double t = r.threshold(level);
            if (r.kind == Kind.NOT_ABOVE && v > t) bad[i]++;
            if (r.kind == Kind.NOT_BELOW && v < t) bad[i]++;
        }
    }

    /**
     * The rep came back to rest: returns the first fault (rules in priority order) or null when the rep
     * is clean. Resets the per-rep statistics either way.
     */
    @Nullable
    public Fault evaluateRep(long ts) {
        Fault fault = null;
        for (int i = 0; i < rules.size() && fault == null; i++) {
            Rule r = rules.get(i);
            double t = r.threshold(level);
            switch (r.kind) {
                case MIN_AT_MOST:
                    if (n[i] > 0 && min[i] > t) fault = new Fault(r.id, r.cue);
                    break;
                case MAX_AT_LEAST:
                    if (n[i] > 0 && max[i] < t) fault = new Fault(r.id, r.cue);
                    break;
                case MAX_AT_MOST:
                    if (n[i] > 0 && max[i] > t) fault = new Fault(r.id, r.cue);
                    break;
                case NOT_ABOVE:
                case NOT_BELOW:
                    if (n[i] >= 3 && bad[i] > MAX_BAD_FRACTION * n[i]) fault = new Fault(r.id, r.cue);
                    break;
                case PRE_MAX_AT_LEAST:
                    if (preMax[i] > -1e8 && preMax[i] < t) fault = new Fault(r.id, r.cue);
                    break;
                default:
                    break;
            }
        }
        Long minMs = MIN_REP_MS.get(exercise);
        if (fault == null && minMs != null && cycleStart >= 0 && level != Level.RELAXED
                && ts - cycleStart < (level == Level.STRICT ? minMs + 150 : minMs)) {
            fault = new Fault("tempo", "Slow down and control each rep");
        }
        resetCycle();
        resetPre();
        return fault;
    }

    /** The movement was abandoned (a partial rep): forget the statistics. */
    public void abandonCycle() {
        resetCycle();
    }

    private void resetCycle() {
        for (int i = 0; i < rules.size(); i++) {
            min[i] = Double.MAX_VALUE;
            max[i] = -Double.MAX_VALUE;
            bad[i] = 0;
            n[i] = 0;
        }
        cycleStart = -1;
    }

    private void resetPre() {
        for (int i = 0; i < rules.size(); i++) preMax[i] = -1e9;
    }

    // ---------------------------------------------------------------- holds
    /**
     * For holds: the posture fault that has persisted for {@link #HOLD_FAULT_MS}, or null when the
     * posture is fine (or a fault only just started).
     */
    @Nullable
    public Fault checkHold(Features fs, long ts) {
        Rule current = null;
        for (Rule r : rules) {
            double v = fs.get(r.f);
            if (Double.isNaN(v)) continue;
            double t = r.threshold(level);
            if ((r.kind == Kind.NOT_ABOVE && v > t) || (r.kind == Kind.NOT_BELOW && v < t)) {
                current = r;
                break;
            }
        }
        if (current == null) {
            holdRule = null;
            holdSince = -1;
            return null;
        }
        if (current != holdRule) {
            holdRule = current;
            holdSince = ts;
        }
        return ts - holdSince >= HOLD_FAULT_MS ? new Fault(current.id, current.cue) : null;
    }

    /** Features of a real pose. */
    public static Features of(Pose p) {
        return f -> PoseFeatures.get(p, f);
    }
}
