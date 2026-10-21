package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.transformsai.nutrifitai.data.db.Syncable;

@Entity(tableName = "weight_entries", indices = {@Index({"uid", "date"})})
public class WeightEntity implements Syncable {
    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String uid = "";
    @NonNull
    public String date = "";
    public long loggedAt;
    public double kg;
    /** Added in schema v2. */
    public String note;
    public long updatedAt;
    public boolean deleted;

    @Override
    public String syncId() {
        return id;
    }

    @Override
    public long syncUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean syncDeleted() {
        return deleted;
    }
}
