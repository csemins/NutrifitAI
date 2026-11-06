package com.transformsai.nutrifitai.health;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.health.connect.AggregateRecordsRequest;
import android.health.connect.AggregateRecordsResponse;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.HealthPermissions;
import android.health.connect.InsertRecordsResponse;
import android.health.connect.TimeInstantRangeFilter;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.ExerciseSessionType;
import android.health.connect.datatypes.HydrationRecord;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.StepsRecord;
import android.health.connect.datatypes.WeightRecord;
import android.health.connect.datatypes.units.Mass;
import android.health.connect.datatypes.units.Volume;
import android.os.Build;
import android.os.OutcomeReceiver;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.db.entity.WaterEntity;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;
import com.transformsai.nutrifitai.data.db.entity.WorkoutSessionEntity;
import com.transformsai.nutrifitai.util.DateUtil;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Health Connect (Android 14+, the platform successor of Google Fit's APIs): reads daily steps that
 * Google Fit, Samsung Health, watches and other apps record, and writes NutriFit AI workouts, weight
 * and water so they appear there too. Writes use our record ids as clientRecordId, so a retry
 * updates the same record instead of duplicating it. Steps are merged with max(), never added.
 */
public final class HealthConnect {
    private static final String TAG = "HealthConnect";
    public static final String[] PERMISSIONS = Build.VERSION.SDK_INT >= 34 ? new String[]{
            HealthPermissions.READ_STEPS, HealthPermissions.WRITE_EXERCISE, HealthPermissions.WRITE_WEIGHT,
            HealthPermissions.WRITE_HYDRATION} : new String[0];
    private static final Executor EXEC = Executors.newSingleThreadExecutor();

    private HealthConnect() { }

    /** Health Connect is part of Android 14+ (on older versions it is a separate app this build does not use). */
    public static boolean available(Context c) {
        return Build.VERSION.SDK_INT >= 34 && c.getSystemService(HealthConnectManager.class) != null;
    }

    public static boolean granted(Context c) {
        if (!available(c)) return false;
        for (String p : PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(c, p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    public static boolean canReadSteps(Context c) {
        return available(c) && ContextCompat.checkSelfPermission(c, PERMISSIONS[0]) == PackageManager.PERMISSION_GRANTED;
    }

    /** Opens Health Connect's own permission screen (to review or revoke). */
    public static Intent manageIntent() {
        return Build.VERSION.SDK_INT >= 34 ? new Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, "com.transformsai.nutrifitai") : new Intent();
    }

    /** Blocking two-way sync (call from a worker). Returns a short status for the settings screen. */
    public static String syncBlocking(Context c, AppGraph g, String uid) {
        if (Build.VERSION.SDK_INT < 34 || !available(c)) return "unavailable";
        return Api34.sync(c, g, uid);
    }

    @RequiresApi(34)
    private static final class Api34 {
        static String sync(Context c, AppGraph g, String uid) {
            HealthConnectManager hc = c.getSystemService(HealthConnectManager.class);
            int read = 0, written = 0;
            ZoneId zone = DateUtil.zone();
            // 1) steps for the last 7 days
            if (canReadSteps(c)) {
                LocalDate today = DateUtil.todayDate();
                for (int i = 6; i >= 0; i--) {
                    LocalDate d = today.minusDays(i);
                    Instant start = d.atStartOfDay(zone).toInstant();
                    Instant end = i == 0 ? Instant.now() : d.plusDays(1).atStartOfDay(zone).toInstant();
                    Long steps = aggregateSteps(hc, start, end);
                    if (steps != null && steps > 0) {
                        g.steps.mergeExternal(uid, DateUtil.iso(d), (int) Math.min(Integer.MAX_VALUE, steps), "health_connect");
                        read++;
                    }
                }
            }
            // 2) our new or changed records since the last sync
            long since = g.prefs.healthLastSync();
            long now = System.currentTimeMillis();
            List<Record> out = new ArrayList<>();
            String from = DateUtil.shift(DateUtil.today(), -30);
            if (granted(c, HealthPermissions.WRITE_EXERCISE)) {
                for (WorkoutSessionEntity s : g.db.workouts().range(uid, from, DateUtil.today())) {
                    if (s.updatedAt <= since || s.deleted || s.endedAt <= s.startedAt) continue;
                    // a session only: no steps or distance records, so Google Fit and others never count the walk twice
                    int type = "walk".equals(s.mode) ? ExerciseSessionType.EXERCISE_SESSION_TYPE_WALKING
                            : ExerciseSessionType.EXERCISE_SESSION_TYPE_CALISTHENICS;
                    out.add(new ExerciseSessionRecord.Builder(meta(s.id), Instant.ofEpochMilli(s.startedAt),
                            Instant.ofEpochMilli(s.endedAt), type).setTitle(s.title).build());
                }
            }
            if (granted(c, HealthPermissions.WRITE_WEIGHT)) {
                for (WeightEntity w : g.db.weights().range(uid, from, DateUtil.today())) {
                    if (w.updatedAt <= since || w.deleted || w.kg <= 0) continue;
                    out.add(new WeightRecord.Builder(meta(w.id), Instant.ofEpochMilli(w.loggedAt), Mass.fromGrams(w.kg * 1000)).build());
                }
            }
            if (granted(c, HealthPermissions.WRITE_HYDRATION)) {
                for (WaterEntity w : g.db.water().entries(uid, from, DateUtil.today())) {
                    if (w.updatedAt <= since || w.deleted || w.ml <= 0) continue;
                    Instant t = Instant.ofEpochMilli(w.loggedAt);
                    out.add(new HydrationRecord.Builder(meta(w.id), t, t.plusSeconds(1), Volume.fromLiters(w.ml / 1000.0)).build());
                }
            }
            boolean ok = true;
            if (!out.isEmpty()) {
                ok = insert(hc, out);
                if (ok) written = out.size();
            }
            if (ok) g.prefs.setHealthLastSync(now);
            return "read " + read + " days, wrote " + written;
        }

        static boolean granted(Context c, String p) {
            return ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED;
        }

        static Metadata meta(String clientId) {
            return new Metadata.Builder().setClientRecordId("nutrifit-" + clientId).build();
        }

        static Long aggregateSteps(HealthConnectManager hc, Instant start, Instant end) {
            AggregateRecordsRequest<Long> req = new AggregateRecordsRequest.Builder<Long>(
                    new TimeInstantRangeFilter.Builder().setStartTime(start).setEndTime(end).build())
                    .addAggregationType(StepsRecord.STEPS_COUNT_TOTAL).build();
            CountDownLatch done = new CountDownLatch(1);
            Long[] value = {null};
            hc.aggregate(req, EXEC, new OutcomeReceiver<AggregateRecordsResponse<Long>, HealthConnectException>() {
                @Override
                public void onResult(AggregateRecordsResponse<Long> r) {
                    value[0] = r.get(StepsRecord.STEPS_COUNT_TOTAL);
                    done.countDown();
                }

                @Override
                public void onError(HealthConnectException e) {
                    Log.w(TAG, "aggregate failed: " + e.getErrorCode());
                    done.countDown();
                }
            });
            await(done);
            return value[0];
        }

        static boolean insert(HealthConnectManager hc, List<Record> records) {
            CountDownLatch done = new CountDownLatch(1);
            boolean[] ok = {false};
            hc.insertRecords(records, EXEC, new OutcomeReceiver<InsertRecordsResponse, HealthConnectException>() {
                @Override
                public void onResult(InsertRecordsResponse r) {
                    ok[0] = true;
                    done.countDown();
                }

                @Override
                public void onError(HealthConnectException e) {
                    Log.w(TAG, "insert failed: " + e.getErrorCode());
                    done.countDown();
                }
            });
            await(done);
            return ok[0];
        }

        static void await(CountDownLatch l) {
            try {
                l.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
