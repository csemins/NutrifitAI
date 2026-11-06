package com.transformsai.nutrifitai.fitness;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Voice-guided workout state machine. Pure Java: the caller supplies the clock via {@link #tick(long)},
 * reps via {@link #addRep()} (camera or manual tap) and, for holds in camera mode, whether the user is
 * in position. Speech and UI updates go to the {@link Listener}.
 *
 * <pre>COUNTDOWN(3 s) -> WORK -> REST -> COUNTDOWN -> WORK ... -> DONE</pre>
 */
public final class WorkoutEngine {
    public enum Phase { IDLE, COUNTDOWN, WORK, REST, DONE }

    public interface Listener {
        /** @param interrupt true for short, time-critical words such as rep counts */
        void speak(String text, boolean interrupt);

        void changed();
    }

    public static final class StepResult {
        public String exerciseId, name;
        public boolean hold;
        public int target, reps;
        /** Reps the form coach did not count. */
        public int rejected;
        public long heldMs, activeMs;
        public boolean skipped, camera;
        public double met;
    }

    public static final long COUNTDOWN_MS = 3000;
    private static final long CUE_GAP_MS = 6000;

    private final WorkoutPlan plan;
    private final ExerciseLibrary lib;
    private final Listener out;
    private final List<StepResult> results = new ArrayList<>();

    private Phase phase = Phase.IDLE;
    private boolean paused;
    private int index;
    private long phaseEnd, pausedRemaining, lastTick = -1, workStart, lastCueAt = -CUE_GAP_MS;
    private int reps, rejected;
    private String lastFaultCue;
    private long lastFaultAt = -CUE_GAP_MS;
    private long heldMs, activeMs, lastAnnouncedSec = -1;
    private boolean cameraMode, inPosition = true;
    private int restTotal;
    private long notInPositionSince = -1;

    public WorkoutEngine(WorkoutPlan plan, ExerciseLibrary lib, Listener listener) {
        this.plan = plan;
        this.lib = lib;
        this.out = listener;
    }

    // ------------------------------------------------------------ inputs
    public void start(long now) {
        if (phase != Phase.IDLE || plan.steps.isEmpty()) return;
        index = 0;
        out.speak("Let's go. " + intro(index), false);
        enterCountdown(now);
    }

    /** Camera mode: holds only count while the user is in position, reps come from pose tracking. */
    public void setCameraMode(boolean camera) {
        cameraMode = camera;
        if (!camera) inPosition = true;
        out.changed();
    }

    public void setInPosition(boolean in, long now) {
        if (!cameraMode) return;
        if (in == inPosition) return;
        inPosition = in;
        notInPositionSince = in ? -1 : now;
        if (phase == Phase.WORK && isHold() && in) out.speak("Good, hold it", false);
        out.changed();
    }

    public void addRep(long now) {
        if (phase != Phase.WORK || paused || isHold()) return;
        reps++;
        int target = step().target;
        if (reps >= target) {
            out.speak(String.valueOf(reps), true);
            completeStep(now, false);
            return;
        }
        if (target >= 10 && reps == target / 2) out.speak(reps + ". Halfway", true);
        else if (target - reps == 3 && target >= 8) out.speak(reps + ". Three more", true);
        else out.speak(String.valueOf(reps), true);
        out.changed();
    }

    /**
     * The camera saw a full movement with a form fault: it does not count. The cue is spoken in full the
     * first time (and again after a pause); repeats of the same fault only say "No rep".
     */
    public void rejectRep(String cue, long now) {
        if (phase != Phase.WORK || paused || isHold()) return;
        rejected++;
        boolean repeat = cue != null && cue.equals(lastFaultCue) && now - lastFaultAt < 8000;
        lastFaultCue = cue;
        lastFaultAt = now;
        lastCueAt = now;
        out.speak(repeat || cue == null ? "No rep" : "No rep. " + cue, true);
        out.changed();
    }

    /** A hold posture fault (the timer is paused by the camera): rate-limited spoken correction. */
    public void formCue(String cue, long now) {
        if (phase != Phase.WORK || paused || cue == null || now - lastFaultAt < 5000) return;
        lastFaultAt = now;
        lastFaultCue = cue;
        lastCueAt = now;
        out.speak(cue, false);
    }

    /** Adds time to the current rest. */
    public void extendRest(int seconds, long now) {
        if (phase != Phase.REST) return;
        if (paused) pausedRemaining += seconds * 1000L;
        else phaseEnd += seconds * 1000L;
        lastAnnouncedSec = -1;
        out.changed();
    }

    /** Starts the current exercise again from zero (e.g. after a bad start). */
    public void restartStep(long now) {
        if (phase != Phase.WORK && phase != Phase.COUNTDOWN) return;
        if (paused) {
            paused = false;
            lastTick = now;
        }
        out.speak("Again: " + intro(index), false);
        enterCountdown(now);
    }

    public int rejected() {
        return rejected;
    }

    /** Total seconds of the current rest (for the progress ring). */
    public int restTotalSeconds() {
        return restTotal;
    }

    /** A movement that did not reach the full range: gives the exercise's form cue (rate limited). */
    public void partialRep(String cue, long now) {
        if (phase != Phase.WORK || paused || cue == null || now - lastCueAt < CUE_GAP_MS) return;
        lastCueAt = now;
        out.speak(cue, false);
    }

    public void skip(long now) {
        if (phase == Phase.WORK) completeStep(now, true);
        else if (phase == Phase.REST || phase == Phase.COUNTDOWN) {
            if (phase == Phase.COUNTDOWN) {
                StepResult r = result(true);
                results.add(r);
                index++;
            }
            if (index >= plan.steps.size()) finish(now);
            else enterCountdown(now);
        }
    }

    public void pause(long now) {
        if (paused || phase == Phase.DONE || phase == Phase.IDLE) return;
        paused = true;
        accumulate(now);
        pausedRemaining = Math.max(0, phaseEnd - now);
        out.speak("Paused", true);
        out.changed();
    }

    public void resume(long now) {
        if (!paused) return;
        paused = false;
        phaseEnd = now + pausedRemaining;
        lastTick = now;
        out.speak("Let's continue", true);
        out.changed();
    }

    public void finishEarly(long now) {
        if (phase == Phase.WORK) completeStep(now, true);
        finish(now);
    }

    public void tick(long now) {
        if (paused || phase == Phase.IDLE || phase == Phase.DONE) {
            lastTick = now;
            return;
        }
        accumulate(now);
        switch (phase) {
            case COUNTDOWN: {
                long left = phaseEnd - now;
                long sec = (left + 999) / 1000;
                if (sec != lastAnnouncedSec && sec > 0 && sec <= 3) {
                    lastAnnouncedSec = sec;
                    out.speak(String.valueOf(sec), true);
                }
                if (left <= 0) enterWork(now);
                break;
            }
            case WORK:
                if (isHold()) {
                    long target = step().target * 1000L;
                    long left = target - heldMs;
                    long sec = (left + 999) / 1000;
                    if (sec != lastAnnouncedSec) {
                        if (sec == 10 && step().target > 15) out.speak("10 seconds left", true);
                        else if (sec > 0 && sec <= 3) out.speak(String.valueOf(sec), true);
                        else if (sec * 2 == step().target && step().target >= 20) out.speak("Halfway there", false);
                        lastAnnouncedSec = sec;
                    }
                    if (cameraMode && !inPosition && notInPositionSince >= 0 && now - notInPositionSince > 2500
                            && now - lastCueAt > CUE_GAP_MS) {
                        lastCueAt = now;
                        out.speak("Get into position, the timer is paused", false);
                    }
                    if (left <= 0) completeStep(now, false);
                }
                break;
            case REST: {
                long left = phaseEnd - now;
                long sec = (left + 999) / 1000;
                if (sec == 3 && lastAnnouncedSec != 3) {
                    lastAnnouncedSec = 3;
                    out.speak("Get ready", false);
                }
                if (left <= 0) enterCountdown(now);
                break;
            }
            default:
                break;
        }
        out.changed();
    }

    private void accumulate(long now) {
        if (lastTick >= 0 && !paused && phase == Phase.WORK) {
            long dt = Math.max(0, Math.min(now - lastTick, 1000));
            activeMs += dt;
            if (isHold() && inPosition) heldMs += dt;
        }
        lastTick = now;
    }

    // ------------------------------------------------------------ transitions
    private void enterCountdown(long now) {
        phase = Phase.COUNTDOWN;
        phaseEnd = now + COUNTDOWN_MS;
        lastAnnouncedSec = -1;
        reps = 0;
        rejected = 0;
        heldMs = 0;
        out.changed();
    }

    private void enterWork(long now) {
        phase = Phase.WORK;
        workStart = now;
        activeMs = 0;
        lastAnnouncedSec = -1;
        notInPositionSince = cameraMode && !inPosition ? now : -1;
        WorkoutPlan.Step s = step();
        out.speak(isHold() ? "Go! Hold for " + s.target + " seconds" : "Go!", true);
        out.changed();
    }

    private void completeStep(long now, boolean skipped) {
        results.add(result(skipped));
        index++;
        if (index >= plan.steps.size()) {
            finish(now);
            return;
        }
        WorkoutPlan.Step prev = plan.steps.get(index - 1);
        if (prev.restSeconds > 0) {
            phase = Phase.REST;
            restTotal = prev.restSeconds;
            phaseEnd = now + prev.restSeconds * 1000L;
            lastAnnouncedSec = -1;
            out.speak((skipped ? "Skipped. " : "Nice work. ") + "Rest " + prev.restSeconds + " seconds. Next: " + intro(index), false);
            out.changed();
        } else {
            out.speak((skipped ? "Skipped. " : "") + "Next: " + intro(index), false);
            enterCountdown(now);
        }
    }

    private StepResult result(boolean skipped) {
        WorkoutPlan.Step s = step();
        Exercise x = lib.byId(s.exerciseId);
        StepResult r = new StepResult();
        r.exerciseId = s.exerciseId;
        r.name = x == null ? s.exerciseId : x.name;
        r.hold = isHold();
        r.target = s.target;
        r.reps = reps;
        r.rejected = rejected;
        r.heldMs = heldMs;
        r.activeMs = phase == Phase.WORK ? activeMs : 0;
        r.skipped = skipped && (r.hold ? heldMs == 0 : reps == 0);
        r.camera = cameraMode && x != null && x.camera;
        r.met = x == null ? 3.5 : x.met;
        return r;
    }

    private void finish(long now) {
        if (phase == Phase.DONE) return;
        phase = Phase.DONE;
        out.speak("Workout complete. Excellent work today!", false);
        out.changed();
    }

    private String intro(int i) {
        WorkoutPlan.Step s = plan.steps.get(i);
        Exercise x = lib.byId(s.exerciseId);
        String name = x == null ? s.exerciseId : x.name.toLowerCase(Locale.ROOT);
        return x != null && x.isHold() ? "hold " + name + " for " + s.target + " seconds" : s.target + " " + name;
    }

    // ------------------------------------------------------------ state for the UI
    public Phase phase() {
        return phase;
    }

    public boolean paused() {
        return paused;
    }

    public int index() {
        return index;
    }

    public int stepCount() {
        return plan.steps.size();
    }

    public WorkoutPlan.Step step() {
        return plan.steps.get(Math.min(index, plan.steps.size() - 1));
    }

    public Exercise exercise() {
        return lib.byId(step().exerciseId);
    }

    public Exercise nextExercise() {
        return index + 1 < plan.steps.size() ? lib.byId(plan.steps.get(index + 1).exerciseId) : null;
    }

    public boolean isHold() {
        Exercise x = exercise();
        return x != null && x.isHold();
    }

    public int reps() {
        return reps;
    }

    public long heldMs() {
        return heldMs;
    }

    public boolean inPosition() {
        return inPosition;
    }

    public boolean cameraMode() {
        return cameraMode;
    }

    public long remainingMs(long now) {
        return paused ? pausedRemaining : Math.max(0, phaseEnd - now);
    }

    public List<StepResult> results() {
        return results;
    }

    public WorkoutPlan plan() {
        return plan;
    }
}
