package com.transformsai.nutrifitai.fitness.pose;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Learns an {@link ExerciseProfile} per exercise from labelled landmark samples
 * (assets/pose/pose_samples.csv, produced by tools/pose/extract_landmarks.py).
 *
 * <p>REPS: robust medians of each feature over "rest" and "peak" samples. HOLD: median of "hold"
 * samples with a tolerance of max(default, 2.5 x MAD). Each sample is also mirrored, so a movement
 * learned on one side works on the other. Pure Java: runs in JVM unit tests and on the device.
 */
public final class PoseTrainer {
    public static final class Sample {
        public final String exercise, state, source;
        public final Pose pose;

        public Sample(String exercise, String state, String source, Pose pose) {
            this.exercise = exercise;
            this.state = state;
            this.source = source;
            this.pose = pose;
        }
    }

    private PoseTrainer() { }

    public static List<Sample> readCsv(InputStream in) throws IOException {
        List<Sample> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line = r.readLine(); // header
        while ((line = r.readLine()) != null) {
            String[] c = line.split(",");
            if (c.length < 3 + Pose.COUNT * 3) continue;
            float[] d = new float[Pose.COUNT * 3];
            float maxX = 0, maxY = 0;
            for (int i = 0; i < d.length; i++) {
                d[i] = Float.parseFloat(c[3 + i]);
                if (i % 3 == 0) maxX = Math.max(maxX, d[i]);
                if (i % 3 == 1) maxY = Math.max(maxY, d[i]);
            }
            Pose p = Pose.of(d, Math.max(1, maxX), Math.max(1, maxY));
            out.add(new Sample(c[0], c[1], c[2], p));
        }
        return out;
    }

    /** Trains every exercise and evaluates it (leave-one-out). Used by tests and the training report. */
    public static Map<String, ExerciseProfile> train(List<Sample> samples) {
        return train(samples, true);
    }

    /** @param evaluate leave-one-out evaluation is O(n^2): skip it on the device. */
    public static Map<String, ExerciseProfile> train(List<Sample> samples, boolean evaluate) {
        Map<String, List<Sample>> by = new HashMap<>();
        for (Sample s : samples) {
            by.computeIfAbsent(s.exercise, k -> new ArrayList<>()).add(s);
            by.computeIfAbsent(s.exercise, k -> new ArrayList<>()).add(new Sample(s.exercise, s.state, s.source + "~m", s.pose.mirrored()));
        }
        Map<String, ExerciseProfile> out = new HashMap<>();
        for (ExerciseSpec spec : ExerciseSpec.all().values()) {
            List<Sample> own = by.getOrDefault(spec.id, new ArrayList<>());
            List<Sample> restExtra = spec.restFrom == null ? new ArrayList<>() : filter(by.getOrDefault(spec.restFrom, new ArrayList<>()), "rest");
            ExerciseProfile p = fit(spec, own, restExtra, -1);
            if (evaluate) p.accuracy = evaluate(spec, own, restExtra);
            out.put(spec.id, p);
        }
        return out;
    }

    static List<Sample> filter(List<Sample> list, String state) {
        List<Sample> out = new ArrayList<>();
        for (Sample s : list) if (s.state.equals(state)) out.add(s);
        return out;
    }

    /** @param skip index into own to leave out (leave-one-out evaluation), -1 for none. */
    static ExerciseProfile fit(ExerciseSpec spec, List<Sample> own, List<Sample> restExtra, int skip) {
        int nf = spec.features.length;
        double[] a = spec.defA.clone(), b = spec.defB.clone();
        int used = 0;
        for (int f = 0; f < nf; f++) {
            List<Double> rest = new ArrayList<>(), peak = new ArrayList<>(), hold = new ArrayList<>();
            for (int i = 0; i < own.size(); i++) {
                if (i == skip || (skip >= 0 && sameSource(own.get(i), own.get(skip)))) continue;
                Sample s = own.get(i);
                double v = PoseFeatures.get(s.pose, spec.features[f]);
                if (Double.isNaN(v)) continue;
                if ("rest".equals(s.state)) rest.add(v);
                else if ("peak".equals(s.state)) peak.add(v);
                else if ("hold".equals(s.state)) hold.add(v);
            }
            if (rest.isEmpty()) {
                for (Sample s : restExtra) {
                    double v = PoseFeatures.get(s.pose, spec.features[f]);
                    if (!Double.isNaN(v)) rest.add(v);
                }
            }
            if (spec.kind == ExerciseSpec.Kind.REPS) {
                if (rest.size() >= 2) a[f] = median(rest);
                if (peak.size() >= 2) b[f] = median(peak);
                // a learned range that collapsed (or flipped) is unusable: keep the documented default
                if (Math.abs(b[f] - a[f]) < Math.abs(spec.defB[f] - spec.defA[f]) * 0.25
                        || Math.signum(b[f] - a[f]) != Math.signum(spec.defB[f] - spec.defA[f])) {
                    a[f] = spec.defA[f];
                    b[f] = spec.defB[f];
                }
                used += rest.size() + peak.size();
            } else if (hold.size() >= 2) {
                double m = median(hold);
                a[f] = m;
                b[f] = Math.max(spec.defB[f], 2.5 * mad(hold, m));
                used += hold.size();
            }
        }
        return new ExerciseProfile(spec, a, b, used);
    }

    /** A sample and its mirror share a source, so leave-one-out leaves out both. */
    private static boolean sameSource(Sample x, Sample y) {
        return base(x.source).equals(base(y.source));
    }

    private static String base(String s) {
        return s.endsWith("~m") ? s.substring(0, s.length() - 2) : s;
    }

    /** Leave-one-out accuracy: rest must score < 0.5 and peak > 0.5, holds must fall inside tolerance. */
    static double evaluate(ExerciseSpec spec, List<Sample> own, List<Sample> restExtra) {
        int ok = 0, n = 0;
        for (int i = 0; i < own.size(); i++) {
            Sample s = own.get(i);
            if (s.source.endsWith("~m")) continue;
            ExerciseProfile p = fit(spec, own, restExtra, i);
            if (spec.kind == ExerciseSpec.Kind.REPS) {
                double sig = signalNoGate(p, s.pose);
                if (Double.isNaN(sig)) continue;
                n++;
                if (("rest".equals(s.state) && sig < 0.5) || ("peak".equals(s.state) && sig > 0.5)) ok++;
            } else if ("hold".equals(s.state)) {
                double d = p.holdDeviation(s.pose);
                if (Double.isNaN(d)) continue;
                n++;
                if (d <= 1) ok++;
            }
        }
        return n == 0 ? Double.NaN : ok / (double) n;
    }

    private static double signalNoGate(ExerciseProfile p, Pose pose) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < p.spec.features.length; i++) {
            double v = PoseFeatures.get(pose, p.spec.features[i]);
            if (Double.isNaN(v)) continue;
            sum += (v - p.a[i]) / (p.b[i] - p.a[i]);
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    static double median(List<Double> v) {
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        int m = a.length / 2;
        return a.length % 2 == 1 ? a[m] : (a[m - 1] + a[m]) / 2;
    }

    static double mad(List<Double> v, double med) {
        List<Double> d = new ArrayList<>();
        for (double x : v) d.add(Math.abs(x - med));
        return median(d);
    }
}
