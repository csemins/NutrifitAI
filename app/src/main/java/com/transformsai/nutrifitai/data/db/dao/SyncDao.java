package com.transformsai.nutrifitai.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.transformsai.nutrifitai.data.db.entity.SyncMetaEntity;
import com.transformsai.nutrifitai.data.db.entity.SyncOpEntity;

import java.util.List;

@Dao
public interface SyncDao {
    /** REPLACE on the unique (uid, collection, docId) index coalesces repeated edits. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long enqueue(SyncOpEntity op);

    @Query("SELECT * FROM sync_ops WHERE uid = :uid ORDER BY seq LIMIT :limit")
    List<SyncOpEntity> pending(String uid, int limit);

    @Query("SELECT COUNT(*) FROM sync_ops WHERE uid = :uid")
    LiveData<Integer> observePendingCount(String uid);

    @Query("SELECT COUNT(*) FROM sync_ops WHERE uid = :uid")
    int pendingCount(String uid);

    @Query("SELECT COUNT(*) FROM sync_ops WHERE uid = :uid AND collection = :collection AND docId = :docId")
    int hasPending(String uid, String collection, String docId);

    /** Removes the op only if it was not replaced by a newer edit while uploading. */
    @Query("DELETE FROM sync_ops WHERE seq = :seq")
    void ack(long seq);

    @Query("UPDATE sync_ops SET attempts = attempts + 1, lastError = :error WHERE seq = :seq")
    void fail(long seq, String error);

    @Query("SELECT * FROM sync_meta WHERE uid = :uid AND collection = :collection")
    SyncMetaEntity meta(String uid, String collection);

    @Query("SELECT MAX(lastSyncAt) FROM sync_meta WHERE uid = :uid")
    LiveData<Long> observeLastSync(String uid);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertMeta(SyncMetaEntity m);

    @Query("DELETE FROM sync_ops WHERE uid = :uid")
    void wipeOps(String uid);

    @Query("DELETE FROM sync_meta WHERE uid = :uid")
    void wipeMeta(String uid);
}
