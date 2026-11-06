package com.transformsai.nutrifitai.fitness.pose;

/**
 * Turns a stream of poses into reps (with hysteresis) or "in position" time for holds.
 * Pure Java and single-threaded: feed it from one thread.
 *
 * <p>Reps: the smoothed signal must rise above {@link #ENTER} (peak reached) and then fall below
 * {@link #EXIT} (back at rest) for one rep. A partial movement that peaks between {@link #PARTIAL}
 * and ENTER triggers a form cue instead of a rep. A minimum rep duration rejects jitter.
 *
 * <p>Every full movement is also checked by the {@link FormCoach}: a rep with a form fault (sagging
 * back, too shallow, too fast...) is reported as rejected with the fault instead of being counted.
 * For holds a persisting posture fault takes the user out of position, so the timer pauses.
 */
public final class MotionTracker {
    public static final double ENTER = 0.60, EXIT = 0.40, PARTIAL = 0.42;
    public static final long MIN_REP_MS = 350;
    public static final long LOST_MS = 1200;

    public enum Status { NO_PERSON, ADJUST, READY, MOVING, HOLDING }

    public static final class Frame {
        public Status status = Status.NO_PERSON;
        public int reps;
        public boolean repCompleted;
        public boolean partialRep;
        /** The movement completed but broke a form rule: not counted. */
        public boolean repRejected;
        /** Current form fault (rejected rep, or a hold posture fault), else null. */
        public FormCoach.Fault fault;
        public double signal = Double.NaN;
        public boolean inPosition;
        public long holdMs;
    }

    private final ExerciseProfile profile;
    private final FormCoach coach;
    private boolean inCycle;
    private double smooth = Double.NaN;
    private boolean atPeak;
    private double cycleMax = 0;
    private long peakAt, lastRepAt, lastGoodAt = -1, lastHoldTs = -1, holdMs;
    private int reps, rejected;
    private boolean inPosition;
    private long outSince = -1;

    public MotionTracker(ExerciseProfile profile) {
        this(profile, new FormCoach(profile.spec.id, FormCoach.Level.STANDARD));
    }

    public MotionTracker(ExerciseProfile profile, FormCoach coach) {
        this.profile = profile;
        this.coach = coach;
    }

    public ExerciseSpec spec() {
        return profile.spec;
    }

    public Frame update(Pose pose, long ts) {
        Frame f = new Frame();
        f.reps = reps;
        if (pose == null) {
            if (lastGoodAt < 0 || ts - lastGoodAt > LOST_MS) {
                f.status = Status.NO_PERSON;
                inPosition = false;
            } else {
                f.status = profile.spec.kind == ExerciseSpec.Kind.HOLD && inPosition ? Status.HOLDING : Status.MOVING;
            }
            lastHoldTs = -1;
            f.holdMs = holdMs;
            f.inPosition = inPosition;
            return f;
        }
        return profile.spec.kind == ExerciseSpec.Kind.HOLD ? hold(pose, ts, f) : rep(pose, ts, f);
    }

    private Frame rep(Pose pose, long ts, Frame f) {
        double s = profile.signal(pose);
        if (Double.isNaN(s)) {
            f.status = lastGoodAt >= 0 && ts - lastGoodAt < LOST_MS ? Status.MOVING : Status.ADJUST;
            return f;
        }
        lastGoodAt = ts;
        smooth = Double.isNaN(smooth) ? s : 0.55 * s + 0.45 * smooth;
        f.signal = smooth;
        FormCoach.Features fs = FormCoach.of(pose);
        if (!atPeak && smooth <= EXIT && !inCycle) coach.observeRest(fs);
        else {
            inCycle = true;
            coach.observeCycle(fs, ts);
        }
        if (!atPeak) {
            cycleMax = Math.max(cycleMax, smooth);
            if (smooth >= ENTER) {
                atPeak = true;
                peakAt = ts;
            } else if (smooth <= EXIT && cycleMax >= PARTIAL) {
                f.partialRep = true; // came back without reaching the peak
                cycleMax = 0;
                inCycle = false;
                coach.abandonCycle();
            } else if (smooth <= EXIT) {
                cycleMax = 0;
                if (inCycle) coach.abandonCycle();
                inCycle = false;
            }
        } else if (smooth <= EXIT) {
            atPeak = false;
            cycleMax = 0;
            inCycle = false;
            if (ts - lastRepAt >= MIN_REP_MS && ts - peakAt >= 60) {
                FormCoach.Fault fault = coach.evaluateRep(ts);
                lastRepAt = ts;
                if (fault == null) {
                    reps++;
                    f.repCompleted = true;
                } else {
                    rejected++;
                    f.repRejected = true;
                    f.fault = fault;
                }
            } else {
                coach.abandonCycle();
            }
        }
        f.reps = reps;
        f.status = smooth < EXIT && !atPeak ? Status.READY : Status.MOVING;
        return f;
    }

    private Frame hold(Pose pose, long ts, Frame f) {
        double d = profile.holdDeviation(pose);
        if (Double.isNaN(d)) {
            f.status = Status.ADJUST;
            lastHoldTs = -1;
            inPosition = false;
            f.holdMs = holdMs;
            return f;
        }
        lastGoodAt = ts;
        FormCoach.Fault fault = coach.checkHold(FormCoach.of(pose), ts);
        if (fault != null) d = Math.max(d, 1.3); // a posture fault takes the user out of position
        f.fault = fault;
        // hysteresis: enter inside tolerance, leave only when clearly outside for 500 ms
        if (!inPosition && d <= 1.0) {
            inPosition = true;
            outSince = -1;
        } else if (inPosition && d > 1.25) {
            if (outSince < 0) outSince = ts;
            if (ts - outSince > 500) inPosition = false;
        } else if (inPosition) {
            outSince = -1;
        }
        if (inPosition && lastHoldTs >= 0) holdMs += Math.min(ts - lastHoldTs, 250);
        lastHoldTs = ts;
        f.inPosition = inPosition;
        f.holdMs = holdMs;
        f.signal = d;
        f.status = inPosition ? Status.HOLDING : Status.ADJUST;
        return f;
    }

    public int reps() {
        return reps;
    }

    public int rejected() {
        return rejected;
    }

    public long holdMs() {
        return holdMs;
    }
}
