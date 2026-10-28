package com.transformsai.nutrifitai.data.repo;

import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.data.db.entity.FastEntity;
import com.transformsai.nutrifitai.sync.Collections;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Ids;

import java.util.List;

/**
 * Intermittent-fasting timer: one running fast at a time, stored in Room (synced), so the timer
 * survives restarts and shows on other devices. The app does not recommend fasting; it only times
 * a window the user chose.
 */
public class FastingRepository {
    public static final int[] PRESETS = {12, 14, 16, 18, 20};
    private final AppGraph g;

    public FastingRepository(AppGraph g) {
        this.g = g;
    }

    public LiveData<FastEntity> observeRunning(String uid) {
        return g.db.activity().observeRunningFast(uid);
    }

    public LiveData<List<FastEntity>> observeHistory(String uid) {
        return g.db.activity().observeFasts(uid, 20);
    }

    public void start(int hours, long startAt, Result.Callback<FastEntity> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            FastEntity running = g.db.activity().runningFast(uid);
            if (running != null) {
                g.postResult(cb, Result.ok(running)); // never two fasts at once (double taps)
                return;
            }
            FastEntity f = new FastEntity();
            f.id = Ids.newId();
            f.uid = uid;
            f.startAt = Math.min(startAt, DateUtil.nowMillis());
            f.date = DateUtil.dateOf(f.startAt);
            f.targetHours = Math.max(1, Math.min(48, hours));
            f.updatedAt = DateUtil.nowMillis();
            g.db.runInTransaction(() -> {
                g.db.activity().upsertFast(f);
                g.outbox.enqueue(uid, Collections.FASTS, f.id);
            });
            g.outbox.kick();
            g.postResult(cb, Result.ok(f));
        });
    }

    public void end(String id, Result.Callback<FastEntity> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            FastEntity f = g.db.activity().fast(id);
            if (f == null || f.endAt != 0) {
                g.postResult(cb, Result.fail("This fast has already ended."));
                return;
            }
            f.endAt = DateUtil.nowMillis();
            f.updatedAt = f.endAt;
            g.db.runInTransaction(() -> {
                g.db.activity().upsertFast(f);
                g.outbox.enqueue(uid, Collections.FASTS, f.id);
            });
            g.outbox.kick();
            g.postResult(cb, Result.ok(f));
        });
    }

    public void delete(String id) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            FastEntity f = g.db.activity().fast(id);
            if (f == null) return;
            f.deleted = true;
            f.updatedAt = DateUtil.nowMillis();
            g.db.runInTransaction(() -> {
                g.db.activity().upsertFast(f);
                g.outbox.enqueue(uid, Collections.FASTS, f.id);
            });
            g.outbox.kick();
        });
    }

    /** Fraction 0..1 of the target completed. */
    public static float progress(FastEntity f, long now) {
        long end = f.endAt == 0 ? now : f.endAt;
        long target = f.targetHours * 3_600_000L;
        return target <= 0 ? 0f : Math.max(0f, Math.min(1f, (end - f.startAt) / (float) target));
    }
}
