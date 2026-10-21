package com.transformsai.nutrifitai.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Upsert;

import com.transformsai.nutrifitai.data.db.entity.AiAnalysisEntity;

import java.util.List;

@Dao
public interface AiDao {
    @Query("SELECT * FROM ai_analyses WHERE id = :id")
    LiveData<AiAnalysisEntity> observe(String id);

    @Query("SELECT * FROM ai_analyses WHERE id = :id")
    AiAnalysisEntity get(String id);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsert(AiAnalysisEntity a);

    @Query("SELECT imageLocalPath FROM ai_analyses WHERE uid = :uid AND imageLocalPath IS NOT NULL")
    List<String> localImages(String uid);

    @Query("DELETE FROM ai_analyses WHERE uid = :uid")
    void wipe(String uid);
}
