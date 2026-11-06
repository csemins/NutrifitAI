package com.transformsai.nutrifitai.fitness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.transformsai.nutrifitai.fitness.pose.FormCoach;
import com.transformsai.nutrifitai.fitness.pose.PoseFeatures.F;

import org.junit.Test;

import java.util.EnumMap;
import java.util.Map;

/** Strict form rules on synthetic feature streams. */
public class FormCoachTest {
    private static FormCoach.Features f(Object... kv) {
        Map<F, Double> m = new EnumMap<>(F.class);
        for (int i = 0; i < kv.length; i += 2) m.put((F) kv[i], ((Number) kv[i + 1]).doubleValue());
        return x -> m.containsKey(x) ? m.get(x) : Double.NaN;
    }

    /** One push-up: rest at the top, down to minElbow, back up, with a constant hip offset. */
    private static FormCoach.Fault pushup(FormCoach c, double topElbow, double minElbow, double sag, long durationMs) {
        for (int i = 0; i < 5; i++) c.observeRest(f(F.ELBOW, topElbow, F.HIP_SAG, sag));
        int frames = 20;
        for (int i = 0; i <= frames; i++) {
            double k = Math.sin(Math.PI * i / frames);
            c.observeCycle(f(F.ELBOW, topElbow - (topElbow - minElbow) * k, F.HIP_SAG, sag), i * durationMs / frames);
        }
        return c.evaluateRep(durationMs);
    }

    @Test
    public void cleanPushupCounts() {
        assertNull(pushup(new FormCoach("pushup", FormCoach.Level.STANDARD), 165, 85, 0.02, 1500));
    }

    @Test
    public void saggingBackIsRejectedWithCue() {
        FormCoach.Fault fault = pushup(new FormCoach("pushup", FormCoach.Level.STANDARD), 165, 85, 0.35, 1500);
        assertNotNull(fault);
        assertEquals("sag", fault.id);
    }

    @Test
    public void shallowAndHalfLockoutAreRejected() {
        assertEquals("depth", pushup(new FormCoach("pushup", FormCoach.Level.STANDARD), 165, 120, 0, 1500).id);
        assertEquals("lockout", pushup(new FormCoach("pushup", FormCoach.Level.STANDARD), 130, 85, 0, 1500).id);
    }

    @Test
    public void tooFastIsRejectedUnlessRelaxed() {
        assertEquals("tempo", pushup(new FormCoach("pushup", FormCoach.Level.STANDARD), 165, 85, 0, 300).id);
        assertNull(pushup(new FormCoach("pushup", FormCoach.Level.RELAXED), 165, 85, 0, 300));
    }

    @Test
    public void strictnessShiftsThresholds() {
        // knee 115 at the bottom of a squat: fine when relaxed (<= 126), too shallow when strict (<= ~104)
        FormCoach relaxed = new FormCoach("squat", FormCoach.Level.RELAXED), strict = new FormCoach("squat", FormCoach.Level.STRICT);
        for (FormCoach c : new FormCoach[]{relaxed, strict}) {
            c.observeCycle(f(F.KNEE_MEAN, 150, F.TORSO_TILT, 20), 0);
            c.observeCycle(f(F.KNEE_MEAN, 115, F.TORSO_TILT, 25), 600);
            c.observeCycle(f(F.KNEE_MEAN, 150, F.TORSO_TILT, 20), 1200);
        }
        assertNull(relaxed.evaluateRep(1300));
        assertEquals("depth", strict.evaluateRep(1300).id);
    }

    @Test
    public void plankHoldFaultNeedsToPersist() {
        FormCoach c = new FormCoach("plank", FormCoach.Level.STANDARD);
        assertNull(c.checkHold(f(F.HIP_SAG, 0.05), 0));
        assertNull(c.checkHold(f(F.HIP_SAG, 0.30), 100));            // just started sagging
        FormCoach.Fault fault = c.checkHold(f(F.HIP_SAG, 0.30), 100 + FormCoach.HOLD_FAULT_MS);
        assertNotNull(fault);
        assertEquals("sag", fault.id);
        assertNull(c.checkHold(f(F.HIP_SAG, 0.0), 2000));           // corrected
        FormCoach p = new FormCoach("plank", FormCoach.Level.STANDARD);
        assertNull(p.checkHold(f(F.HIP_SAG, -0.4), 0));
        assertEquals("pike", p.checkHold(f(F.HIP_SAG, -0.4), FormCoach.HOLD_FAULT_MS).id);
    }

    @Test
    public void exercisesWithoutRulesAlwaysPass() {
        FormCoach c = new FormCoach("burpee", FormCoach.Level.STRICT);
        c.observeCycle(f(F.KNEE, 100), 0);
        assertNull(c.evaluateRep(2000));
    }
}
