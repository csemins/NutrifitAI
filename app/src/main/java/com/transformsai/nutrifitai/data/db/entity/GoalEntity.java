package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.transformsai.nutrifitai.data.db.Syncable;

/**
 * Immutable goal snapshot. A new row is written whenever targets change, effective from
 * {@link #effectiveDate}. Historical days keep resolving to the snapshot that was active then.
 */
@Entity(tableName = "goals", indices = {@Index({"uid", "effectiveDate"})})
public class GoalEntity implements Syncable {
    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String uid = "";
    /** Local date yyyy-MM-dd. */
    @NonNull
    public String effectiveDate = "";
    public int kcal;
    public int proteinG;
    public int carbsG;
    public int fatG;
    public int fibreG;
    public int waterMl;
    /** mifflin_st_jeor | manual */
    public String method;
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
