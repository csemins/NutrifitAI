package com.transformsai.nutrifitai.fitness.pose;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MediaPipe Pose Landmarker (BlazePose, 33 landmarks) in LIVE_STREAM mode. Frames are analysed on the
 * device only; nothing is uploaded. GPU delegate with CPU fallback.
 */
public final class PoseEstimator {
    public interface Listener {
        /** pose == null: no person in the frame. Called on a MediaPipe thread. */
        void onPose(Pose pose, long timestampMs);

        void onError(String message);
    }

    public static final String MODEL_FULL = "pose/pose_landmarker_full.task";
    public static final String MODEL_LITE = "pose/pose_landmarker_lite.task";
    private static final String TAG = "PoseEstimator";
    private static final long BUSY_TIMEOUT_MS = 1500;

    private final PoseLandmarker landmarker;
    private final Listener listener;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile long submitUptimeMs;
    private volatile int lastW = 3, lastH = 4;
    private long lastTimestamp = -1;
    public final boolean gpu;

    private PoseEstimator(PoseLandmarker l, boolean gpu, Listener listener) {
        this.landmarker = l;
        this.gpu = gpu;
        this.listener = listener;
    }

    private static final class Holder {
        PoseEstimator est;
    }

    public static PoseEstimator create(Context ctx, String model, Listener listener) {
        return create(ctx, model, true, listener);
    }

    /**
     * @param model asset path (lite/full) or an absolute file path (downloaded heavy model)
     * @param tryGpu false forces the CPU delegate
     */
    public static PoseEstimator create(Context ctx, String model, boolean tryGpu, Listener listener) {
        Holder h = new Holder();
        if (tryGpu) {
            try {
                h.est = build(ctx, model, Delegate.GPU, listener, h);
                return h.est;
            } catch (Throwable t) {
                Log.w(TAG, "GPU delegate unavailable, using CPU");
            }
        }
        h.est = build(ctx, model, Delegate.CPU, listener, h);
        return h.est;
    }

    private static BaseOptions base(String model, Delegate d) {
        BaseOptions.Builder b = BaseOptions.builder().setDelegate(d);
        if (model.startsWith("/")) {
            try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(model, "r")) {
                b.setModelAssetBuffer(f.getChannel().map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, f.length()));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Model file unavailable", e);
            }
        } else {
            b.setModelAssetPath(model);
        }
        return b.build();
    }

    private static PoseEstimator build(Context ctx, String model, Delegate d, Listener listener, Holder h) {
        PoseLandmarker.PoseLandmarkerOptions opts = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base(model, d))
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.4f)
                .setMinTrackingConfidence(0.4f)
                .setResultListener((PoseLandmarkerResult r, MPImage in) -> {
                    if (h.est != null) h.est.handle(r);
                })
                .setErrorListener(e -> {
                    if (h.est != null) {
                        h.est.busy.set(false);
                        h.est.listener.onError(e.getMessage());
                    }
                })
                .build();
        return new PoseEstimator(PoseLandmarker.createFromOptions(ctx, opts), d == Delegate.GPU, listener);
    }

    /** True while a frame is inside the model; drop camera frames meanwhile. */
    public boolean isBusy() {
        if (busy.get() && SystemClock.uptimeMillis() - submitUptimeMs > BUSY_TIMEOUT_MS) busy.set(false);
        return busy.get();
    }

    /** @param upright already rotated, NOT mirrored */
    public synchronized void submit(Bitmap upright, long captureUptimeMs) {
        long ts = captureUptimeMs <= lastTimestamp ? lastTimestamp + 1 : captureUptimeMs;
        lastTimestamp = ts;
        lastW = upright.getWidth();
        lastH = upright.getHeight();
        submitUptimeMs = SystemClock.uptimeMillis();
        busy.set(true);
        try {
            landmarker.detectAsync(new BitmapImageBuilder(upright).build(), ts);
        } catch (RuntimeException e) {
            busy.set(false);
            listener.onError(e.getMessage());
        }
    }

    private void handle(PoseLandmarkerResult r) {
        busy.set(false);
        List<List<NormalizedLandmark>> people = r.landmarks();
        if (people == null || people.isEmpty()) {
            listener.onPose(null, r.timestampMs());
            return;
        }
        float s = Math.max(lastW, lastH);
        float w = lastW / s, h = lastH / s;
        Pose p = new Pose(w, h);
        List<NormalizedLandmark> lm = people.get(0);
        for (int i = 0; i < Math.min(Pose.COUNT, lm.size()); i++) {
            NormalizedLandmark l = lm.get(i);
            p.x[i] = l.x() * w;
            p.y[i] = l.y() * h;
            p.v[i] = l.visibility().orElse(0f);
        }
        listener.onPose(p, r.timestampMs());
    }

    public void close() {
        try {
            landmarker.close();
        } catch (Exception ignored) {
            // already closed
        }
    }
}
