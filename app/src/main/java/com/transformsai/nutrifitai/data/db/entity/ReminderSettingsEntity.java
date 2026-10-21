package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.transformsai.nutrifitai.data.db.Syncable;

/** Times are minutes after local midnight. Firestore: users/{uid}/settings/reminders. */
@Entity(tableName = "reminder_settings")
public class ReminderSettingsEntity implements Syncable {
    @PrimaryKey
    @NonNull
    public String uid = "";
    public boolean waterEnabled = false;
    public int intervalMin = 120;
    public int startMin = 8 * 60;
    public int endMin = 20 * 60;
    public boolean cutoffEnabled = false;
    public int cutoffMin = 21 * 60;
    public boolean mealEnabled = false;
    public int breakfastMin = 9 * 60;
    public int lunchMin = 13 * 60 + 30;
    public int dinnerMin = 20 * 60 + 30;
    public long updatedAt;

    @Override
    public String syncId() {
        return "reminders";
    }

    @Override
    public long syncUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean syncDeleted() {
        return false;
    }
}
