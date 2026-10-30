package com.transformsai.nutrifitai.data.repo;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;
import com.transformsai.nutrifitai.sync.Collections;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Ids;

import java.util.List;

public class WeightRepository {
    private final AppGraph g;

    public WeightRepository(AppGraph g) {
        this.g = g;
    }

    public LiveData<List<WeightEntity>> observeAll(String uid) {
        return g.db.weights().observeAll(uid);
    }

    /** id == null creates a new entry. */
    public void save(@Nullable String id, String date, double kg, @Nullable String note) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            g.db.runInTransaction(() -> {
                WeightEntity w = id == null ? null : g.db.weights().byId(id);
                long now = DateUtil.nowMillis();
                if (w == null) {
                    w = new WeightEntity();
                    w.id = Ids.newId();
                    w.uid = uid;
                    w.loggedAt = now;
                }
                w.date = date;
                w.kg = kg;
                w.note = note;
                w.deleted = false;
                w.updatedAt = now;
                g.db.weights().upsert(w);
                g.outbox.enqueue(uid, Collections.WEIGHTS, w.id);
            });
            g.outbox.kick();
        });
    }

    public void setDeleted(String id, boolean deleted) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            g.db.runInTransaction(() -> {
                WeightEntity w = g.db.weights().byId(id);
                if (w == null) return;
                w.deleted = deleted;
                w.updatedAt = DateUtil.nowMillis();
                g.db.weights().upsert(w);
                g.outbox.enqueue(uid, Collections.WEIGHTS, id);
            });
            g.outbox.kick();
        });
    }
}
