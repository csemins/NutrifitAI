package com.transformsai.nutrifitai.data;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/** Current signed-in account. Every query is scoped to this uid (per-account isolation). */
public final class Session {
    private final MutableLiveData<String> uid = new MutableLiveData<>();

    public Session() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        uid.setValue(u == null ? null : u.getUid());
        FirebaseAuth.getInstance().addAuthStateListener(a -> {
            FirebaseUser cur = a.getCurrentUser();
            String next = cur == null ? null : cur.getUid();
            String prev = uid.getValue();
            if (next == null ? prev != null : !next.equals(prev)) uid.postValue(next);
        });
    }

    public LiveData<String> observeUid() {
        return uid;
    }

    /** Reads the live Firebase user so background threads always see the latest account. */
    @Nullable
    public String uid() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        return u == null ? null : u.getUid();
    }

    public String requireUid() {
        String u = uid();
        if (u == null) throw new IllegalStateException("Not signed in");
        return u;
    }

    @Nullable
    public String email() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        return u == null ? null : u.getEmail();
    }
}
