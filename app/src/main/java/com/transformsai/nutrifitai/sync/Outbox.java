package com.transformsai.nutrifitai.sync;

import android.content.Context;

import com.transformsai.nutrifitai.data.db.AppDatabase;
import com.transformsai.nutrifitai.data.db.entity.SyncOpEntity;
import com.transformsai.nutrifitai.util.DateUtil;

/** Records a pending upload. Call inside the same Room transaction as the local write. */
public final class Outbox {
    private final Context context;
    private final AppDatabase db;

    public Outbox(Context c, AppDatabase db) {
        this.context = c.getApplicationContext();
        this.db = db;
    }

    public void enqueue(String uid, String collection, String docId) {
        SyncOpEntity op = new SyncOpEntity();
        op.uid = uid;
        op.collection = collection;
        op.docId = docId;
        op.op = SyncOpEntity.OP_UPSERT;
        op.queuedAt = DateUtil.nowMillis();
        db.sync().enqueue(op);
    }

    /** Ask WorkManager to upload soon (after the transaction has committed). */
    public void kick() {
        SyncScheduler.requestSync(context);
    }

    /** Batched upload for frequent small writes such as step counts. */
    public void kickDebounced() {
        SyncScheduler.requestSyncSoon(context);
    }
}
