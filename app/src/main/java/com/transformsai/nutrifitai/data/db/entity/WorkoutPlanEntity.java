package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.transformsai.nutrifitai.data.db.Syncable;

/** A saved workout plan (AI-generated or user-built). Built-in programs live in code (schema v3). */
@Entity(tableName = "workout_plans", indices = {@Index("uid")})
public class WorkoutPlanEntity implements Syncable {
    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String uid = "";
    @NonNull
    public String title = "";
    public String summary;
    /** ai | user */
    public String source;
    public String level;
    public String focus;
    /** JSON array of {exercise_id, target, rest_seconds, cue}. */
    public String stepsJson;
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
