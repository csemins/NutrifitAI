package com.transformsai.nutrifitai.ui.fasting;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.FastEntity;
import com.transformsai.nutrifitai.data.repo.FastingRepository;
import com.transformsai.nutrifitai.databinding.ActivityFastingBinding;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.util.DateUtil;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.util.Calendar;
import java.util.List;

/**
 * Fasting timer: pick a window (12-20 h), start now or at an earlier time, watch progress, end it.
 * Stored in Room and synced, so it keeps running across restarts and devices. A fast-complete
 * notification is scheduled with WorkManager. Includes a plain safety note, no health claims.
 */
public class FastingActivity extends BaseActivity {
    private ActivityFastingBinding b;
    @Nullable private FastEntity running;
    private final Handler tick = new Handler(Looper.getMainLooper());
    private final Runnable every = new Runnable() {
        @Override
        public void run() {
            render();
            tick.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        String uid = uidOrFinish();
        if (uid == null) return;
        b = ActivityFastingBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.bar.getRoot(), true, false);
        InsetsUtil.padding(b.content, false, true);
        b.bar.title.setText(R.string.fasting_title);
        b.bar.back.setOnClickListener(v -> finish());
        b.ring.setColors(ContextCompat.getColor(this, R.color.nl_carbs), ContextCompat.getColor(this, R.color.nl_lime));
        int pref = graph().prefs.fastingHours();
        for (int h : FastingRepository.PRESETS) {
            Chip c = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
            c.setId(View.generateViewId());
            c.setText(getString(R.string.fasting_preset, h, 24 - h));
            c.setTag(h);
            c.setCheckable(true);
            c.setCheckedIconVisible(false);
            c.setChipBackgroundColorResource(R.color.chip_bg);
            c.setChipStrokeColorResource(R.color.chip_stroke);
            c.setChipStrokeWidth(getResources().getDisplayMetrics().density);
            c.setTextColor(getColorStateList(R.color.chip_text));
            b.presets.addView(c);
            c.setChecked(h == pref);
        }
        Ui.onClick(b.primaryAction, v -> {
            if (running != null) confirmEnd();
            else start(System.currentTimeMillis());
        });
        b.earlier.setOnClickListener(v -> pickStart());
        graph().fasting.observeRunning(uid).observe(this, f -> {
            running = f;
            render();
        });
        graph().fasting.observeHistory(uid).observe(this, this::renderHistory);
    }

    @Override
    protected void onStart() {
        super.onStart();
        tick.post(every);
    }

    @Override
    protected void onStop() {
        tick.removeCallbacks(every);
        super.onStop();
    }

    private int chosenHours() {
        Chip c = b.presets.findViewById(b.presets.getCheckedChipId());
        return c == null ? 16 : (int) c.getTag();
    }

    private void start(long at) {
        int h = chosenHours();
        graph().prefs.setFastingHours(h);
        graph().fasting.start(h, at, r -> {
            if (r.ok()) FastDoneWorker.schedule(this, r.value);
        });
    }

    private void pickStart() {
        if (running != null) return;
        Calendar now = Calendar.getInstance();
        MaterialTimePicker p = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(this) ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setHour(now.get(Calendar.HOUR_OF_DAY)).setMinute(0).setTitleText(R.string.fasting_started_earlier).build();
        p.addOnPositiveButtonClickListener(v -> {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, p.getHour());
            c.set(Calendar.MINUTE, p.getMinute());
            c.set(Calendar.SECOND, 0);
            if (c.getTimeInMillis() > System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, -1); // "8 pm" = last night
            start(c.getTimeInMillis());
        });
        p.show(getSupportFragmentManager(), "fast_start");
    }

    private void confirmEnd() {
        if (running == null) return;
        FastEntity f = running;
        new MaterialAlertDialogBuilder(this).setTitle(R.string.fasting_end_q)
                .setMessage(getString(R.string.fasting_end_body, hm(System.currentTimeMillis() - f.startAt)))
                .setPositiveButton(R.string.fasting_end, (d, w) -> graph().fasting.end(f.id, r -> FastDoneWorker.cancel(this)))
                .setNegativeButton(R.string.keep_going, null).show();
    }

    private void render() {
        if (b == null) return;
        long now = System.currentTimeMillis();
        boolean on = running != null;
        b.timerBox.setVisibility(on ? View.VISIBLE : View.GONE);
        b.idleArt.setVisibility(on ? View.GONE : View.VISIBLE);
        b.presets.setVisibility(on ? View.GONE : View.VISIBLE);
        b.presetsTitle.setVisibility(on ? View.GONE : View.VISIBLE);
        b.earlier.setVisibility(on ? View.GONE : View.VISIBLE);
        b.primaryAction.setText(on ? R.string.fasting_end : R.string.fasting_start);
        if (!on) {
            b.ring.setProgress(0, false);
            b.state.setText(R.string.fasting_idle);
            b.stateSub.setText(getString(R.string.fasting_idle_short, chosenHours(), 24 - chosenHours()));
            return;
        }
        long elapsed = now - running.startAt;
        long target = running.targetHours * 3_600_000L;
        b.ring.setProgress(FastingRepository.progress(running, now), false);
        b.elapsed.setText(hm(elapsed));
        b.target.setText(getString(R.string.fasting_of, running.targetHours));
        b.state.setText(getString(R.string.fasting_since, DateUtil.time(running.startAt)));
        long end = running.startAt + target;
        b.stateSub.setText(elapsed >= target ? getString(R.string.fasting_reached, DateUtil.time(end))
                : getString(R.string.fasting_ends, DateUtil.time(end), hm(target - elapsed)));
        b.ring.setContentDescription(getString(R.string.cd_fasting, hm(elapsed), running.targetHours));
    }

    private void renderHistory(@Nullable List<FastEntity> list) {
        b.history.removeAllViews();
        int shown = 0;
        if (list != null) for (FastEntity f : list) {
            if (f.endAt == 0 || shown >= 10) continue;
            shown++;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight((int) (48 * getResources().getDisplayMetrics().density));
            TextView l = new TextView(this, null, 0, R.style.NL_Text_Body);
            l.setText(DateUtil.monthDay(f.date) + "  ·  " + DateUtil.time(f.startAt));
            l.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            TextView r = new TextView(this, null, 0, R.style.NL_Text_BodyStrong);
            boolean reached = f.endAt - f.startAt >= f.targetHours * 3_600_000L;
            r.setText(hm(f.endAt - f.startAt) + (reached ? "  ✓" : "") + " / " + f.targetHours + " h");
            r.setTextColor(ContextCompat.getColor(this, reached ? R.color.nl_lime : R.color.nl_text_2));
            row.addView(l);
            row.addView(r);
            row.setOnLongClickListener(v -> {
                new MaterialAlertDialogBuilder(this).setTitle(R.string.fasting_delete_q)
                        .setPositiveButton(R.string.delete, (d, w) -> graph().fasting.delete(f.id))
                        .setNegativeButton(R.string.cancel, null).show();
                return true;
            });
            b.history.addView(row);
        }
        b.historyTitle.setVisibility(shown == 0 ? View.GONE : View.VISIBLE);
        b.history.setVisibility(shown == 0 ? View.GONE : View.VISIBLE);
    }

    static String hm(long ms) {
        long m = Math.max(0, ms) / 60000;
        return String.format(java.util.Locale.getDefault(), "%d:%02d", m / 60, m % 60);
    }
}
