package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.transformsai.nutrifitai.data.db.Syncable;

/**
 * A finished (or ended early) workout. Performed sets are stored as a JSON snapshot so later changes
 * to programs never rewrite history. Calories are an MET-based estimate (schema v3).
 */
@Entity(tableName = "workout_sessions", indices = {@Index({"uid", "date"})})
public class WorkoutSessionEntity implements Syncable {
    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String uid = "";
    /** Local date (yyyy-MM-dd) when the workout started. */
    @NonNull
    public String date = "";
    public long startedAt;
    public long endedAt;
    @NonNull
    public String title = "";
    public String planId;
    /** camera | manual | mixed */
    public String mode;
    public long activeMs;
    public int totalReps;
    public int exercisesDone;
    public double kcalEstimate;
    /** 0..1 share of targets achieved. */
    public double completion;
    /** JSON array of {exercise_id, name, hold, target, reps, held_ms, active_ms, camera, skipped}. */
    public String setsJson;
    // GPS walk (mode "walk" started with location on): route and moving stats. Steps of a walk are part of the
    // day's phone/Health Connect total already, so they are never added to the daily steps again.
    /** Route as an encoded polyline (Google polyline algorithm, 1e5 precision); null without GPS. */
    public String routePolyline;
    @androidx.room.ColumnInfo(defaultValue = "0")
    public double distanceM;
    @androidx.room.ColumnInfo(defaultValue = "0")
    public long movingMs;
    @androidx.room.ColumnInfo(defaultValue = "0")
    public double maxSpeedMps;
    @androidx.room.ColumnInfo(defaultValue = "0")
    public double elevGainM;
    @androidx.room.ColumnInfo(defaultValue = "0")
    public int steps;
    /** Per-kilometre split times in ms, JSON array. */
    public String splitsJson;
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
