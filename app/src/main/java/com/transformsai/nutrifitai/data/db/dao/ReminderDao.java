package com.transformsai.nutrifitai.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Upsert;

import com.transformsai.nutrifitai.data.db.entity.ReminderSettingsEntity;

@Dao
public interface ReminderDao {
    @Query("SELECT * FROM reminder_settings WHERE uid = :uid")
    LiveData<ReminderSettingsEntity> observe(String uid);

    @Query("SELECT * FROM reminder_settings WHERE uid = :uid")
    ReminderSettingsEntity get(String uid);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsert(ReminderSettingsEntity s);

    @Query("DELETE FROM reminder_settings WHERE uid = :uid")
    void wipe(String uid);
}
