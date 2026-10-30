package com.transformsai.nutrifitai.data.repo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.data.db.entity.StepDayEntity;
import com.transformsai.nutrifitai.sync.Collections;
import com.transformsai.nutrifitai.util.DateUtil;

import java.util.List;

/**
 * Daily walk tracker. The phone's step-counter sensor reports steps since boot; each reading adds
 * the increase since the previous reading to the day it was read on (a reboot resets the counter, so
 * a smaller value counts from zero). Readings come from the open app, a 15-minute WorkManager job and
 * walk sessions. Health Connect steps (Google Fit and other apps) are merged with max(), never added,
 * so the same steps are not counted twice.
 */
public class StepsRepository {
    private final AppGraph g;

    public StepsRepository(AppGraph g) {
        this.g = g;
    }

    public static boolean hasSensor(Context c) {
        SensorManager sm = c.getSystemService(SensorManager.class);
        return sm != null && sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null;
    }

    public static boolean hasPermission(Context c) {
        return Build.VERSION.SDK_INT < 29
                || ContextCompat.checkSelfPermission(c, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    public LiveData<StepDayEntity> observeDay(String uid, String date) {
        return g.db.activity().observeSteps(uid, date);
    }

    public LiveData<List<StepDayEntity>> observeRange(String uid, String from, String to) {
        return g.db.activity().observeStepsRange(uid, from, to);
    }

    /**
     * Records a raw step-counter value (steps since boot). Call on a background thread.
     *
     * @return steps added to today
     */
    public synchronized int onCounter(String uid, float counter, long now) {
        String today = DateUtil.dateOf(now);
        String base = g.prefs.stepBaseline(); // "uid|lastCounter"
        long value = (long) counter;
        long delta = 0;
        if (base != null) {
            String[] p = base.split("\\|");
            if (p.length == 2 && p[0].equals(uid)) {
                long last = Long.parseLong(p[1]);
                delta = value >= last ? value - last : value; // smaller = the phone rebooted
            }
        }
        g.prefs.setStepBaseline(uid + "|" + value);
        // ignore absurd jumps (sensor glitch / first reading after a long gap is still valid up to this)
        if (delta <= 0 || delta > 60_000) return 0;
        addSteps(uid, today, (int) delta, "sensor");
        return (int) delta;
    }

    /** Adds steps to a day (sensor deltas, manual entries). */
    public void addSteps(String uid, String date, int steps, String source) {
        g.db.runInTransaction(() -> {
            StepDayEntity s = g.db.activity().stepsById(StepDayEntity.idFor(uid, date));
            if (s == null) {
                s = new StepDayEntity();
                s.id = StepDayEntity.idFor(uid, date);
                s.uid = uid;
                s.date = date;
            }
            s.deleted = false;
            s.steps = Math.max(0, s.steps + steps);
            s.distanceM = s.steps * strideMetres(uid);
            s.source = s.source == null ? source : s.source;
            s.updatedAt = DateUtil.nowMillis();
            g.db.activity().upsertSteps(s);
            g.outbox.enqueue(uid, Collections.STEPS, s.id);
        });
        g.outbox.kickDebounced();
    }

    /** Health Connect total for a day: kept only when higher than what this phone counted. */
    public void mergeExternal(String uid, String date, int steps, String source) {
        if (steps <= 0) return;
        g.db.runInTransaction(() -> {
            StepDayEntity s = g.db.activity().stepsById(StepDayEntity.idFor(uid, date));
            if (s != null && s.steps >= steps) return;
            if (s == null) {
                s = new StepDayEntity();
                s.id = StepDayEntity.idFor(uid, date);
                s.uid = uid;
                s.date = date;
            }
            s.deleted = false;
            s.steps = steps;
            s.distanceM = steps * strideMetres(uid);
            s.source = source;
            s.updatedAt = DateUtil.nowMillis();
            g.db.activity().upsertSteps(s);
            g.outbox.enqueue(uid, Collections.STEPS, s.id);
        });
        g.outbox.kickDebounced();
    }

    /** Average stride from height (0.415 x height), 0.72 m when height is unknown. */
    public double strideMetres(String uid) {
        ProfileEntity p = g.db.profiles().get(uid);
        return p != null && p.heightCm > 100 ? p.heightCm * 0.00415 : 0.72;
    }

    /** Walking energy: about 0.04 kcal per step per 70 kg (scaled by body weight). An estimate. */
    public static double kcalForSteps(int steps, double kg) {
        return steps * 0.04 * (kg > 0 ? kg / 70.0 : 1.0);
    }

    @Nullable
    public StepDayEntity day(String uid, String date) {
        return g.db.activity().steps(uid, date);
    }
}
