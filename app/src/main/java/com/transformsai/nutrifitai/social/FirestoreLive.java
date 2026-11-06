package com.transformsai.nutrifitai.social;

import androidx.lifecycle.LiveData;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A Firestore query as LiveData: the snapshot listener is attached only while someone observes (a visible
 * screen) and removed afterwards, so no listener outlives its screen. Firestore's local cache keeps the last
 * results available offline.
 */
public final class FirestoreLive<T> extends LiveData<List<T>> {
    private final Query query;
    private final Class<T> type;
    private ListenerRegistration reg;

    public FirestoreLive(Query query, Class<T> type) {
        this.query = query;
        this.type = type;
    }

    @Override
    protected void onActive() {
        reg = query.addSnapshotListener((snap, err) -> {
            if (err != null || snap == null) {
                if (getValue() == null) setValue(Collections.emptyList());
                return;
            }
            List<T> out = new ArrayList<>(snap.size());
            for (DocumentSnapshot d : snap.getDocuments()) {
                try {
                    T t = d.toObject(type);
                    if (t != null) out.add(t);
                } catch (RuntimeException ignored) {
                    // a malformed document is skipped, not fatal
                }
            }
            setValue(out);
        });
    }

    @Override
    protected void onInactive() {
        if (reg != null) reg.remove();
        reg = null;
    }

    /** One document as LiveData (null while missing or unreadable). */
    public static final class Doc<T> extends LiveData<T> {
        private final DocumentReference ref;
        private final Class<T> type;
        private ListenerRegistration reg;

        public Doc(DocumentReference ref, Class<T> type) {
            this.ref = ref;
            this.type = type;
        }

        @Override
        protected void onActive() {
            reg = ref.addSnapshotListener((snap, err) -> {
                if (err != null || snap == null || !snap.exists()) return;
                try {
                    setValue(snap.toObject(type));
                } catch (RuntimeException ignored) {
                    // malformed document
                }
            });
        }

        @Override
        protected void onInactive() {
            if (reg != null) reg.remove();
            reg = null;
        }
    }
}
