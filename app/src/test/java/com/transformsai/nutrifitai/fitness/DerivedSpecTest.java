package com.transformsai.nutrifitai.fitness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.transformsai.nutrifitai.fitness.pose.ExerciseProfile;
import com.transformsai.nutrifitai.fitness.pose.ExerciseSpec;
import com.transformsai.nutrifitai.fitness.pose.Pose;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Camera models derived by tools/pose/derive_specs.py and shipped in guided.json: every offered
 * exercise must be camera-tracked, and each photo-derived model must read its own start/end photos
 * as rest (~0) and peak (~1), or as "in position" for holds.
 */
public class DerivedSpecTest {
    static ExerciseLibrary lib;
    /** Models set by rule instead of photos (the photos do not show the movement clearly). */
    static final Set<String> RULE = new HashSet<>(Arrays.asList("butt_kicks", "flutter_kicks", "scissor_kick", "elbow_to_knee", "calf_raise"));
    static final Set<String> DERIVED = new HashSet<>(Arrays.asList("russian_twist", "inchworm", "reverse_crunch", "glute_kickback",
            "burpee", "dead_bug", "side_leg_raise", "tuck_jump", "worlds_greatest", "arm_circles", "superman", "childs_pose",
            "cat_cow", "hamstring_stretch", "hip_flexor_stretch"));

    @BeforeClass
    public static void load() throws Exception {
        try (InputStream g = new FileInputStream("src/main/assets/exercises/guided.json")) {
            lib = ExerciseLibrary.fromStreams(g, null);
        }
        try (InputStream m = new FileInputStream("src/main/assets/exercises/motion.json.gz")) {
            lib.attachMotion(m);
        }
    }

    @Test
    public void everyOfferedExerciseHasACameraModel() {
        List<Exercise> all = lib.guided();
        assertTrue("expected the no-equipment set, got " + all.size(), all.size() >= 35);
        for (Exercise x : all) {
            assertTrue(x.id + " camera flag", x.camera);
            assertNotNull(x.id + " has no camera model", ExerciseSpec.forId(x.trackId()));
        }
        for (String id : RULE) assertNotNull(id, ExerciseSpec.forId(id));
    }

    @Test
    public void derivedModelsReadTheirOwnPhotos() {
        for (String id : DERIVED) {
            Exercise x = lib.byId(id);
            assertNotNull(id, x);
            assertNotNull(id + " motion", x.motion);
            ExerciseSpec spec = ExerciseSpec.forId(id);
            assertNotNull(id, spec);
            ExerciseProfile p = new ExerciseProfile(spec, spec.defA, spec.defB, 0);
            Pose start = Pose.of(x.motion[0], 1, 1);
            Pose end = Pose.of(x.motion["burpee".equals(id) ? 2 : 1], 1, 1);
            if (spec.kind == ExerciseSpec.Kind.HOLD) {
                double d = Math.min(p.holdDeviation(start), p.holdDeviation(end));
                assertTrue(id + " hold deviation " + d, d <= 1.0);
            } else {
                if ("tuck_jump".equals(id)) { Pose t = start; start = end; end = t; }
                assertEquals(id + " rest", 0.0, p.signal(start), 0.15);
                assertEquals(id + " peak", 1.0, p.signal(end), 0.15);
            }
        }
    }

    @Test
    public void holdsRejectAnUnrelatedPose() {
        // a standing pose (squat start photo) is not a child's pose or a superman
        Pose standing = Pose.of(lib.byId("squat").motion[0], 1, 1);
        for (String id : new String[]{"childs_pose", "superman", "cat_cow"}) {
            ExerciseSpec spec = ExerciseSpec.forId(id);
            double d = new ExerciseProfile(spec, spec.defA, spec.defB, 0).holdDeviation(standing);
            assertTrue(id + " accepted standing: " + d, Double.isNaN(d) || d > 1.0);
        }
    }
}
