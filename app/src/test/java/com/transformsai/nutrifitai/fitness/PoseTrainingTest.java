package com.transformsai.nutrifitai.fitness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.transformsai.nutrifitai.fitness.pose.ExerciseProfile;
import com.transformsai.nutrifitai.fitness.pose.ExerciseSpec;
import com.transformsai.nutrifitai.fitness.pose.MotionTracker;
import com.transformsai.nutrifitai.fitness.pose.Pose;
import com.transformsai.nutrifitai.fitness.pose.PoseFeatures;
import com.transformsai.nutrifitai.fitness.pose.PoseTrainer;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Trains the camera exercise models on the bundled labelled samples (the same code the app runs) and
 * checks leave-one-out accuracy, then replays real sample poses through the rep counter.
 */
public class PoseTrainingTest {
    static List<PoseTrainer.Sample> samples;
    static Map<String, ExerciseProfile> profiles;

    @BeforeClass
    public static void train() throws Exception {
        try (InputStream in = new FileInputStream("src/main/assets/pose/pose_samples.csv")) {
            samples = PoseTrainer.readCsv(in);
        }
        profiles = PoseTrainer.train(samples);
        StringBuilder report = new StringBuilder("Pose training report (leave-one-out):\n");
        for (ExerciseProfile p : profiles.values()) {
            report.append(String.format("  %-17s samples=%4d accuracy=%s%n", p.spec.id, p.samples,
                    Double.isNaN(p.accuracy) ? "n/a" : String.format("%.0f%%", p.accuracy * 100)));
        }
        System.out.println(report);
    }

    @Test
    public void everyCameraExerciseHasAProfile() {
        for (String id : ExerciseSpec.all().keySet()) assertTrue(id, profiles.containsKey(id));
    }

    @Test
    public void wellSampledExercisesClassifyAccurately() {
        // Exercises with a real labelled dataset (Google ML Kit samples + free-exercise-db photos).
        assertTrue("squat " + profiles.get("squat").accuracy, profiles.get("squat").accuracy >= 0.9);
        assertTrue("pushup " + profiles.get("pushup").accuracy, profiles.get("pushup").accuracy >= 0.9);
        for (ExerciseProfile p : profiles.values()) {
            if (!Double.isNaN(p.accuracy) && p.samples >= 8) {
                assertTrue(p.spec.id + " accuracy " + p.accuracy, p.accuracy >= 0.75);
            }
        }
    }

    @Test
    public void learnedRangesPointTheRightWay() {
        ExerciseProfile squat = profiles.get("squat");
        assertTrue("knees bend at the bottom of a squat", squat.b[0] < squat.a[0] - 40);
        ExerciseProfile pushup = profiles.get("pushup");
        assertTrue("elbows bend at the bottom of a push-up", pushup.b[0] < pushup.a[0] - 40);
        ExerciseProfile jj = profiles.get("jumping_jack");
        assertTrue("arms rise in a jumping jack", jj.b[0] > jj.a[0] + 60);
    }

    /** Replays labelled rest/peak poses as a movement: rest, peak, rest ... must count one rep per cycle. */
    @Test
    public void countsRepsFromRealPoses() {
        for (String id : new String[]{"squat", "pushup"}) {
            List<Pose> rest = new ArrayList<>(), peak = new ArrayList<>();
            for (PoseTrainer.Sample s : samples) {
                if (!s.exercise.equals(id)) continue;
                if ("rest".equals(s.state)) rest.add(s.pose);
                if ("peak".equals(s.state)) peak.add(s.pose);
            }
            MotionTracker t = new MotionTracker(profiles.get(id));
            long ts = 0;
            int cycles = 0, gated = 0;
            StringBuilder faults = new StringBuilder();
            for (int c = 0; c < 12; c++) {
                Pose r = rest.get(c * 7 % rest.size()), p = peak.get(c * 11 % peak.size());
                if (Double.isNaN(profiles.get(id).signal(r)) || Double.isNaN(profiles.get(id).signal(p))) {
                    gated++;
                    continue;
                }
                // a realistic ~1.5 s rep: the form coach rejects reps faster than 650 ms
                for (int k = 0; k < 4; k++) t.update(r, ts += 120);
                for (int k = 0; k < 6; k++) t.update(p, ts += 120);
                for (int k = 0; k < 6; k++) {
                    MotionTracker.Frame fr = t.update(r, ts += 120);
                    if (fr.repRejected) faults.append(id).append(':').append(fr.fault.id).append(' ');
                }
                cycles++;
            }
            assertTrue(id + " too many unusable poses: " + gated, cycles >= 8);
            // Each cycle mixes two different people/angles from the dataset; a few borderline pairs may not count,
            // but the counter must never over-count.
            // Every full movement is either counted or rejected with a named form fault (a shallow photo pair is a
            // genuinely shallow rep); the counter never over-counts and rejects at most a fifth of correct samples.
            assertTrue(id + " detected " + (t.reps() + t.rejected()) + " of " + cycles + " rejected: " + faults,
                    t.reps() + t.rejected() >= Math.ceil(cycles * 0.8) && t.reps() <= cycles);
            assertTrue(id + " rejected too many: " + faults, t.rejected() <= Math.ceil(cycles * 0.2));
        }
    }

    @Test
    public void standingStillCountsNothing() {
        Pose standing = null;
        for (PoseTrainer.Sample s : samples) if (s.exercise.equals("squat") && s.state.equals("rest")) { standing = s.pose; break; }
        MotionTracker t = new MotionTracker(profiles.get("squat"));
        for (int i = 0; i < 100; i++) t.update(standing, i * 33L);
        assertEquals(0, t.reps());
        // nobody in frame
        MotionTracker t2 = new MotionTracker(profiles.get("squat"));
        MotionTracker.Frame f = null;
        for (int i = 0; i < 50; i++) f = t2.update(null, i * 33L);
        assertEquals(MotionTracker.Status.NO_PERSON, f.status);
    }

    @Test
    public void mirroringKeepsFeatures() {
        Pose p = samples.get(0).pose;
        Pose m = p.mirrored();
        for (PoseFeatures.F f : PoseFeatures.F.values()) {
            double a = PoseFeatures.get(p, f), b = PoseFeatures.get(m, f);
            if (Double.isNaN(a)) assertTrue(Double.isNaN(b));
            else assertEquals(f.name(), a, b, 1e-3);
        }
    }

    @Test
    public void holdTimerOnlyRunsInPosition() {
        ExerciseProfile wall = profiles.get("wall_sit");
        Pose hold = null, standing = null;
        for (PoseTrainer.Sample s : samples) {
            if (s.exercise.equals("wall_sit") && hold == null) hold = s.pose;
            if (s.exercise.equals("squat") && s.state.equals("rest") && standing == null) standing = s.pose;
        }
        assertTrue(wall.holdDeviation(hold) <= 1);
        assertFalse(wall.holdDeviation(standing) <= 1);
        MotionTracker t = new MotionTracker(wall);
        long ts = 0;
        for (int i = 0; i < 20; i++) t.update(standing, ts += 100);
        assertEquals(0, t.holdMs());
        for (int i = 0; i < 30; i++) t.update(hold, ts += 100);
        assertTrue(t.holdMs() >= 2500 && t.holdMs() <= 3000);
    }
}
