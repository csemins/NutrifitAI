package com.transformsai.nutrifitai.data.repo;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.transformsai.nutrifitai.ai.AnalysisWorker;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.data.db.entity.AiAnalysisEntity;
import com.transformsai.nutrifitai.sync.Collections;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Ids;

/**
 * AI analysis jobs run in WorkManager and persist their state in Room, so a capture that is
 * being analysed survives rotation, backgrounding and process death. The UI observes the row.
 */
public class AnalysisRepository {
    private final AppGraph g;

    public AnalysisRepository(AppGraph g) {
        this.g = g;
    }

    public LiveData<AiAnalysisEntity> observe(String id) {
        return g.db.ai().observe(id);
    }

    public void createImage(String imagePath, @Nullable String note, String mealType, String date, Result.Callback<String> cb) {
        create("image", imagePath, null, note, mealType, date, cb);
    }

    public void createText(String text, String mealType, String date, Result.Callback<String> cb) {
        create("text", null, text, null, mealType, date, cb);
    }

    private void create(String type, @Nullable String image, @Nullable String text, @Nullable String note,
                        String mealType, String date, Result.Callback<String> cb) {
        g.runIo(() -> {
            AiAnalysisEntity a = new AiAnalysisEntity();
            a.id = Ids.newId();
            a.uid = g.session.requireUid();
            a.inputType = type;
            a.imageLocalPath = image;
            a.inputText = text;
            a.note = note;
            a.mealType = mealType;
            a.date = date;
            a.status = AiAnalysisEntity.QUEUED;
            a.createdAt = DateUtil.nowMillis();
            a.updatedAt = a.createdAt;
            g.db.ai().upsert(a);
            enqueue(a.id);
            g.postResult(cb, Result.ok(a.id));
        });
    }

    public void enqueue(String id) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(AnalysisWorker.class)
                .setInputData(new Data.Builder().putString(AnalysisWorker.KEY_ID, id).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("analysis")
                .build();
        WorkManager.getInstance(g.context).enqueueUniqueWork("analysis-" + id, ExistingWorkPolicy.REPLACE, req);
    }

    public void retry(String id) {
        g.runIo(() -> {
            AiAnalysisEntity a = g.db.ai().get(id);
            if (a == null) return;
            a.status = AiAnalysisEntity.QUEUED;
            a.errorCode = null;
            a.errorMessage = null;
            a.updatedAt = DateUtil.nowMillis();
            g.db.ai().upsert(a);
            enqueue(id);
        });
    }

    public void cancel(String id) {
        WorkManager.getInstance(g.context).cancelUniqueWork("analysis-" + id);
        g.runIo(() -> {
            AiAnalysisEntity a = g.db.ai().get(id);
            if (a == null || AiAnalysisEntity.SAVED.equals(a.status)) return;
            a.status = AiAnalysisEntity.CANCELLED;
            a.updatedAt = DateUtil.nowMillis();
            g.db.ai().upsert(a);
        });
    }

    /** Persist the user's edited result and mark the analysis as saved to the diary. */
    public void markSaved(String id, String editedJson) {
        g.runIo(() -> {
            AiAnalysisEntity a = g.db.ai().get(id);
            if (a == null) return;
            a.status = AiAnalysisEntity.SAVED;
            a.resultJson = editedJson;
            a.updatedAt = DateUtil.nowMillis();
            g.db.runInTransaction(() -> {
                g.db.ai().upsert(a);
                g.outbox.enqueue(a.uid, Collections.AI, a.id);
            });
            g.outbox.kick();
        });
    }
}
