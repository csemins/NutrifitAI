package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;

/** Pull watermark per collection: the largest remote updatedAt applied locally. */
@Entity(tableName = "sync_meta", primaryKeys = {"uid", "collection"})
public class SyncMetaEntity {
    @NonNull
    public String uid = "";
    @NonNull
    public String collection = "";
    public long lastPulledUpdatedAt;
    public long lastSyncAt;
}
