package com.transformsai.nutrifitai.health;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.fitness.RouteTracker;
import com.transformsai.nutrifitai.reminders.Notifications;
import com.transformsai.nutrifitai.reminders.SystemEventLog;
import com.transformsai.nutrifitai.reminders.SystemEventsReceiver;
import com.transformsai.nutrifitai.ui.steps.WalkActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A user-started walk, like a run tracker: a foreground service (types "health" and "location") so it keeps
 * going with the screen off. It records the GPS route (distance, pace, speed, elevation, km splits) and counts
 * steps from the phone's step counter, shows live numbers in an ongoing notification with a Finish action,
 * publishes live state to the walk screen, and saves the walk with its route when finished.
 *
 * No double counting: the walk's steps come from the same step-counter deltas that already make up the day's
 * total (merged with Google Fit / Health Connect by max, never added), so the walk only records them for
 * itself. It is written to Health Connect as an exercise session without a steps record.
 */
public class WalkService extends Service {
    public static final String ACTION_START = "com.transformsai.nutrifitai.walk.START";
    public static final String ACTION_STOP = "com.transformsai.nutrifitai.walk.STOP";
    public static final int NOTIFICATION_ID = 1010;
    private static final long GPS_INTERVAL_MS = 2000;

    /** Live walk, as seen by the walk screen. */
    public static final class State {
        public boolean running;
        public long startedAt;
        public long elapsedMs, movingMs;
        public int steps;
        public double distanceM, speedMps, avgSpeedMps, maxSpeedMps, elevGainM;
        /** Route so far as [lat, lon] pairs. */
        public List<double[]> route = new ArrayList<>();
        public boolean gps, located;
        public float accuracyM = Float.NaN;
        public double lat = Double.NaN, lon = Double.NaN;
        /** Set when the walk was ended by the system (battery saver / low battery), not by the user. */
        @Nullable public String stoppedBy;
        /** The saved session once a finished walk is stored (opens its summary). */
        @Nullable public String savedSessionId;
    }

    private static final MutableLiveData<State> STATE = new MutableLiveData<>(new State());

    public static LiveData<State> state() {
        return STATE;
    }

    public static void start(Context c) {
        ContextCompat.startForegroundService(c, new Intent(c, WalkService.class).setAction(ACTION_START));
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, WalkService.class).setAction(ACTION_STOP));
    }

    public static boolean running() {
        State s = STATE.getValue();
        return s != null && s.running;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private StepSensor sensor;
    @Nullable private LocationManager locations;
    private final RouteTracker route = new RouteTracker();
    private float startCounter = Float.NaN;
    private long startWall, startUptime, lastDailyAt;
    private int steps;
    private boolean started, stopping, gpsOn, located;
    private float accuracy = Float.NaN;
    private double lat = Double.NaN, lon = Double.NaN;
    @Nullable private String stoppedBy;

    /**
     * While a walk runs (also in the background) the service listens for battery saver and low battery: GPS and the
     * step sensor drain the battery, so it stops, saves the walk and says why.
     */
    private final SystemEventsReceiver power = new SystemEventsReceiver(e -> {
        if (e == SystemEventsReceiver.Event.SAVER_ON || e == SystemEventsReceiver.Event.BATTERY_LOW) stopForPower(e);
    });

    private final LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location l) {
            located = true;
            accuracy = l.hasAccuracy() ? l.getAccuracy() : Float.NaN;
            lat = l.getLatitude();
            lon = l.getLongitude();
            route.add(lat, lon, l.hasAltitude() ? l.getAltitude() : Double.NaN, accuracy, l.getTime());
            publish();
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            gpsOn = true;
            publish();
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            gpsOn = false;
            located = false;
            publish();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            publish();
            if (Notifications.canPost(WalkService.this)) {
                try {
                    NotificationManagerCompat.from(WalkService.this).notify(NOTIFICATION_ID, notification());
                } catch (SecurityException ignored) {
                    // notifications revoked meanwhile
                }
            }
            main.postDelayed(this, 1000);
        }
    };

    public static boolean has(Context c, String perm) {
        return ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean locationAllowed(Context c) {
        return has(c, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            finishWalk();
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY; // already running
        AppGraph g = App.graph();
        String uid = g.session.uid();
        if (uid == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notifications.createChannels(this);
        if (saverOn(this)) { // never start while the phone is saving battery
            SystemEventLog.record(this, SystemEventsReceiver.Event.SAVER_ON, getString(R.string.sys_log_walk_blocked));
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean steps = Build.VERSION.SDK_INT < 29 || has(this, Manifest.permission.ACTIVITY_RECOGNITION);
        boolean gps = locationAllowed(this);
        int type = 0;
        if (Build.VERSION.SDK_INT >= 34) {
            if (steps) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
            if (gps) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        } else if (Build.VERSION.SDK_INT >= 29 && gps) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        if (!steps && !gps) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startWall = System.currentTimeMillis();
        startUptime = SystemClock.elapsedRealtime();
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type);
        } catch (RuntimeException e) { // permission revoked or background start not allowed
            stopSelf();
            return START_NOT_STICKY;
        }
        started = true;
        if (steps && stepSensorAvailable()) {
            sensor = new StepSensor(this);
            sensor.listen(v -> {
                if (Float.isNaN(startCounter)) startCounter = v;
                this.steps = (int) Math.max(0, v - startCounter);
                long now = System.currentTimeMillis();
                if (now - lastDailyAt > 30_000) { // keep today's total current without a write per step
                    lastDailyAt = now;
                    g.runIo(() -> g.steps.onCounter(uid, v, now));
                }
            });
        }
        if (gps) startGps();
        main.post(tick);
        power.register(this);
        return START_NOT_STICKY;
    }

    private boolean stepSensorAvailable() {
        return com.transformsai.nutrifitai.data.repo.StepsRepository.hasSensor(this);
    }

    @SuppressWarnings("MissingPermission") // checked by locationAllowed() before this is called
    private void startGps() {
        locations = getSystemService(LocationManager.class);
        if (locations == null) return;
        gpsOn = locations.isProviderEnabled(LocationManager.GPS_PROVIDER);
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, gpsListener, Looper.getMainLooper());
            Location last = locations.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null && System.currentTimeMillis() - last.getTime() < 60_000) { // centre the map quickly
                lat = last.getLatitude();
                lon = last.getLongitude();
            }
        } catch (SecurityException | IllegalArgumentException e) {
            locations = null;
            gpsOn = false;
        }
    }

    private void publish() {
        State s = new State();
        s.running = started && !stopping;
        s.startedAt = startWall;
        s.elapsedMs = SystemClock.elapsedRealtime() - startUptime;
        s.movingMs = route.movingMs();
        s.steps = steps;
        s.distanceM = route.distanceM();
        s.speedMps = route.currentSpeedMps();
        s.avgSpeedMps = route.averageSpeedMps();
        s.maxSpeedMps = route.maxSpeedMps();
        s.elevGainM = route.elevationGainM();
        for (RouteTracker.Point p : route.points()) s.route.add(new double[]{p.lat, p.lon});
        s.gps = gpsOn && locations != null;
        s.located = located;
        s.accuracyM = accuracy;
        s.lat = lat;
        s.lon = lon;
        STATE.setValue(s);
    }

    public static boolean saverOn(Context c) {
        android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
        return pm != null && pm.isPowerSaveMode();
    }

    /** Battery saver turned on (or the battery is low): save the walk so far, stop, and tell the user. */
    private void stopForPower(SystemEventsReceiver.Event e) {
        if (stopping) return;
        boolean saver = e == SystemEventsReceiver.Event.SAVER_ON;
        stoppedBy = getString(saver ? R.string.walk_stopped_saver : R.string.walk_stopped_battery);
        int walked = steps;
        SystemEventLog.record(this, e, getString(R.string.sys_log_walk_stopped, walked));
        finishWalk();
        if (!Notifications.canPost(this)) return;
        Notification n = new NotificationCompat.Builder(this, Notifications.CH_TRAINING)
                .setSmallIcon(R.drawable.ic_steps)
                .setContentTitle(stoppedBy)
                .setContentText(getString(R.string.walk_stopped_body, walked))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(getString(R.string.walk_stopped_body, walked)))
                .setAutoCancel(true)
                .setContentIntent(openIntent(12))
                .build();
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID + 1, n);
        } catch (SecurityException ignored) {
            // notifications revoked meanwhile
        }
    }

    private PendingIntent openIntent(int code) {
        return PendingIntent.getActivity(this, code, new Intent(this, WalkActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification notification() {
        long ms = startUptime == 0 ? 0 : SystemClock.elapsedRealtime() - startUptime;
        PendingIntent stop = PendingIntent.getService(this, 11, new Intent(this, WalkService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String body = getString(R.string.walk_notif_live, formatKm(route.distanceM()), formatElapsed(ms),
                RouteTracker.pace(route.averageSpeedMps()), steps);
        return new NotificationCompat.Builder(this, Notifications.CH_TRAINING)
                .setSmallIcon(R.drawable.ic_steps)
                .setContentTitle(getString(R.string.walk_notif_title))
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setUsesChronometer(true)
                .setWhen(startWall == 0 ? System.currentTimeMillis() : startWall)
                .setContentIntent(openIntent(10))
                .addAction(R.drawable.ic_close, getString(R.string.walk_finish), stop)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    public static String formatKm(double metres) {
        return String.format(Locale.getDefault(), "%.2f", metres / 1000.0);
    }

    public static String formatElapsed(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private void finishWalk() {
        if (stopping) return;
        stopping = true;
        main.removeCallbacks(tick);
        power.unregister(this);
        if (sensor != null) sensor.stop();
        if (locations != null) locations.removeUpdates(gpsListener);
        long end = System.currentTimeMillis();
        int walked = steps;
        State s = new State();
        s.running = false;
        s.steps = walked;
        s.stoppedBy = stoppedBy;
        s.elapsedMs = end - startWall;
        s.distanceM = route.distanceM();
        boolean worthSaving = started && (end - startWall >= 60_000 || route.distanceM() >= 50);
        if (worthSaving) {
            App.graph().workouts.saveWalk(startWall, end, walked, route, r -> {
                if (r.ok() && r.value != null) {
                    State done = new State();
                    done.steps = walked;
                    done.stoppedBy = stoppedBy;
                    done.distanceM = route.distanceM();
                    done.savedSessionId = r.value.id;
                    STATE.setValue(done);
                }
            });
        }
        sensor = null;
        locations = null;
        STATE.setValue(s);
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        power.unregister(this);
        if (sensor != null) sensor.stop();
        if (locations != null) locations.removeUpdates(gpsListener);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
