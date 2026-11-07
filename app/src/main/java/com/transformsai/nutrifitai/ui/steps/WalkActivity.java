package com.transformsai.nutrifitai.ui.steps;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ActivityWalkBinding;
import com.transformsai.nutrifitai.databinding.ViewWalkStatBinding;
import com.transformsai.nutrifitai.fitness.RouteTracker;
import com.transformsai.nutrifitai.health.WalkService;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Permissions;
import com.transformsai.nutrifitai.ui.common.Ui;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Live walk on an OpenStreetMap map: the route draws as you move, with distance, time, pace, speed, steps and
 * climb on top. GO asks for location (and step counting / notifications when useful), then starts the walk
 * service; the walk keeps recording with the screen off. Finishing opens the summary of the saved walk.
 */
public class WalkActivity extends BaseActivity {
    private ActivityWalkBinding b;
    private WalkMap map;
    private boolean follow = true, wasRunning, openedSummary;
    /** A walk saved before this screen opened (its summary was already shown). */
    @Nullable private String staleSaved;
    private final NumberFormat nf = NumberFormat.getIntegerInstance();

    private final ActivityResultLauncher<String[]> perms = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissions);

    public static Intent intent(Context c) {
        return new Intent(c, WalkActivity.class);
    }

    @Override
    protected boolean alwaysDark() {
        return true; // the map style is dark
    }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        WalkMap.init(this);
        b = ActivityWalkBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.top, true, false);
        InsetsUtil.padding(b.bottom, false, true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        map = WalkMap.attach(b.map);
        label(b.sPace, R.string.walk_pace);
        label(b.sSpeed, R.string.walk_speed);
        label(b.sSteps, R.string.walk_steps);
        label(b.sElev, R.string.walk_elev);
        b.back.setOnClickListener(v -> finish());
        b.recentre.setOnClickListener(v -> {
            follow = true;
            WalkService.State st = WalkService.state().getValue();
            if (st != null) map.centre(st.lat, st.lon, true);
        });
        b.map.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) follow = false; // the user is exploring
            return false;
        });
        Ui.onClick(b.go, v -> {
            if (WalkService.running()) confirmFinish();
            else askAndStart();
        });
        pulse();
        WalkService.State now = WalkService.state().getValue();
        staleSaved = now == null ? null : now.savedSessionId;
        centreOnLastKnown();
        WalkService.state().observe(this, this::render);
    }

    /** Before a walk starts: show where the user is, from the last fix the phone already has. */
    @SuppressWarnings("MissingPermission")
    private void centreOnLastKnown() {
        if (!WalkService.locationAllowed(this)) return;
        LocationManager lm = getSystemService(LocationManager.class);
        if (lm == null) return;
        try {
            android.location.Location best = null;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                android.location.Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null && !WalkService.running()) {
                map.centre(best.getLatitude(), best.getLongitude(), false);
                map.show(java.util.Collections.emptyList(), best.getLatitude(), best.getLongitude(), Float.NaN);
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // no fix yet
        }
    }

    private static void label(ViewWalkStatBinding t, int res) {
        t.label.setText(res);
        t.value.setText("–");
    }

    /** Breathing ring around GO / Finish. */
    private void pulse() {
        b.pulse.animate().scaleX(1.25f).scaleY(1.25f).alpha(0f).setDuration(1400)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    if (b == null || isFinishing()) return;
                    b.pulse.setScaleX(1f);
                    b.pulse.setScaleY(1f);
                    b.pulse.setAlpha(0.35f);
                    pulse();
                }).start();
    }

    // ---------------------------------------------------------------- start
    private void askAndStart() {
        if (WalkService.saverOn(this)) {
            new MaterialAlertDialogBuilder(this).setTitle(R.string.walk_saver_title).setMessage(R.string.walk_saver_body)
                    .setPositiveButton(R.string.walk_saver_settings, (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
                        } catch (android.content.ActivityNotFoundException e) {
                            Ui.openAppSettings(this);
                        }
                    })
                    .setNegativeButton(R.string.cancel, null).show();
            return;
        }
        java.util.List<String> need = new java.util.ArrayList<>();
        if (!WalkService.locationAllowed(this)) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!Permissions.granted(this, Permissions.ACTIVITY)) need.add(Permissions.ACTIVITY);
        if (!Permissions.granted(this, Permissions.NOTIFICATIONS)) need.add(Permissions.NOTIFICATIONS);
        if (need.isEmpty()) {
            begin();
        } else if (!WalkService.locationAllowed(this) && Permissions.blocked(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            new MaterialAlertDialogBuilder(this).setTitle(R.string.perm_blocked_title)
                    .setMessage(getString(R.string.perm_why_location) + "\n\n" + getString(R.string.perm_blocked_body))
                    .setPositiveButton(R.string.open_settings, (d, w) -> Permissions.openSettings(this, Manifest.permission.ACCESS_FINE_LOCATION))
                    .setNegativeButton(R.string.walk_start, (d, w) -> begin()).show();
        } else {
            perms.launch(need.toArray(new String[0]));
        }
    }

    private void onPermissions(Map<String, Boolean> r) {
        getSharedPreferences("permissions", MODE_PRIVATE).edit().putBoolean(Manifest.permission.ACCESS_FINE_LOCATION, true).apply();
        begin();
    }

    private void begin() {
        boolean gps = WalkService.locationAllowed(this);
        boolean steps = Permissions.granted(this, Permissions.ACTIVITY);
        if (!gps && !steps) {
            Ui.snack(b.getRoot(), getString(R.string.perm_why_location));
            return;
        }
        if (gps && !locationEnabled()) {
            Ui.snack(b.getRoot(), getString(R.string.walk_no_gps)).setAction(R.string.walk_turn_on_location,
                    v -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        } else if (!gps) {
            Ui.snack(b.getRoot(), getString(R.string.walk_no_gps));
        }
        graph().prefs.setStepTracking(steps || graph().prefs.stepTracking());
        follow = true;
        openedSummary = false;
        WalkService.start(this);
    }

    private boolean locationEnabled() {
        LocationManager lm = getSystemService(LocationManager.class);
        return lm != null && (Build.VERSION.SDK_INT < 28 || lm.isLocationEnabled());
    }

    private void confirmFinish() {
        WalkService.State st = WalkService.state().getValue();
        String km = WalkService.formatKm(st == null ? 0 : st.distanceM);
        String time = WalkService.formatElapsed(st == null ? 0 : st.elapsedMs);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.walk_finish_q)
                .setMessage(getString(R.string.walk_finish_body, km, time))
                .setPositiveButton(R.string.walk_finish, (d, w) -> WalkService.stop(this))
                .setNegativeButton(R.string.walk_keep_going, null).show();
    }

    // ---------------------------------------------------------------- live
    private void render(@Nullable WalkService.State st) {
        if (b == null || st == null) return;
        if (st.savedSessionId != null && !st.savedSessionId.equals(staleSaved) && !openedSummary) { // just finished and saved
            openedSummary = true;
            startActivity(WalkSummaryActivity.intent(this, st.savedSessionId));
            finish();
            return;
        }
        if (wasRunning && !st.running && st.savedSessionId == null && st.stoppedBy == null && st.elapsedMs < 60_000
                && st.distanceM < 50) {
            Ui.snack(b.getRoot(), getString(R.string.walk_too_short));
        }
        wasRunning = st.running;
        b.go.setText(st.running ? R.string.walk_finish : R.string.walk_go);
        b.go.setBackgroundResource(st.running ? R.drawable.bg_walk_stop : R.drawable.bg_walk_go);
        b.go.setTextColor(getColor(st.running ? android.R.color.white : R.color.share_bg));
        b.pulse.setBackgroundResource(st.running ? R.drawable.bg_walk_stop : R.drawable.bg_walk_go);
        b.hint.setText(st.running ? R.string.walk_hint_running : R.string.walk_hint_start);

        b.distance.setText(WalkService.formatKm(st.distanceM));
        b.time.setText(WalkService.formatElapsed(st.running ? st.elapsedMs : 0));
        b.sPace.value.setText(RouteTracker.pace(st.avgSpeedMps));
        b.sSpeed.value.setText(String.format(Locale.getDefault(), "%.1f", st.speedMps * 3.6));
        b.sSteps.value.setText(nf.format(st.steps));
        b.sElev.value.setText(String.valueOf(Math.round(st.elevGainM)));
        b.stats.setContentDescription(getString(R.string.walk_finish_body, WalkService.formatKm(st.distanceM),
                WalkService.formatElapsed(st.elapsedMs)));

        if (!st.running) {
            b.gps.setVisibility(View.GONE);
        } else {
            b.gps.setVisibility(View.VISIBLE);
            b.gps.setText(!st.gps ? getString(R.string.walk_gps_off) : !st.located ? getString(R.string.walk_gps_searching)
                    : getString(R.string.walk_gps_ok, Math.round(Float.isNaN(st.accuracyM) ? 0 : st.accuracyM)));
        }
        map.show(st.route, st.lat, st.lon, st.accuracyM);
        if (follow && !Double.isNaN(st.lat)) map.centre(st.lat, st.lon, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        b.map.onResume();
    }

    @Override
    protected void onPause() {
        b.map.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (b != null) {
            b.pulse.animate().cancel();
            b.map.onDetach();
        }
        super.onDestroy();
    }
}
