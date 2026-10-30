package com.transformsai.nutrifitai.data.repo;

import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.db.entity.ReminderSettingsEntity;
import com.transformsai.nutrifitai.reminders.ReminderScheduler;
import com.transformsai.nutrifitai.sync.Collections;
import com.transformsai.nutrifitai.util.DateUtil;

public class ReminderRepository {
    private final AppGraph g;

    public ReminderRepository(AppGraph g) {
        this.g = g;
    }

    public LiveData<ReminderSettingsEntity> observe(String uid) {
        return g.db.reminders().observe(uid);
    }

    public static ReminderSettingsEntity defaults(String uid) {
        ReminderSettingsEntity s = new ReminderSettingsEntity();
        s.uid = uid;
        return s;
    }

    public void save(ReminderSettingsEntity s) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            s.uid = uid;
            s.updatedAt = DateUtil.nowMillis();
            g.db.runInTransaction(() -> {
                g.db.reminders().upsert(s);
                g.outbox.enqueue(uid, Collections.SETTINGS, "reminders");
            });
            g.outbox.kick();
            ReminderScheduler.reschedule(g.context, s);
        });
    }
}
