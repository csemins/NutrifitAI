package com.transformsai.nutrifitai.data.remote;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.transformsai.nutrifitai.BuildConfig;
import com.transformsai.nutrifitai.data.Prefs;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * AI access, kept entirely in Firebase. Each user owns a card aiAccess/{uid} {allowed, requestedAt, email, name};
 * security rules let only admins (or a verified pre-allowed email, aiAllowList/{email}) set allowed = true.
 * The server checks the same card before every AI call. Asking for access sets requestedAt once.
 */
public final class AiAccess {
    public enum State { UNKNOWN, ALLOWED, NOT_ENROLLED }

    private final Prefs prefs;
    private final MutableLiveData<State> state = 
            // demo build: no approval step, every signed-in account starts with AI on
            new MutableLiveData<>(BuildConfig.AI_OPEN ? State.ALLOWED : State.UNKNOWN);
    @Nullable private ListenerRegistration listener;
    @Nullable private String listeningFor;

    public AiAccess(AiApiClient ai, Prefs prefs) {
        this.prefs = prefs;
        // AI calls report enrolment too (e.g. the admin switched it off a moment ago)
        ai.setAccessListener(allowed -> state.postValue(allowed ? State.ALLOWED : State.NOT_ENROLLED));
    }

    /** Mirrors isAdmin() in firestore.rules: verified admin emails (the rules are what enforce it). */
    private static final String[] ADMINS = {"minhaz.hredoy@gmail.com", "cse.minhaz87@gmail.com"};

    public static boolean isAdmin() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null || !u.isEmailVerified() || u.getEmail() == null) return false;
        String e = u.getEmail().toLowerCase(Locale.ROOT);
        for (String a : ADMINS) if (a.equals(e)) return true;
        return false;
    }

    public LiveData<State> state() {
        return state;
    }

    private static DocumentReference card(String uid) {
        return FirebaseFirestore.getInstance().collection("aiAccess").document(uid);
    }

    /** Creates the user's card if needed, applies a pre-allowed email, and follows the card live. */
    public synchronized void refresh() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;
        if (BuildConfig.AI_OPEN) {
            state.postValue(State.ALLOWED);
            return;
        }
        String uid = u.getUid();
        if (!uid.equals(listeningFor)) {
            if (listener != null) listener.remove();
            listeningFor = uid;
            listener = card(uid).addSnapshotListener((snap, err) -> {
                if (err != null || snap == null || !snap.exists()) return;
                state.postValue(Boolean.TRUE.equals(snap.getBoolean("allowed")) ? State.ALLOWED : State.NOT_ENROLLED);
                Long req = snap.getLong("requestedAt");
                if (req != null && req > 0) prefs.setAiAccessRequested(uid);
            });
        }
        card(uid).get().addOnSuccessListener(snap -> ensureCard(u, snap));
    }

    private void ensureCard(FirebaseUser u, DocumentSnapshot snap) {
        boolean allowed = snap.exists() && Boolean.TRUE.equals(snap.getBoolean("allowed"));
        if (snap.exists() && (allowed || !u.isEmailVerified() || u.getEmail() == null)) return;
        if (!u.isEmailVerified() || u.getEmail() == null) {
            write(u, false, !snap.exists());
            return;
        }
        if (isAdmin()) {
            write(u, true, !snap.exists()); // admins are always allowed (firestore.rules preAllowed)
            return;
        }
        // a verified email on the pre-allowed list may switch its own card on (enforced by the rules)
        FirebaseFirestore.getInstance().collection("aiAllowList").document(u.getEmail().toLowerCase(Locale.ROOT)).get()
                .addOnCompleteListener(t -> {
                    boolean listed = t.isSuccessful() && t.getResult() != null && t.getResult().exists();
                    if (listed || !snap.exists()) write(u, listed, !snap.exists());
                });
    }

    private void write(FirebaseUser u, boolean allowed, boolean create) {
        long now = System.currentTimeMillis();
        Map<String, Object> m = new HashMap<>();
        m.put("email", u.getEmail());
        m.put("name", u.getDisplayName());
        m.put("allowed", allowed);
        m.put("updatedAt", now);
        if (create) {
            m.put("uid", u.getUid());
            m.put("requestedAt", 0L);
            m.put("createdAt", now);
            card(u.getUid()).set(m);
        } else {
            card(u.getUid()).set(m, SetOptions.merge());
        }
    }

    public boolean requested() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        return u != null && prefs.aiAccessRequested(u.getUid());
    }

    /**
     * Asks for access the first time only (requestedAt is set once; Firestore delivers it when online). Later
     * calls send nothing. Returns false when there is no signed-in user.
     */
    public boolean request() {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return false;
        if (requested()) return true;
        Map<String, Object> m = new HashMap<>();
        m.put("requestedAt", System.currentTimeMillis());
        m.put("updatedAt", System.currentTimeMillis());
        card(u.getUid()).set(m, SetOptions.merge());
        prefs.setAiAccessRequested(u.getUid());
        return true;
    }

    /** Signed out: stop following the previous account. */
    public synchronized void reset() {
        if (listener != null) listener.remove();
        listener = null;
        listeningFor = null;
        state.postValue(BuildConfig.AI_OPEN ? State.ALLOWED : State.UNKNOWN);
    }
}
