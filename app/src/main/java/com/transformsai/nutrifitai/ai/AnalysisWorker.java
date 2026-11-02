package com.transformsai.nutrifitai.ai;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.db.entity.AiAnalysisEntity;
import com.transformsai.nutrifitai.data.remote.AiApiClient;
import com.transformsai.nutrifitai.data.remote.AiHttpException;
import com.transformsai.nutrifitai.util.DateUtil;

import java.io.File;
import java.util.Locale;

/**
 * Sends ONE user-confirmed image or text to the proxy, validates the structured response on the
 * device, and stores the outcome. There is no automatic retry loop: errors are surfaced with a
 * Retry button. Never fabricates a result.
 */
public class AnalysisWorker extends Worker {
    public static final String KEY_ID = "analysis_id";
    private final AiApiClient.Handle handle = new AiApiClient.Handle();

    public AnalysisWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    @NonNull
    @Override
    public Result doWork() {
        AppGraph g = App.graph();
        String id = getInputData().getString(KEY_ID);
        if (id == null) return Result.failure();
        AiAnalysisEntity a = g.db.ai().get(id);
        if (a == null || AiAnalysisEntity.CANCELLED.equals(a.status) || AiAnalysisEntity.SAVED.equals(a.status)) {
            return Result.success();
        }
        a.status = AiAnalysisEntity.RUNNING;
        a.updatedAt = DateUtil.nowMillis();
        g.db.ai().upsert(a);
        try {
            String locale = Locale.getDefault().toLanguageTag();
            String raw;
            if ("image".equals(a.inputType)) {
                if (a.imageLocalPath == null || !new File(a.imageLocalPath).exists()) {
                    fail(g, id, "missing_image", "The photo is no longer available. Please retake it.");
                    return Result.success();
                }
                raw = g.ai.analyzeImage(new File(a.imageLocalPath), a.note, locale, handle);
            } else {
                raw = g.ai.analyzeText(a.inputText == null ? "" : a.inputText, locale, handle);
            }
            if (isStopped()) return Result.success();
            AiResult r = AiResultParser.parse(raw);
            AiAnalysisEntity cur = g.db.ai().get(id);
            if (cur == null || AiAnalysisEntity.CANCELLED.equals(cur.status)) return Result.success();
            cur.resultJson = AiResultParser.toJson(r);
            cur.status = AiResult.STATUS_NON_FOOD.equals(r.status) ? AiAnalysisEntity.NON_FOOD
                    : AiResult.STATUS_UNCERTAIN.equals(r.status) ? AiAnalysisEntity.UNCERTAIN : AiAnalysisEntity.SUCCESS;
            cur.errorCode = null;
            cur.errorMessage = null;
            cur.updatedAt = DateUtil.nowMillis();
            g.db.ai().upsert(cur);
        } catch (AiHttpException e) {
            if (!"cancelled".equals(e.code)) fail(g, id, e.code, e.getMessage());
        } catch (AiParseException e) {
            fail(g, id, e.code, e.getMessage());
        } catch (Exception e) {
            fail(g, id, "unexpected", "Something went wrong while analysing. Please try again.");
        }
        return Result.success();
    }

    private static void fail(AppGraph g, String id, String code, String msg) {
        AiAnalysisEntity cur = g.db.ai().get(id);
        if (cur == null || AiAnalysisEntity.CANCELLED.equals(cur.status)) return;
        cur.status = AiAnalysisEntity.ERROR;
        cur.errorCode = code;
        cur.errorMessage = msg;
        cur.updatedAt = DateUtil.nowMillis();
        g.db.ai().upsert(cur);
    }

    @Override
    public void onStopped() {
        handle.cancel();
    }
}
