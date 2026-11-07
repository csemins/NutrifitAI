package com.transformsai.nutrifitai.ui.steps;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.Gson;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.WorkoutSessionEntity;
import com.transformsai.nutrifitai.databinding.ActivityWalkSummaryBinding;
import com.transformsai.nutrifitai.databinding.ItemWalkSplitBinding;
import com.transformsai.nutrifitai.databinding.ItemWalkStatBinding;
import com.transformsai.nutrifitai.fitness.RouteTracker;
import com.transformsai.nutrifitai.health.WalkService;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.ShareCards;
import com.transformsai.nutrifitai.ui.common.Ui;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A recorded walk: its route on the map (start and finish marked), distance, moving and total time, pace,
 * average and top speed, climb, steps and energy, per-kilometre splits, and sharing as an image card.
 */
public class WalkSummaryActivity extends BaseActivity {
    private static final String EXTRA_ID = "session_id";

    private ActivityWalkSummaryBinding b;
    @Nullable private WorkoutSessionEntity walk;
    private List<double[]> route = java.util.Collections.emptyList();
    private WalkMap map;
    private boolean drawn;

    public static Intent intent(Context c, String sessionId) {
        return new Intent(c, WalkSummaryActivity.class).putExtra(EXTRA_ID, sessionId);
    }

    @Override
    protected boolean alwaysDark() {
        return true;
    }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        String id = getIntent().getStringExtra(EXTRA_ID);
        if (uidOrFinish() == null) return;
        if (id == null) {
            finish();
            return;
        }
        WalkMap.init(this);
        b = ActivityWalkSummaryBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.top, true, false);
        InsetsUtil.padding(b.bottom, false, true);
        map = WalkMap.attach(b.map);
        b.back.setOnClickListener(v -> finish());
        b.delete.setOnClickListener(v -> new MaterialAlertDialogBuilder(this).setTitle(R.string.walk_delete_q)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    graph().workouts.deleteSession(id);
                    finish();
                })
                .setNegativeButton(R.string.cancel, null).show());
        Ui.onClick(b.share, v -> share());
        graph().workouts.observeSession(id).observe(this, w -> {
            if (w == null || w.deleted) {
                finish();
                return;
            }
            walk = w;
            render(w);
        });
    }

    private void render(WorkoutSessionEntity w) {
        route = RouteTracker.decode(w.routePolyline);
        boolean hasRoute = route.size() >= 2;
        b.map.setVisibility(hasRoute ? View.VISIBLE : View.GONE);
        b.noRoute.setVisibility(hasRoute ? View.GONE : View.VISIBLE);
        if (hasRoute && !drawn) {
            drawn = true;
            map.showFinished(route);
            map.fit(route);
        }
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        b.kicker.setText(df.format(new Date(w.startedAt)));
        b.title.setText(w.title);
        b.distance.setText(WalkService.formatKm(w.distanceM));

        double avg = w.movingMs <= 0 ? 0 : w.distanceM / (w.movingMs / 1000.0);
        NumberFormat nf = NumberFormat.getIntegerInstance();
        b.grid.removeAllViews();
        tile(R.string.walk_s_time, WalkService.formatElapsed(w.movingMs > 0 ? w.movingMs : w.activeMs));
        tile(R.string.walk_s_elapsed, WalkService.formatElapsed(Math.max(0, w.endedAt - w.startedAt)));
        tile(R.string.walk_s_pace, getString(R.string.walk_value_pace, RouteTracker.pace(avg)));
        tile(R.string.walk_s_speed, getString(R.string.walk_value_kmh, String.format(Locale.getDefault(), "%.1f", avg * 3.6)));
        tile(R.string.walk_s_max, getString(R.string.walk_value_kmh, String.format(Locale.getDefault(), "%.1f", w.maxSpeedMps * 3.6)));
        tile(R.string.walk_s_elev, getString(R.string.walk_value_m, (int) Math.round(w.elevGainM)));
        tile(R.string.walk_s_steps, nf.format(w.steps));
        tile(R.string.walk_s_kcal, getString(R.string.walk_value_kcal, (int) Math.round(w.kcalEstimate)));
        renderSplits(w);
    }

    private void tile(int label, String value) {
        ItemWalkStatBinding t = ItemWalkStatBinding.inflate(getLayoutInflater(), b.grid, false);
        t.label.setText(label);
        t.value.setText(value);
        t.getRoot().setContentDescription(getString(label) + ": " + value);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        int m = (int) (5 * getResources().getDisplayMetrics().density);
        lp.setMargins(m, m, m, m);
        b.grid.addView(t.getRoot(), lp);
    }

    private void renderSplits(WorkoutSessionEntity w) {
        b.splits.removeAllViews();
        Long[] splits = null;
        try {
            splits = w.splitsJson == null ? null : new Gson().fromJson(w.splitsJson, Long[].class);
        } catch (RuntimeException ignored) {
            // malformed: no splits shown
        }
        boolean any = splits != null && splits.length > 0;
        b.splitsTitle.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any) return;
        long fastest = Long.MAX_VALUE, slowest = 0;
        for (Long ms : splits) {
            fastest = Math.min(fastest, ms);
            slowest = Math.max(slowest, ms);
        }
        for (int i = 0; i < splits.length; i++) {
            ItemWalkSplitBinding r = ItemWalkSplitBinding.inflate(getLayoutInflater(), b.splits, false);
            long ms = splits[i];
            r.km.setText(getString(R.string.walk_split_row, i + 1));
            r.pace.setText(getString(R.string.walk_value_pace, RouteTracker.pace(ms <= 0 ? 0 : 1000.0 / (ms / 1000.0))));
            // faster kilometres get longer bars
            float f = slowest == fastest ? 1f : 0.35f + 0.65f * (slowest - ms) / (float) (slowest - fastest);
            ((LinearLayout.LayoutParams) r.bar.getLayoutParams()).weight = f;
            ((LinearLayout.LayoutParams) r.rest.getLayoutParams()).weight = 1f - f + 0.0001f;
            r.getRoot().setContentDescription(r.km.getText() + ", " + r.pace.getText());
            b.splits.addView(r.getRoot());
        }
    }

    private void share() {
        WorkoutSessionEntity w = walk;
        if (w == null) return;
        double avg = w.movingMs <= 0 ? 0 : w.distanceM / (w.movingMs / 1000.0);
        ShareCards.Card c = new ShareCards.Card();
        c.kicker = getString(R.string.share_kicker_walk);
        c.title = w.title;
        c.subtitle = DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(w.startedAt));
        if (route.size() >= 2) c.route = route;
        else c.art = R.drawable.hero_walk;
        c.stat(WalkService.formatKm(w.distanceM), getString(R.string.unit_km))
                .stat(WalkService.formatElapsed(w.movingMs > 0 ? w.movingMs : w.activeMs), getString(R.string.walk_s_time))
                .stat(RouteTracker.pace(avg), getString(R.string.walk_pace))
                .stat(NumberFormat.getIntegerInstance().format(w.steps), getString(R.string.walk_steps));
        ShareCards.share(this, c, getString(R.string.share));
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
        if (b != null) b.map.onDetach();
        super.onDestroy();
    }
}
