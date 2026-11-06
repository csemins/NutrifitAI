package com.transformsai.nutrifitai.fitness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

public class WorkoutEngineTest {
    ExerciseLibrary lib;
    final List<String> said = new ArrayList<>();
    final WorkoutEngine.Listener listener = new WorkoutEngine.Listener() {
        @Override
        public void speak(String text, boolean interrupt) {
            said.add(text);
        }

        @Override
        public void changed() {
        }
    };

    @Before
    public void setUp() throws Exception {
        try (FileInputStream g = new FileInputStream("src/main/assets/exercises/guided.json");
             FileInputStream l = new FileInputStream("src/main/assets/exercises/library.json.gz")) {
            lib = ExerciseLibrary.fromStreams(g, l);
        }
        try (FileInputStream m = new FileInputStream("src/main/assets/exercises/motion.json.gz")) {
            lib.attachMotion(m);
        }
    }

    WorkoutPlan plan() {
        WorkoutPlan p = new WorkoutPlan();
        p.title = "Test";
        return p.add("jumping_jack", 10, 20).add("plank", 30, 0);
    }

    @Test
    public void librariesLoad() {
        assertTrue(lib.librarySize() > 100); // instrument-free subset
        for (Exercise x : lib.guided()) {
            assertTrue(x.id + " is archived but listed", !x.archived);
            assertTrue(x.id + " has no muscle targets", !x.targets.isEmpty());
            assertTrue(x.id + " has no animation", x.motion != null);
        }
        assertTrue(lib.byId("bicep_curl").archived); // dumbbells: archived, still resolvable for old sessions
        assertEquals(Exercise.Kind.HOLD, lib.byId("plank").kind);
        assertTrue(lib.byId("squat").camera);
        assertTrue(lib.byId("lib:Plank") != null);
        for (WorkoutPlan p : Programs.all()) {
            for (WorkoutPlan.Step s : p.steps) {
                Exercise x = lib.byId(s.exerciseId);
                assertTrue(p.id + " " + s.exerciseId, x != null && !x.archived);
            }
            for (int i = 0; i < p.steps.size() - 1; i++) assertEquals(Programs.REST, p.steps.get(i).restSeconds);
            assertTrue(p.estimatedSeconds(lib) > 120);
        }
    }

    @Test
    public void voiceGuidedRepsThenTimedHold() {
        WorkoutEngine e = new WorkoutEngine(plan(), lib, listener);
        e.start(0);
        assertTrue(said.get(0).contains("10 jumping jacks"));
        e.tick(1000);
        e.tick(3100);
        assertEquals(WorkoutEngine.Phase.WORK, e.phase());
        for (int i = 1; i <= 9; i++) e.addRep(3100 + i * 500L);
        assertEquals(9, e.reps());
        assertTrue(said.contains("5. Halfway"));
        e.addRep(9000);
        assertEquals(WorkoutEngine.Phase.REST, e.phase());
        assertTrue(said.get(said.size() - 1).contains("hold plank for 30 seconds"));
        // rest 20 s then 3 s countdown, then the hold (manual mode: timer always runs)
        long t = 9000;
        while (e.phase() != WorkoutEngine.Phase.WORK) e.tick(t += 100);
        long holdStart = t;
        while (e.phase() == WorkoutEngine.Phase.WORK) e.tick(t += 100);
        assertEquals(WorkoutEngine.Phase.DONE, e.phase());
        assertTrue("hold lasted " + (t - holdStart), t - holdStart >= 29_900 && t - holdStart <= 30_300);
        assertEquals(2, e.results().size());
        assertEquals(10, e.results().get(0).reps);
        assertTrue(said.contains("10 seconds left"));
    }

    @Test
    public void cameraHoldPausesWhenOutOfPosition() {
        WorkoutPlan p = new WorkoutPlan();
        p.add("plank", 10, 0);
        WorkoutEngine e = new WorkoutEngine(p, lib, listener);
        e.setCameraMode(true);
        e.start(0);
        long t = 0;
        while (e.phase() != WorkoutEngine.Phase.WORK) e.tick(t += 100);
        e.setInPosition(false, t);
        for (int i = 0; i < 50; i++) e.tick(t += 100);
        assertEquals(0, e.heldMs());
        assertTrue(said.contains("Get into position, the timer is paused"));
        e.setInPosition(true, t);
        for (int i = 0; i < 50; i++) e.tick(t += 100);
        assertTrue(e.heldMs() >= 4900 && e.heldMs() <= 5100);
    }

    @Test
    public void pauseFreezesTimersAndSkipMovesOn() {
        WorkoutEngine e = new WorkoutEngine(plan(), lib, listener);
        e.start(0);
        e.tick(3100);
        e.addRep(3200);
        e.pause(3300);
        e.addRep(3400); // ignored while paused
        assertEquals(1, e.reps());
        e.resume(60_000);
        e.skip(60_100);
        assertEquals(WorkoutEngine.Phase.REST, e.phase());
        e.skip(60_200); // skip the rest
        assertEquals(WorkoutEngine.Phase.COUNTDOWN, e.phase());
        e.finishEarly(60_300);
        assertEquals(WorkoutEngine.Phase.DONE, e.phase());
    }

    @Test
    public void calorieEstimateUsesMet() {
        // 8 MET, 70 kg, 10 minutes -> 93.3 kcal
        assertEquals(93.3, WorkoutMath.kcal(8, 70, 600_000), 0.1);
        assertEquals(0, WorkoutMath.kcal(8, 0, 600_000), 0);
        assertEquals(22.9, WorkoutMath.bmi(70, 175), 0.1);
    }
}
