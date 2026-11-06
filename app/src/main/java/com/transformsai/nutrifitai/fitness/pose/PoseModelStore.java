package com.transformsai.nutrifitai.fitness.pose;

import android.content.Context;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.reminders.SystemEventsReceiver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Pose model choice (Settings › Advanced). Lite and Full ship in the APK; Heavy (most accurate, about
 * 29 MB, slow without a GPU) is downloaded on request from Google's MediaPipe model storage and
 * verified against a pinned SHA-256 before use.
 */
public final class PoseModelStore {
    public static final String LITE = "lite", FULL = "full", HEAVY = "heavy", AUTO = "auto";
    static final String HEAVY_URL = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/"
            + "pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task";
    static final String HEAVY_SHA256 = "64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b";
    public static final long HEAVY_BYTES = 30_664_242L;

    public interface Progress {
        void onProgress(int percent);
    }

    private PoseModelStore() { }

    public static File heavyFile(Context c) {
        return new File(new File(c.getFilesDir(), "pose"), "pose_landmarker_heavy.task");
    }

    public static boolean heavyReady(Context c) {
        File f = heavyFile(c);
        return f.isFile() && f.length() == HEAVY_BYTES;
    }

    public static void deleteHeavy(Context c) {
        //noinspection ResultOfMethodCallIgnored
        heavyFile(c).delete();
    }

    /**
     * Which model to load. Auto: Full on a GPU, Lite on CPU-only devices or while battery saver is on.
     * Heavy falls back to Full when it has not been downloaded.
     */
    public static String resolve(Context c, String pref, boolean gpu) {
        if (HEAVY.equals(pref)) return heavyReady(c) ? HEAVY : FULL;
        if (LITE.equals(pref) || FULL.equals(pref)) return pref;
        return gpu && !SystemEventsReceiver.powerSaving(c) ? FULL : LITE;
    }

    /** Asset path for lite/full, null for heavy (a file). */
    @Nullable
    public static String assetPath(String model) {
        if (LITE.equals(model)) return PoseEstimator.MODEL_LITE;
        if (FULL.equals(model)) return PoseEstimator.MODEL_FULL;
        return null;
    }

    /** Downloads and verifies the heavy model. Blocking; call off the main thread. */
    public static void downloadHeavy(Context c, OkHttpClient http, @Nullable Progress p, AtomicBoolean cancel) throws IOException {
        File out = heavyFile(c);
        File dir = out.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Storage unavailable");
        File tmp = new File(dir, out.getName() + ".part");
        Request req = new Request.Builder().url(HEAVY_URL).build();
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException(e);
        }
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful() || r.body() == null) throw new IOException("Download failed (" + r.code() + ")");
            long total = r.body().contentLength() > 0 ? r.body().contentLength() : HEAVY_BYTES;
            if (total > 64L * 1024 * 1024) throw new IOException("Unexpected model size");
            try (InputStream in = r.body().byteStream(); OutputStream o = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n, lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    if (cancel.get()) throw new IOException("Cancelled");
                    o.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    int pct = (int) Math.min(100, done * 100 / total);
                    if (p != null && pct != lastPct) p.onProgress(lastPct = pct);
                }
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        String hex = hex(sha.digest());
        if (!HEAVY_SHA256.equals(hex) || tmp.length() != HEAVY_BYTES) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("The downloaded model failed verification");
        }
        if (!tmp.renameTo(out)) throw new IOException("Could not save the model");
    }

    /** Verifies an existing heavy file (used in tests and before first use after an app update). */
    public static boolean verify(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) sha.update(buf, 0, n);
            return HEAVY_SHA256.equals(hex(sha.digest()));
        } catch (Exception e) {
            return false;
        }
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
