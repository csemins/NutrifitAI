package com.transformsai.nutrifitai.social;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.fitness.pose.Pose;

/**
 * Compact text form of a pose for the live challenge tiles: 13 joints as "x,y" in 0..999 (image-normalised,
 * mirrored for the front camera), "-" when a joint is not visible, joined by ';' (about 100 characters).
 */
public final class PoseCodec {
    /** Nose, shoulders, elbows, wrists, hips, knees, ankles. */
    public static final int[] JOINTS = {Pose.NOSE, Pose.L_SHOULDER, Pose.R_SHOULDER, Pose.L_ELBOW, Pose.R_ELBOW,
            Pose.L_WRIST, Pose.R_WRIST, Pose.L_HIP, Pose.R_HIP, Pose.L_KNEE, Pose.R_KNEE, Pose.L_ANKLE, Pose.R_ANKLE};
    /** Bones between JOINTS indices. */
    public static final int[][] BONES = {{1, 2}, {1, 3}, {3, 5}, {2, 4}, {4, 6}, {1, 7}, {2, 8}, {7, 8}, {7, 9}, {9, 11}, {8, 10}, {10, 12}};
    private static final float MIN_VIS = 0.45f;

    private PoseCodec() { }

    public static String encode(Pose p, boolean mirror) {
        StringBuilder sb = new StringBuilder(110);
        float w = p.width <= 0 ? 1 : p.width, h = p.height <= 0 ? 1 : p.height;
        for (int i = 0; i < JOINTS.length; i++) {
            if (i > 0) sb.append(';');
            int j = JOINTS[i];
            if (p.v[j] < MIN_VIS) {
                sb.append('-');
                continue;
            }
            float x = p.x[j] / w, y = p.y[j] / h; // pixels -> 0..1
            if (mirror) x = 1 - x;
            sb.append(clamp(x)).append(',').append(clamp(y));
        }
        return sb.toString();
    }

    /** @return x0,y0,x1,y1,... in 0..1, NaN for missing joints; null when the text is not a pose. */
    @Nullable
    public static float[] decode(@Nullable String s) {
        if (s == null || s.isEmpty()) return null;
        String[] parts = s.split(";");
        if (parts.length != JOINTS.length) return null;
        float[] out = new float[JOINTS.length * 2];
        for (int i = 0; i < parts.length; i++) {
            String[] xy = parts[i].split(",");
            if (xy.length != 2) {
                out[i * 2] = Float.NaN;
                out[i * 2 + 1] = Float.NaN;
                continue;
            }
            try {
                out[i * 2] = Integer.parseInt(xy[0]) / 999f;
                out[i * 2 + 1] = Integer.parseInt(xy[1]) / 999f;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(999, Math.round(v * 999)));
    }
}
