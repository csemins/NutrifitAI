package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Durable outbox row. One pending row per (uid, collection, docId): a newer local change
 * replaces the older row, so retries upload the latest state exactly once per document.
 */
@Entity(tableName = "sync_ops", indices = {@Index(value = {"uid", "collection", "docId"}, unique = true)})
public class SyncOpEntity {
    public static final String OP_UPSERT = "upsert";
    public static final String OP_DELETE = "delete";

    @PrimaryKey(autoGenerate = true)
    public long seq;
    @NonNull
    public String uid = "";
    @NonNull
    public String collection = "";
    @NonNull
    public String docId = "";
    @NonNull
    public String op = OP_UPSERT;
    public long queuedAt;
    public int attempts;
    public String lastError;
}
