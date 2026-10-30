package com.transformsai.nutrifitai.sync;

import androidx.annotation.Nullable;

/**
 * Last-writer-wins on the record's updatedAt (client clock, millisecond precision), with one
 * guard: a local record that still has a pending outbox op and is at least as new as the remote
 * copy is never overwritten (it will be uploaded next). Tombstones follow the same rule, so a newer
 * delete beats an older edit and a newer edit beats an older delete.
 */
public final class ConflictResolver {
    private ConflictResolver() {
    }

    public static boolean applyRemote(@Nullable Long localUpdatedAt, boolean localPending, long remoteUpdatedAt) {
        if (localUpdatedAt == null) return true;
        if (localPending && localUpdatedAt >= remoteUpdatedAt) return false;
        return remoteUpdatedAt > localUpdatedAt;
    }
}
