package com.transformsai.nutrifitai.call;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.webrtc.CapturerObserver;
import org.webrtc.JavaI420Buffer;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.YuvHelper;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * Video for a call taken from frames the app already has: during a challenge the workout camera feeds the pose
 * tracker, and the same frames (RGBA bitmaps) are sent to friends at up to 12 fps. Nothing opens the camera twice.
 */
public final class FrameCapturer implements VideoCapturer {
    private static final long MIN_GAP_MS = 80;

    @Nullable private CapturerObserver observer;
    private volatile boolean running;
    private long last;
    @Nullable private ByteBuffer rgba;

    @Override
    public void initialize(SurfaceTextureHelper helper, Context c, CapturerObserver observer) {
        this.observer = observer;
    }

    @Override
    public void startCapture(int width, int height, int fps) {
        running = true;
        if (observer != null) observer.onCapturerStarted(true);
    }

    @Override
    public void stopCapture() {
        running = false;
        if (observer != null) observer.onCapturerStopped();
    }

    @Override
    public void changeCaptureFormat(int width, int height, int fps) { }

    @Override
    public void dispose() {
        running = false;
        observer = null;
    }

    @Override
    public boolean isScreencast() {
        return false;
    }

    public boolean running() {
        return running;
    }

    /** Sends an upright ARGB_8888 frame (call from the camera thread). Frames arriving too fast are skipped. */
    public void push(Bitmap frame) {
        CapturerObserver o = observer;
        if (!running || o == null || frame.getConfig() != Bitmap.Config.ARGB_8888) return;
        long now = SystemClock.elapsedRealtime();
        if (now - last < MIN_GAP_MS) return;
        last = now;
        int w = frame.getWidth() & ~1, h = frame.getHeight() & ~1;
        int bytes = frame.getRowBytes() * frame.getHeight();
        if (rgba == null || rgba.capacity() < bytes) rgba = ByteBuffer.allocateDirect(bytes);
        rgba.clear();
        frame.copyPixelsToBuffer(rgba);
        rgba.rewind();
        JavaI420Buffer i420 = JavaI420Buffer.allocate(w, h);
        // Android's ARGB_8888 is R,G,B,A in memory, which libyuv calls "ABGR"
        YuvHelper.ABGRToI420(rgba, frame.getRowBytes(), i420.getDataY(), i420.getStrideY(), i420.getDataU(), i420.getStrideU(),
                i420.getDataV(), i420.getStrideV(), w, h);
        VideoFrame vf = new VideoFrame(i420, 0, TimeUnit.MILLISECONDS.toNanos(now));
        o.onFrameCaptured(vf);
        vf.release();
    }
}
