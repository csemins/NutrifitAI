package com.transformsai.nutrifitai.data.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.transformsai.nutrifitai.data.db.entity.ImportBatchEntity;

import java.util.List;

@Dao
public interface ImportDao {
    @Insert
    void insert(ImportBatchEntity batch);

    @Query("SELECT * FROM import_batches WHERE uid = :uid ORDER BY importedAt DESC")
    List<ImportBatchEntity> all(String uid);

    @Query("DELETE FROM import_batches WHERE uid = :uid")
    void wipe(String uid);
}
