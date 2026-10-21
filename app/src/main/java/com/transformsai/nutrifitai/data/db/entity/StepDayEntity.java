package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.transformsai.nutrifitai.data.db.Syncable;

/**
 * Steps walked on one local date (schema v4). One row per user and day, id = uid + "_" + date, so
 * repeated sensor / Health Connect updates overwrite instead of duplicating.
 */
@Entity(tableName = "daily_steps", indices = {@Index(value = {"uid", "date"}, unique = true)})
public class StepDayEntity implements Syncable {
    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String uid = "";
    /** Local date yyyy-MM-dd. */
    @NonNull
    public String date = "";
    public int steps;
    /** Estimated distance in metres (steps x stride from height). */
    public double distanceM;
    /** sensor | health_connect | manual */
    public String source;
    public long updatedAt;
    public boolean deleted;

    public static String idFor(String uid, String date) {
        return uid + "_" + date;
    }

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
