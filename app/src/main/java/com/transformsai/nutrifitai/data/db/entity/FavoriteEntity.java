package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;

import com.transformsai.nutrifitai.data.db.Syncable;

@Entity(tableName = "favorites", primaryKeys = {"uid", "foodId"})
public class FavoriteEntity implements Syncable {
    @NonNull
    public String uid = "";
    @NonNull
    public String foodId = "";
    public long createdAt;
    public long updatedAt;
    public boolean deleted;

    @Override
    public String syncId() {
        return foodId;
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
