package com.transformsai.nutrifitai.fitness.pose;

/**
 * 33 BlazePose landmarks in an aspect-corrected image space (x and y share one unit, y points down)
 * plus a per-landmark visibility in [0, 1]. Immutable after construction.
 */
public final class Pose {
    public static final int COUNT = 33;
    public static final int NOSE = 0;
    public static final int L_SHOULDER = 11, R_SHOULDER = 12, L_ELBOW = 13, R_ELBOW = 14, L_WRIST = 15, R_WRIST = 16;
    public static final int L_HIP = 23, R_HIP = 24, L_KNEE = 25, R_KNEE = 26, L_ANKLE = 27, R_ANKLE = 28;
    public static final int L_HEEL = 29, R_HEEL = 30, L_FOOT = 31, R_FOOT = 32;

    /** Pairs drawn by the overlay. */
    public static final int[][] BONES = {
            {11, 12}, {11, 23}, {12, 24}, {23, 24},
            {11, 13}, {13, 15}, {12, 14}, {14, 16},
            {23, 25}, {25, 27}, {24, 26}, {26, 28},
            {27, 31}, {28, 32}, {27, 29}, {28, 30}};

    public final float[] x = new float[COUNT], y = new float[COUNT], v = new float[COUNT];
    /** Width/height of the source image in the same unit (for drawing). */
    public final float width, height;

    public Pose(float width, float height) {
        this.width = width;
        this.height = height;
    }

    /** @param data x0,y0,v0,x1,y1,v1,... (99 values) */
    public static Pose of(float[] data, float width, float height) {
        Pose p = new Pose(width, height);
        for (int i = 0; i < COUNT; i++) {
            p.x[i] = data[i * 3];
            p.y[i] = data[i * 3 + 1];
            p.v[i] = data[i * 3 + 2];
        }
        return p;
    }

    /** Horizontal mirror (left/right landmarks swapped) - used for training augmentation. */
    public Pose mirrored() {
        Pose p = new Pose(width, height);
        for (int i = 0; i < COUNT; i++) {
            int j = mirrorIndex(i);
            p.x[j] = width - x[i];
            p.y[j] = y[i];
            p.v[j] = v[i];
        }
        return p;
    }

    static int mirrorIndex(int i) {
        if (i == 0) return 0;
        if (i <= 10) { // face: 1-3 <-> 4-6, 7<->8, 9<->10
            if (i >= 1 && i <= 3) return i + 3;
            if (i >= 4 && i <= 6) return i - 3;
            return (i % 2 == 1) ? i + 1 : i - 1;
        }
        return (i % 2 == 1) ? i + 1 : i - 1; // body: odd = left, even = right
    }

    public boolean visible(int i, float min) {
        return v[i] >= min;
    }
}
