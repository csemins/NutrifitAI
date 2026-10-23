package com.transformsai.nutrifitai.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Upsert;

import com.transformsai.nutrifitai.data.db.entity.WaterEntity;
import com.transformsai.nutrifitai.data.db.model.DayWater;

import java.util.List;

@Dao
public interface WaterDao {
    @Query("SELECT * FROM water_entries WHERE uid = :uid AND date = :date AND deleted = 0 ORDER BY loggedAt DESC")
    LiveData<List<WaterEntity>> observeDay(String uid, String date);

    @Query("SELECT COALESCE(SUM(ml), 0) FROM water_entries WHERE uid = :uid AND date = :date AND deleted = 0")
    LiveData<Integer> observeDayTotal(String uid, String date);

    @Query("SELECT COALESCE(SUM(ml), 0) FROM water_entries WHERE uid = :uid AND date = :date AND deleted = 0")
    int dayTotal(String uid, String date);

    @Query("SELECT date, SUM(ml) AS ml FROM water_entries WHERE uid = :uid AND deleted = 0 AND date BETWEEN :from AND :to "
            + "GROUP BY date ORDER BY date")
    List<DayWater> range(String uid, String from, String to);

    @Query("SELECT * FROM water_entries WHERE uid = :uid AND deleted = 0 AND date BETWEEN :from AND :to ORDER BY loggedAt")
    List<WaterEntity> entries(String uid, String from, String to);

    @Query("SELECT * FROM water_entries WHERE id = :id")
    WaterEntity byId(String id);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsert(WaterEntity w);

    @Query("DELETE FROM water_entries WHERE uid = :uid")
    void wipe(String uid);
}
