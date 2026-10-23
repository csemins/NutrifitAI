package com.transformsai.nutrifitai.data;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.transformsai.nutrifitai.util.DateUtil;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/** Emits the local date and re-emits at midnight or when the clock/timezone changes. */
public final class Today {
    private final MutableLiveData<String> today = new MutableLiveData<>(DateUtil.today());
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::refresh;

    public LiveData<String> observe() {
        return today;
    }

    public String get() {
        return DateUtil.today();
    }

    public void refresh() {
        String now = DateUtil.today();
        if (!now.equals(today.getValue())) today.setValue(now);
        handler.removeCallbacks(tick);
        ZonedDateTime n = ZonedDateTime.now(DateUtil.zone());
        ZonedDateTime midnight = LocalDate.now(DateUtil.zone()).plusDays(1).atStartOfDay(DateUtil.zone());
        handler.postDelayed(tick, Duration.between(n, midnight).toMillis() + 1000);
    }

    public void stop() {
        handler.removeCallbacks(tick);
    }
}
