package com.transformsai.nutrifitai.fitness.pose;

/**
 * A trained description of one exercise.
 * <ul>
 * <li>REPS: per feature, the typical value at rest and at the peak of the movement. The rep signal is
 * the mean normalised position between them: 0 = rest, 1 = peak.</li>
 * <li>HOLD: per feature, the centre of a correct hold and the allowed tolerance.</li>
 * </ul>
 */
public final class ExerciseProfile {
    public final ExerciseSpec spec;
    public final double[] a; // rest (reps) or centre (hold)
    public final double[] b; // peak (reps) or tolerance (hold)
    public final int samples;
    /** Leave-one-out accuracy on the labelled samples (NaN when not evaluated). */
    public double accuracy = Double.NaN;

    public ExerciseProfile(ExerciseSpec spec, double[] a, double[] b, int samples) {
        this.spec = spec;
        this.a = a;
        this.b = b;
        this.samples = samples;
    }

    /** Rep progress 0 (rest) .. 1 (peak); may overshoot slightly. NaN when the pose is not usable. */
    public double signal(Pose p) {
        if (!gateOk(p)) return Double.NaN;
        double sum = 0;
        int n = 0;
        for (int i = 0; i < spec.features.length; i++) {
            double v = PoseFeatures.get(p, spec.features[i]);
            if (Double.isNaN(v)) continue;
            double range = b[i] - a[i];
            if (Math.abs(range) < 1e-6) continue;
            sum += (v - a[i]) / range;
            n++;
        }
        // require at least half of the features to be measurable
        return n == 0 || n * 2 < spec.features.length ? Double.NaN : sum / n;
    }

    /** For holds: worst normalised deviation (<= 1 means inside tolerance); NaN when unusable. */
    public double holdDeviation(Pose p) {
        double worst = 0;
        int n = 0;
        for (int i = 0; i < spec.features.length; i++) {
            double v = PoseFeatures.get(p, spec.features[i]);
            if (Double.isNaN(v)) continue;
            worst = Math.max(worst, Math.abs(v - a[i]) / b[i]);
            n++;
        }
        return n < spec.features.length ? Double.NaN : worst;
    }

    boolean gateOk(Pose p) {
        if (spec.gate == null) return true;
        double g = PoseFeatures.get(p, spec.gate);
        return !Double.isNaN(g) && g >= spec.gateMin && g <= spec.gateMax;
    }
}
