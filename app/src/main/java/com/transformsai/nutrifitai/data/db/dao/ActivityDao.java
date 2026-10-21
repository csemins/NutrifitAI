package com.transformsai.nutrifitai.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.transformsai.nutrifitai.data.db.entity.FastEntity;
import com.transformsai.nutrifitai.data.db.entity.StepDayEntity;

import java.util.List;

/** Daily steps and fasting windows (schema v4). */
@Dao
public interface ActivityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertSteps(StepDayEntity s);

    @Query("SELECT * FROM daily_steps WHERE id = :id")
    StepDayEntity stepsById(String id);

    @Query("SELECT * FROM daily_steps WHERE uid = :uid AND date = :date AND deleted = 0")
    StepDayEntity steps(String uid, String date);

    @Query("SELECT * FROM daily_steps WHERE uid = :uid AND date = :date AND deleted = 0")
    LiveData<StepDayEntity> observeSteps(String uid, String date);

    @Query("SELECT * FROM daily_steps WHERE uid = :uid AND deleted = 0 AND date BETWEEN :from AND :to ORDER BY date")
    List<StepDayEntity> stepsRange(String uid, String from, String to);

    @Query("SELECT * FROM daily_steps WHERE uid = :uid AND deleted = 0 AND date BETWEEN :from AND :to ORDER BY date")
    LiveData<List<StepDayEntity>> observeStepsRange(String uid, String from, String to);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertFast(FastEntity f);

    @Query("SELECT * FROM fasts WHERE id = :id")
    FastEntity fast(String id);

    @Query("SELECT * FROM fasts WHERE uid = :uid AND deleted = 0 AND endAt = 0 ORDER BY startAt DESC LIMIT 1")
    FastEntity runningFast(String uid);

    @Query("SELECT * FROM fasts WHERE uid = :uid AND deleted = 0 AND endAt = 0 ORDER BY startAt DESC LIMIT 1")
    LiveData<FastEntity> observeRunningFast(String uid);

    @Query("SELECT * FROM fasts WHERE uid = :uid AND deleted = 0 ORDER BY startAt DESC LIMIT :limit")
    LiveData<List<FastEntity>> observeFasts(String uid, int limit);

    @Query("SELECT * FROM fasts WHERE uid = :uid AND deleted = 0 AND date BETWEEN :from AND :to ORDER BY startAt")
    List<FastEntity> fastsRange(String uid, String from, String to);

    @Query("DELETE FROM daily_steps WHERE uid = :uid")
    void wipeSteps(String uid);

    @Query("DELETE FROM fasts WHERE uid = :uid")
    void wipeFasts(String uid);
}
