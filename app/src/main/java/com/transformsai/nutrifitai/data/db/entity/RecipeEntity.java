package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.transformsai.nutrifitai.data.db.Syncable;

@Entity(tableName = "recipes", indices = {@Index("uid")})
public class RecipeEntity implements Syncable {
    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String uid = "";
    @NonNull
    public String name = "";
    /** Number of servings the full ingredient list makes. */
    public double servings = 1;
    public String notes;
    public long createdAt;
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
