package com.transformsai.nutrifitai.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Upsert;

import com.transformsai.nutrifitai.data.db.entity.WeightEntity;

import java.util.List;

@Dao
public interface WeightDao {
    @Query("SELECT * FROM weight_entries WHERE uid = :uid AND deleted = 0 ORDER BY date DESC, loggedAt DESC")
    LiveData<List<WeightEntity>> observeAll(String uid);

    @Query("SELECT * FROM weight_entries WHERE uid = :uid AND deleted = 0 AND date BETWEEN :from AND :to ORDER BY date, loggedAt")
    List<WeightEntity> range(String uid, String from, String to);

    @Query("SELECT * FROM weight_entries WHERE uid = :uid AND deleted = 0 ORDER BY date DESC, loggedAt DESC LIMIT 1")
    WeightEntity latest(String uid);

    @Query("SELECT * FROM weight_entries WHERE uid = :uid AND deleted = 0 AND date < :date ORDER BY date DESC, loggedAt DESC LIMIT 1")
    WeightEntity latestBefore(String uid, String date);

    @Query("SELECT * FROM weight_entries WHERE id = :id")
    WeightEntity byId(String id);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsert(WeightEntity w);

    @Query("DELETE FROM weight_entries WHERE uid = :uid")
    void wipe(String uid);
}
