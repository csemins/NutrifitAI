package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.transformsai.nutrifitai.data.db.LocalOnly;
import com.transformsai.nutrifitai.data.db.Syncable;

/**
 * A logging event (one photo, one voice entry, one manual add) holding one or more items.
 * Planned entries never count toward consumed totals.
 */
@Entity(tableName = "meal_entries", indices = {@Index({"uid", "date"}), @Index({"uid", "status"})})
public class MealEntryEntity implements Syncable {
    public static final String STATUS_CONSUMED = "consumed";
    public static final String STATUS_PLANNED = "planned";

    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String uid = "";
    /** Local calendar date yyyy-MM-dd the meal belongs to. */
    @NonNull
    public String date = "";
    /** breakfast | lunch | dinner | snack */
    @NonNull
    public String mealType = "snack";
    @NonNull
    public String status = STATUS_CONSUMED;
    /** manual | camera | gallery | voice | text | recipe | assistant | import | copy */
    public String source;
    public String note;
    @LocalOnly
    public String imageLocalPath;
    public String imageRemotePath;
    public String aiAnalysisId;
    /** Epoch millis when it was eaten (null for planned). */
    public Long consumedAt;
    /** Zone id at the time of logging, kept for auditing timezone changes. */
    public String zoneId;
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
