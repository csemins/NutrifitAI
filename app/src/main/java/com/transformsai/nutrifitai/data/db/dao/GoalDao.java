package com.transformsai.nutrifitai.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Upsert;

import com.transformsai.nutrifitai.data.db.entity.GoalEntity;

import java.util.List;

@Dao
public interface GoalDao {
    String ACTIVE = "SELECT * FROM goals WHERE uid = :uid AND deleted = 0 AND effectiveDate <= :date "
            + "ORDER BY effectiveDate DESC, createdAt DESC LIMIT 1";

    @Query(ACTIVE)
    LiveData<GoalEntity> observeActive(String uid, String date);

    @Query(ACTIVE)
    GoalEntity active(String uid, String date);

    @Query("SELECT * FROM goals WHERE uid = :uid AND deleted = 0 ORDER BY effectiveDate ASC, createdAt ASC")
    List<GoalEntity> all(String uid);

    @Query("SELECT * FROM goals WHERE id = :id")
    GoalEntity byId(String id);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsert(GoalEntity g);

    @Query("DELETE FROM goals WHERE uid = :uid")
    void wipe(String uid);
}
