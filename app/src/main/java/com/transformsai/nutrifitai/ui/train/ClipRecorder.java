package com.transformsai.nutrifitai.ui.train;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;

import java.io.File;

/**
 * Records a short clip (up to 30 s, no audio, so no microphone permission) from the same camera session that
 * the pose tracker uses, for sharing to the feed. The workout screen binds {@link #useCase()} next to the
 * preview and analysis only while a recording is wanted.
 */
final class ClipRecorder {
    static final long MAX_MS = 30_000;

    interface Listener {
        void onTick(long elapsedMs);

        /** @param clip the saved file, or null when recording failed or was too short */
        void onFinished(@Nullable File clip);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final VideoCapture<Recorder> capture = VideoCapture.withOutput(new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build());
    @Nullable private Recording recording;
    @Nullable private File file;
    private long startedAt;
    private boolean wanted;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (recording == null) return;
            long el = SystemClock.elapsedRealtime() - startedAt;
            listener.onTick(el);
            if (el >= MAX_MS) stop();
            else main.postDelayed(this, 250);
        }
    };

    ClipRecorder(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    VideoCapture<Recorder> useCase() {
        return capture;
    }

    /** True from the record tap until the clip is saved: the camera binding includes the video use case. */
    boolean wanted() {
        return wanted;
    }

    boolean recording() {
        return recording != null;
    }

    void want() {
        wanted = true;
    }

    /** Starts writing once the use case is bound. */
    @SuppressLint("MissingPermission") // video only, audio is not enabled
    void start() {
        if (recording != null) return;
        File dir = new File(context.getFilesDir(), "clips");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        file = new File(dir, "clip_" + System.currentTimeMillis() + ".mp4");
        startedAt = SystemClock.elapsedRealtime();
        recording = capture.getOutput().prepareRecording(context, new FileOutputOptions.Builder(file)
                        .setFileSizeLimit(24L * 1024 * 1024).build())
                .start(ContextCompat.getMainExecutor(context), this::onEvent);
        main.post(tick);
    }

    void stop() {
        if (recording != null) recording.stop();
    }

    /** Lifecycle stop: end the clip quietly (it is still saved). */
    void release() {
        stop();
        main.removeCallbacksAndMessages(null);
    }

    private void onEvent(VideoRecordEvent e) {
        if (!(e instanceof VideoRecordEvent.Finalize)) return;
        VideoRecordEvent.Finalize f = (VideoRecordEvent.Finalize) e;
        recording = null;
        wanted = false;
        main.removeCallbacks(tick);
        boolean ok = !f.hasError() || f.getError() == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED;
        File out = file;
        if (out != null && (!ok || out.length() < 10_000)) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            out = null;
        }
        listener.onFinished(out);
    }

    /** Clips older than a week are removed (shared ones are on the server already). */
    static void prune(Context c) {
        File[] files = new File(c.getFilesDir(), "clips").listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            if (f.lastModified() < cutoff) f.delete();
        }
    }
}
