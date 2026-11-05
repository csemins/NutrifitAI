package com.transformsai.nutrifitai.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** In-memory fixed-window limiter per uid and route (single-instance deployment). */
public final class RateLimiter {
    private static final long MIN = 60_000, DAY = 86_400_000;

    private static final class Window {
        long minStart, dayStart;
        int minCount, dayCount;
    }

    private final Map<String, Config.Limit> limits;
    private final LongSupplier now;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    /** Many users over a long uptime: old windows are dropped so memory stays bounded. */
    private static final int PRUNE_EVERY = 2048;
    private final java.util.concurrent.atomic.AtomicInteger takes = new java.util.concurrent.atomic.AtomicInteger();

    public RateLimiter(Map<String, Config.Limit> limits, LongSupplier now) {
        this.limits = limits;
        this.now = now;
    }

    /** @return 0 when allowed, otherwise seconds until the caller may retry. */
    public long take(String uid, String route) {
        Config.Limit lim = limits.get(route);
        if (lim == null) return 0;
        long t = now.getAsLong();
        if (takes.incrementAndGet() % PRUNE_EVERY == 0) prune(t);
        Window w = windows.computeIfAbsent(route + ":" + uid, k -> {
            Window x = new Window();
            x.minStart = t;
            x.dayStart = t;
            return x;
        });
        synchronized (w) {
            if (t - w.minStart >= MIN) {
                w.minStart = t;
                w.minCount = 0;
            }
            if (t - w.dayStart >= DAY) {
                w.dayStart = t;
                w.dayCount = 0;
            }
            if (w.minCount >= lim.perMinute) return Math.max(1, (w.minStart + MIN - t + 999) / 1000);
            if (w.dayCount >= lim.perDay) return Math.max(1, (w.dayStart + DAY - t + 999) / 1000);
            w.minCount++;
            w.dayCount++;
            return 0;
        }
    }

    /** Removes windows whose day has ended (they would be reset on next use anyway). */
    void prune(long t) {
        windows.entrySet().removeIf(e -> {
            Window w = e.getValue();
            synchronized (w) {
                return t - w.dayStart >= DAY;
            }
        });
    }

    int size() {
        return windows.size();
    }
}
