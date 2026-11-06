package com.transformsai.nutrifitai.social;

import com.transformsai.nutrifitai.data.AppGraph;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * Shared plumbing of the social repositories. Social data is online-first and lives only in Firestore (with
 * its offline cache): it is shared between accounts, so it does not go through the private Room outbox.
 * Who can read and write what is enforced by firestore.rules.
 */
abstract class SocialBase {
    private static final SecureRandom RNG = new SecureRandom();
    static final long TIMEOUT_S = 20;

    final AppGraph g;
    final FirebaseFirestore fs = FirebaseFirestore.getInstance();

    SocialBase(AppGraph g) {
        this.g = g;
    }

    String me() {
        return g.session.requireUid();
    }

    CollectionReference friendsOf(String uid) {
        return fs.collection("users").document(uid).collection("friends");
    }

    /** Blocking wait for a Firestore task (call on the io executor). */
    static <T> T await(Task<T> t) throws Exception {
        return Tasks.await(t, TIMEOUT_S, TimeUnit.SECONDS);
    }

    /** 22 url-safe random characters: media, challenge and message ids that cannot be guessed. */
    public static String randomId() {
        String a = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-";
        StringBuilder sb = new StringBuilder(22);
        for (int i = 0; i < 22; i++) sb.append(a.charAt(RNG.nextInt(a.length())));
        return sb.toString();
    }
}
