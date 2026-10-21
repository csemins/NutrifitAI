package com.transformsai.nutrifitai.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Upsert;

import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;

@Dao
public interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE uid = :uid")
    LiveData<ProfileEntity> observe(String uid);

    @Query("SELECT * FROM profiles WHERE uid = :uid")
    ProfileEntity get(String uid);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsert(ProfileEntity p);

    @Query("DELETE FROM profiles WHERE uid = :uid")
    void wipe(String uid);
}
