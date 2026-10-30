package com.transformsai.nutrifitai.data.repo;

import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.data.db.AppDatabase;
import com.transformsai.nutrifitai.data.db.entity.WaterEntity;
import com.transformsai.nutrifitai.data.db.model.DayWater;
import com.transformsai.nutrifitai.sync.Collections;
import com.transformsai.nutrifitai.sync.Outbox;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Ids;

import java.util.List;

public class WaterRepository {
    /** Common container sizes for quick-add: a glass, a bottle and a large bottle. */
    public static final int GLASS_ML = 200, BOTTLE_ML = 500, LARGE_BOTTLE_ML = 1000;

    public static final int MAX_ML = 5000;
    private final AppGraph g;

    public WaterRepository(AppGraph g) {
        this.g = g;
    }

    public LiveData<List<WaterEntity>> observeDay(String uid, String date) {
        return g.db.water().observeDay(uid, date);
    }

    public LiveData<Integer> observeTotal(String uid, String date) {
        return g.db.water().observeDayTotal(uid, date);
    }

    public void add(int ml, Result.Callback<String> cb) {
        g.runIo(() -> {
            String id = addBlocking(g.db, g.outbox, g.session.requireUid(), ml);
            g.outbox.kick();
            g.postResult(cb, Result.ok(id));
        });
    }

    /** Also used by the reminder notification action (no UI). */
    public static String addBlocking(AppDatabase db, Outbox outbox, String uid, int ml) {
        if (ml <= 0 || ml > MAX_ML) throw new IllegalArgumentException("ml out of range");
        WaterEntity w = new WaterEntity();
        long now = DateUtil.nowMillis();
        w.id = Ids.newId();
        w.uid = uid;
        w.loggedAt = now;
        w.date = DateUtil.today();
        w.ml = ml;
        w.updatedAt = now;
        db.runInTransaction(() -> {
            db.water().upsert(w);
            outbox.enqueue(uid, Collections.WATER, w.id);
        });
        return w.id;
    }

    public void update(String id, int ml) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            g.db.runInTransaction(() -> {
                WaterEntity w = g.db.water().byId(id);
                if (w == null) return;
                w.ml = ml;
                w.updatedAt = DateUtil.nowMillis();
                g.db.water().upsert(w);
                g.outbox.enqueue(uid, Collections.WATER, id);
            });
            g.outbox.kick();
        });
    }

    public void setDeleted(String id, boolean deleted) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            g.db.runInTransaction(() -> {
                WaterEntity w = g.db.water().byId(id);
                if (w == null) return;
                w.deleted = deleted;
                w.updatedAt = DateUtil.nowMillis();
                g.db.water().upsert(w);
                g.outbox.enqueue(uid, Collections.WATER, id);
            });
            g.outbox.kick();
        });
    }

    public void range(String from, String to, Result.Callback<List<DayWater>> cb) {
        g.runIo(() -> g.postResult(cb, Result.ok(g.db.water().range(g.session.requireUid(), from, to))));
    }
}
