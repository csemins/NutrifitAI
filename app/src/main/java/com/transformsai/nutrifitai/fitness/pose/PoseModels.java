package com.transformsai.nutrifitai.fitness.pose;

import android.content.Context;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

/** Trains the exercise profiles from assets/pose/pose_samples.csv once per process (about 100 ms). */
public final class PoseModels {
    private static volatile Map<String, ExerciseProfile> profiles;

    private PoseModels() { }

    /** Call off the main thread. */
    public static Map<String, ExerciseProfile> get(Context c) {
        Map<String, ExerciseProfile> p = profiles;
        if (p != null) return p;
        synchronized (PoseModels.class) {
            if (profiles == null) {
                // the exercise library registers the derived camera models before training
                com.transformsai.nutrifitai.fitness.ExerciseLibrary.get(c);
                try (InputStream in = c.getApplicationContext().getAssets().open("pose/pose_samples.csv")) {
                    profiles = Collections.unmodifiableMap(PoseTrainer.train(PoseTrainer.readCsv(in), false));
                } catch (Exception e) {
                    profiles = Collections.emptyMap();
                }
            }
            return profiles;
        }
    }
}
