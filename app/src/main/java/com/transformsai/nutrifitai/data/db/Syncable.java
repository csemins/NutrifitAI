package com.transformsai.nutrifitai.data.db;

/** A record that is mirrored to Cloud Firestore under users/{uid}/{collection}/{id}. */
public interface Syncable {
    String syncId();

    long syncUpdatedAt();

    boolean syncDeleted();
}
