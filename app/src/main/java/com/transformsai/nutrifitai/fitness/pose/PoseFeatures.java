package com.transformsai.nutrifitai.fitness.pose;

/**
 * View-tolerant body measurements computed from one {@link Pose}. Angles are in degrees, ratios are
 * relative to torso length so they do not depend on distance to the camera. A feature is NaN when the
 * joints it needs are not visible enough.
 */
public final class PoseFeatures {
    public enum F {
        /** Most-bent knee: hip-knee-ankle interior angle (180 = straight). */
        KNEE,
        /** Mean of both knees. */
        KNEE_MEAN,
        /** Mean shoulder-hip-knee angle (180 = body straight). */
        HIP,
        /** Most-flexed hip (knee driven towards the chest). */
        HIP_MIN,
        /** Mean shoulder-elbow-wrist angle. */
        ELBOW,
        /** Most-bent elbow. */
        ELBOW_MIN,
        /** Mean upper-arm elevation from the trunk (0 = arms down, 180 = overhead). */
        ARM_RAISE,
        /** Wrists above the shoulders, in torso lengths. */
        WRIST_HEIGHT,
        /** Distance between the ankles, in torso lengths. */
        LEG_SPREAD,
        /** Highest knee relative to the hips, in torso lengths (0 = knee at hip height). */
        KNEE_LIFT,
        /** Shoulder-to-ankle line from horizontal (0 = lying/plank, 90 = standing). */
        BODY_TILT,
        /** Hip-to-shoulder line from vertical (0 = upright torso, 90 = lying). */
        TORSO_TILT,
        /**
         * Hips' distance from the shoulder-ankle line, in torso lengths. Positive = hips below the line
         * (towards the floor, a sagging back); negative = hips above it (piked). ~0 = straight body.
         */
        HIP_SAG,
        /** Difference between the left and right hip angles: alternating leg moves (flutter kicks, bicycles). */
        LEG_SPLIT,
        /** Heels above the toes, in torso lengths (0 = flat feet): calf raises. */
        HEEL_RISE
    }

    public static final float MIN_VIS = 0.45f;

    private PoseFeatures() { }

    public static double get(Pose p, F f) {
        switch (f) {
            case KNEE: return minOf(angle(p, Pose.L_HIP, Pose.L_KNEE, Pose.L_ANKLE), angle(p, Pose.R_HIP, Pose.R_KNEE, Pose.R_ANKLE));
            case KNEE_MEAN: return meanOf(angle(p, Pose.L_HIP, Pose.L_KNEE, Pose.L_ANKLE), angle(p, Pose.R_HIP, Pose.R_KNEE, Pose.R_ANKLE));
            case HIP: return meanOf(angle(p, Pose.L_SHOULDER, Pose.L_HIP, Pose.L_KNEE), angle(p, Pose.R_SHOULDER, Pose.R_HIP, Pose.R_KNEE));
            case HIP_MIN: return minOf(angle(p, Pose.L_SHOULDER, Pose.L_HIP, Pose.L_KNEE), angle(p, Pose.R_SHOULDER, Pose.R_HIP, Pose.R_KNEE));
            case ELBOW: return meanOf(angle(p, Pose.L_SHOULDER, Pose.L_ELBOW, Pose.L_WRIST), angle(p, Pose.R_SHOULDER, Pose.R_ELBOW, Pose.R_WRIST));
            case ELBOW_MIN: return minOf(angle(p, Pose.L_SHOULDER, Pose.L_ELBOW, Pose.L_WRIST), angle(p, Pose.R_SHOULDER, Pose.R_ELBOW, Pose.R_WRIST));
            case ARM_RAISE: return meanOf(angle(p, Pose.L_HIP, Pose.L_SHOULDER, Pose.L_ELBOW), angle(p, Pose.R_HIP, Pose.R_SHOULDER, Pose.R_ELBOW));
            case WRIST_HEIGHT: {
                double t = torso(p);
                if (Double.isNaN(t) || !vis(p, Pose.L_WRIST, Pose.R_WRIST)) return Double.NaN;
                double sh = (p.y[Pose.L_SHOULDER] + p.y[Pose.R_SHOULDER]) / 2.0;
                double wr = (p.y[Pose.L_WRIST] + p.y[Pose.R_WRIST]) / 2.0;
                return (sh - wr) / t;
            }
            case LEG_SPREAD: {
                double t = torso(p);
                if (Double.isNaN(t) || !vis(p, Pose.L_ANKLE, Pose.R_ANKLE)) return Double.NaN;
                return Math.hypot(p.x[Pose.L_ANKLE] - p.x[Pose.R_ANKLE], p.y[Pose.L_ANKLE] - p.y[Pose.R_ANKLE]) / t;
            }
            case KNEE_LIFT: {
                double t = torso(p);
                if (Double.isNaN(t) || !vis(p, Pose.L_KNEE, Pose.R_KNEE)) return Double.NaN;
                double l = (p.y[Pose.L_HIP] - p.y[Pose.L_KNEE]) / t, r = (p.y[Pose.R_HIP] - p.y[Pose.R_KNEE]) / t;
                return Math.max(l, r);
            }
            case BODY_TILT: {
                if (!vis(p, Pose.L_SHOULDER, Pose.R_SHOULDER) || !(vis(p, Pose.L_ANKLE) || vis(p, Pose.R_ANKLE))) return Double.NaN;
                double sx = (p.x[Pose.L_SHOULDER] + p.x[Pose.R_SHOULDER]) / 2, sy = (p.y[Pose.L_SHOULDER] + p.y[Pose.R_SHOULDER]) / 2;
                double[] a = midVisible(p, Pose.L_ANKLE, Pose.R_ANKLE);
                return Math.toDegrees(Math.atan2(Math.abs(sy - a[1]), Math.abs(sx - a[0]) + 1e-9));
            }
            case TORSO_TILT: {
                if (!vis(p, Pose.L_SHOULDER, Pose.R_SHOULDER, Pose.L_HIP, Pose.R_HIP)) return Double.NaN;
                double sx = (p.x[Pose.L_SHOULDER] + p.x[Pose.R_SHOULDER]) / 2, sy = (p.y[Pose.L_SHOULDER] + p.y[Pose.R_SHOULDER]) / 2;
                double hx = (p.x[Pose.L_HIP] + p.x[Pose.R_HIP]) / 2, hy = (p.y[Pose.L_HIP] + p.y[Pose.R_HIP]) / 2;
                return Math.toDegrees(Math.atan2(Math.abs(sx - hx), Math.abs(sy - hy) + 1e-9));
            }
            case HIP_SAG: {
                double t = torso(p);
                if (Double.isNaN(t) || !(vis(p, Pose.L_ANKLE) || vis(p, Pose.R_ANKLE))) return Double.NaN;
                double sx = (p.x[Pose.L_SHOULDER] + p.x[Pose.R_SHOULDER]) / 2, sy = (p.y[Pose.L_SHOULDER] + p.y[Pose.R_SHOULDER]) / 2;
                double hx = (p.x[Pose.L_HIP] + p.x[Pose.R_HIP]) / 2, hy = (p.y[Pose.L_HIP] + p.y[Pose.R_HIP]) / 2;
                double[] a = midVisible(p, Pose.L_ANKLE, Pose.R_ANKLE);
                double dx = a[0] - sx, dy = a[1] - sy, len = Math.hypot(dx, dy);
                if (len < 1e-4) return Double.NaN;
                // unit normal of the shoulder->ankle line that points down the image (towards the floor)
                double nx = -dy / len, ny = dx / len;
                if (ny < 0) {
                    nx = -nx;
                    ny = -ny;
                }
                return ((hx - sx) * nx + (hy - sy) * ny) / t;
            }
            case LEG_SPLIT: {
                double l = angle(p, Pose.L_SHOULDER, Pose.L_HIP, Pose.L_KNEE), r = angle(p, Pose.R_SHOULDER, Pose.R_HIP, Pose.R_KNEE);
                return Double.isNaN(l) || Double.isNaN(r) ? Double.NaN : Math.abs(l - r);
            }
            case HEEL_RISE: {
                double t = torso(p);
                if (Double.isNaN(t)) return Double.NaN;
                double sum = 0;
                int n = 0;
                if (vis(p, Pose.L_HEEL, Pose.L_FOOT)) {
                    sum += (p.y[Pose.L_FOOT] - p.y[Pose.L_HEEL]) / t;
                    n++;
                }
                if (vis(p, Pose.R_HEEL, Pose.R_FOOT)) {
                    sum += (p.y[Pose.R_FOOT] - p.y[Pose.R_HEEL]) / t;
                    n++;
                }
                return n == 0 ? Double.NaN : sum / n;
            }
            default: return Double.NaN;
        }
    }

    /** Torso length (shoulder midpoint to hip midpoint); NaN when not visible. */
    static double torso(Pose p) {
        if (!vis(p, Pose.L_SHOULDER, Pose.R_SHOULDER, Pose.L_HIP, Pose.R_HIP)) return Double.NaN;
        double sx = (p.x[Pose.L_SHOULDER] + p.x[Pose.R_SHOULDER]) / 2, sy = (p.y[Pose.L_SHOULDER] + p.y[Pose.R_SHOULDER]) / 2;
        double hx = (p.x[Pose.L_HIP] + p.x[Pose.R_HIP]) / 2, hy = (p.y[Pose.L_HIP] + p.y[Pose.R_HIP]) / 2;
        double t = Math.hypot(sx - hx, sy - hy);
        return t < 1e-4 ? Double.NaN : t;
    }

    private static double[] midVisible(Pose p, int a, int b) {
        boolean va = p.visible(a, MIN_VIS), vb = p.visible(b, MIN_VIS);
        if (va && vb) return new double[]{(p.x[a] + p.x[b]) / 2, (p.y[a] + p.y[b]) / 2};
        int i = va ? a : b;
        return new double[]{p.x[i], p.y[i]};
    }

    static boolean vis(Pose p, int... idx) {
        for (int i : idx) if (!p.visible(i, MIN_VIS)) return false;
        return true;
    }

    /** Interior angle at b (degrees), NaN when any joint is hidden. */
    static double angle(Pose p, int a, int b, int c) {
        if (!vis(p, a, b, c)) return Double.NaN;
        double ax = p.x[a] - p.x[b], ay = p.y[a] - p.y[b], cx = p.x[c] - p.x[b], cy = p.y[c] - p.y[b];
        double d = Math.hypot(ax, ay) * Math.hypot(cx, cy);
        if (d < 1e-9) return Double.NaN;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (ax * cx + ay * cy) / d))));
    }

    private static double minOf(double a, double b) {
        if (Double.isNaN(a)) return b;
        if (Double.isNaN(b)) return a;
        return Math.min(a, b);
    }

    private static double meanOf(double a, double b) {
        if (Double.isNaN(a)) return b;
        if (Double.isNaN(b)) return a;
        return (a + b) / 2;
    }
}
