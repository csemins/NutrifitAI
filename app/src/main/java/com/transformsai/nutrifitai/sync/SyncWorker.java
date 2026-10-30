package com.transformsai.nutrifitai.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.data.AppGraph;
import com.google.firebase.firestore.FirebaseFirestoreException;

/** Push pending outbox ops, then pull remote changes. Retries with backoff on transient failures. */
public class SyncWorker extends Worker {
    public static final String OUT_PUSHED = "pushed";
    public static final String OUT_PULLED = "pulled";
    public static final String OUT_ERROR = "error";

    public SyncWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    @NonNull
    @Override
    public Result doWork() {
        AppGraph g = App.graph();
        String uid = g.session.uid();
        if (uid == null) return Result.success();
        SyncEngine engine = new SyncEngine(g.context, g.db, uid);
        try {
            int pushed = engine.push();
            int pulled = engine.pull();
            if (pushed > 0) g.presence.announceChange(uid);
            return Result.success(new Data.Builder().putInt(OUT_PUSHED, pushed).putInt(OUT_PULLED, pulled).build());
        } catch (Exception e) {
            String why = SyncEngine.describe(e);
            Log.w("NutriFitSync", "Sync failed: " + why);
            Throwable root = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
            if (root instanceof FirebaseFirestoreException) {
                FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) root).getCode();
                if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                        || code == FirebaseFirestoreException.Code.INVALID_ARGUMENT
                        || code == FirebaseFirestoreException.Code.FAILED_PRECONDITION) {
                    // Not transient: keep ops in the outbox and surface the error instead of looping.
                    return Result.failure(new Data.Builder().putString(OUT_ERROR, code.name()).build());
                }
            }
            if (getRunAttemptCount() >= 8) {
                return Result.failure(new Data.Builder().putString(OUT_ERROR, why).build());
            }
            return Result.retry();
        }
    }
}
