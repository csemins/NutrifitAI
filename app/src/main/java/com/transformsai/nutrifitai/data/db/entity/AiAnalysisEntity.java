package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.transformsai.nutrifitai.data.db.LocalOnly;
import com.transformsai.nutrifitai.data.db.Syncable;

/** Tracks one AI analysis request so it survives process death. Never stores base64. */
@Entity(tableName = "ai_analyses", indices = {@Index("uid")})
public class AiAnalysisEntity implements Syncable {
    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String SUCCESS = "success";
    public static final String UNCERTAIN = "uncertain";
    public static final String NON_FOOD = "non_food";
    public static final String ERROR = "error";
    public static final String CANCELLED = "cancelled";
    public static final String SAVED = "saved";

    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String uid = "";
    /** image | text */
    public String inputType;
    public String status = QUEUED;
    public String note;
    public String inputText;
    @LocalOnly
    public String imageLocalPath;
    /** Validated, normalized result JSON (totals recomputed on device). */
    public String resultJson;
    public String errorCode;
    public String errorMessage;
    public String mealType;
    public String date;
    public long createdAt;
    public long updatedAt;
    public boolean deleted;

    @Override
    public String syncId() {
        return id;
    }

    @Override
    public long syncUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean syncDeleted() {
        return deleted;
    }
}
