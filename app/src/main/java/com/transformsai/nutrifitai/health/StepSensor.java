package com.transformsai.nutrifitai.health;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The hardware step counter (steps since boot, counted by a low-power co-processor even while the
 * app is closed). {@link #readOnce} gets one value for background jobs; {@link #listen} streams live
 * values while a screen or a walk session is open. Always unregister (lifecycle owners do this in
 * onStop / onDestroy).
 */
public final class StepSensor implements SensorEventListener {
    public interface Listener {
        void onCounter(float stepsSinceBoot);
    }

    private final SensorManager sm;
    @Nullable private final Sensor sensor;
    @Nullable private Listener listener;

    public StepSensor(Context c) {
        sm = c.getSystemService(SensorManager.class);
        sensor = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
    }

    public boolean available() {
        return sensor != null;
    }

    public void listen(Listener l) {
        if (sensor == null || sm == null) return;
        listener = l;
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    public void stop() {
        if (sm != null) sm.unregisterListener(this);
        listener = null;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        Listener l = listener;
        if (l != null && e.values.length > 0) l.onCounter(e.values[0]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // not used
    }

    /** Blocking: waits up to timeoutMs for one counter value; NaN when unavailable. Not on the main thread. */
    public static float readOnce(Context c, long timeoutMs) {
        SensorManager sm = c.getSystemService(SensorManager.class);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (s == null) return Float.NaN;
        HandlerThread t = new HandlerThread("step-read");
        t.start();
        CountDownLatch done = new CountDownLatch(1);
        float[] value = {Float.NaN};
        SensorEventListener l = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                if (e.values.length > 0 && Float.isNaN(value[0])) {
                    value[0] = e.values[0];
                    done.countDown();
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        try {
            sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL, new Handler(t.getLooper()));
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sm.unregisterListener(l);
            t.quitSafely();
        }
        return value[0];
    }
}
