package com.transformsai.nutrifitai.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Upsert;

import com.transformsai.nutrifitai.data.db.entity.WorkoutPlanEntity;
import com.transformsai.nutrifitai.data.db.entity.WorkoutSessionEntity;
import com.transformsai.nutrifitai.data.db.model.DayWorkout;

import java.util.List;

@Dao
public interface WorkoutDao {
    @Query("SELECT * FROM workout_sessions WHERE uid = :uid AND deleted = 0 ORDER BY startedAt DESC LIMIT :limit")
    LiveData<List<WorkoutSessionEntity>> observeRecent(String uid, int limit);

    @Query("SELECT * FROM workout_sessions WHERE uid = :uid AND deleted = 0 AND date BETWEEN :from AND :to ORDER BY startedAt")
    List<WorkoutSessionEntity> range(String uid, String from, String to);

    @Query("SELECT * FROM workout_sessions WHERE uid = :uid AND deleted = 0 AND date BETWEEN :from AND :to ORDER BY startedAt")
    LiveData<List<WorkoutSessionEntity>> observeRange(String uid, String from, String to);

    @Query("SELECT date AS date, SUM(activeMs) AS activeMs, SUM(kcalEstimate) AS kcal, COUNT(*) AS sessions, SUM(totalReps) AS reps "
            + "FROM workout_sessions WHERE uid = :uid AND deleted = 0 AND date BETWEEN :from AND :to GROUP BY date ORDER BY date")
    List<DayWorkout> daily(String uid, String from, String to);

    @Query("SELECT date AS date, SUM(activeMs) AS activeMs, SUM(kcalEstimate) AS kcal, COUNT(*) AS sessions, SUM(totalReps) AS reps "
            + "FROM workout_sessions WHERE uid = :uid AND deleted = 0 AND date BETWEEN :from AND :to GROUP BY date ORDER BY date")
    LiveData<List<DayWorkout>> observeDaily(String uid, String from, String to);

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    WorkoutSessionEntity session(String id);

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    LiveData<WorkoutSessionEntity> observeSession(String id);

    /** Recorded walks, newest first (for the walk history). */
    @Query("SELECT * FROM workout_sessions WHERE uid = :uid AND deleted = 0 AND mode = 'walk' ORDER BY startedAt DESC LIMIT :limit")
    LiveData<List<WorkoutSessionEntity>> observeWalks(String uid, int limit);

    @Upsert
    void upsertSession(WorkoutSessionEntity s);

    @Query("SELECT * FROM workout_plans WHERE uid = :uid AND deleted = 0 ORDER BY updatedAt DESC")
    LiveData<List<WorkoutPlanEntity>> observePlans(String uid);

    @Query("SELECT * FROM workout_plans WHERE id = :id")
    WorkoutPlanEntity plan(String id);

    @Upsert
    void upsertPlan(WorkoutPlanEntity p);

    @Query("DELETE FROM workout_sessions WHERE uid = :uid")
    void wipeSessions(String uid);

    @Query("DELETE FROM workout_plans WHERE uid = :uid")
    void wipePlans(String uid);
}
