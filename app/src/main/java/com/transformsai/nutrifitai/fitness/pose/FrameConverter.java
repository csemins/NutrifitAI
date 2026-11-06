package com.transformsai.nutrifitai.fitness.pose;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

/**
 * Turns CameraX RGBA frames into upright bitmaps for the model WITHOUT allocating per frame.
 *
 * <p>Three reusable bitmaps rotate between the roles "being written" (camera thread), "pending"
 * (newest finished frame) and "in flight" (inside MediaPipe). A frame is never overwritten while the
 * model may still read it. The old path ({@code ImageProxy.toBitmap()} + {@code Bitmap.createBitmap}
 * with filtering) allocated two 1.2 MB bitmaps per frame and spent ~10 ms doing it.
 */
public final class FrameConverter {
    private final Bitmap[] pool = new Bitmap[3];
    private final Canvas[] canvases = new Canvas[3];
    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint();     // no filtering: 90-degree rotations are pixel exact
    private Bitmap src;
    private Bitmap pending, inflight;
    private long pendingTime, takenTime;

    public FrameConverter() {
        paint.setFilterBitmap(false);
    }

    /** Camera thread. Converts the frame and makes it the pending frame. Does NOT close the proxy. */
    public Bitmap convert(ImageProxy proxy, long arrivalUptimeMs) {
        int w = proxy.getWidth(), h = proxy.getHeight();
        int rot = proxy.getImageInfo().getRotationDegrees();
        ImageProxy.PlaneProxy plane = proxy.getPlanes()[0];
        Bitmap source;
        if (plane.getPixelStride() == 4 && plane.getRowStride() == w * 4) {
            if (src == null || src.getWidth() != w || src.getHeight() != h) {
                src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            ByteBuffer buf = plane.getBuffer();
            buf.rewind();
            src.copyPixelsFromBuffer(buf);       // RGBA bytes == ARGB_8888 memory layout
            source = src;
        } else {
            source = proxy.toBitmap();           // padded rows: let CameraX handle it
        }

        int ow = (rot == 90 || rot == 270) ? h : w;
        int oh = (rot == 90 || rot == 270) ? w : h;
        int slot = freeSlot();
        if (pool[slot] == null || pool[slot].getWidth() != ow || pool[slot].getHeight() != oh) {
            pool[slot] = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            canvases[slot] = new Canvas(pool[slot]);
        }
        matrix.reset();
        if (rot != 0) {
            matrix.postRotate(rot);
            if (rot == 90) matrix.postTranslate(h, 0);
            else if (rot == 180) matrix.postTranslate(w, h);
            else if (rot == 270) matrix.postTranslate(0, w);
        }
        canvases[slot].drawBitmap(source, matrix, paint);
        Bitmap out = pool[slot];
        synchronized (this) {
            pending = out;                        // any older pending frame is dropped (becomes free)
            pendingTime = arrivalUptimeMs;
        }
        return out;
    }

    private synchronized int freeSlot() {
        for (int i = 0; i < pool.length; i++) {
            if (pool[i] == null || (pool[i] != pending && pool[i] != inflight)) return i;
        }
        return 0;   // unreachable with 3 slots
    }

    /** Submit thread: takes the newest frame (or null) and marks it as in flight. */
    public synchronized Bitmap takePending() {
        Bitmap b = pending;
        pending = null;
        if (b != null) {
            inflight = b;
            takenTime = pendingTime;
        }
        return b;
    }

    /** Camera-arrival time of the frame last returned by {@link #takePending()}. */
    public synchronized long takenArrivalMs() { return takenTime; }

    /** Result/error thread: the model is done with the in-flight frame. */
    public synchronized void release() {
        inflight = null;
    }
}
