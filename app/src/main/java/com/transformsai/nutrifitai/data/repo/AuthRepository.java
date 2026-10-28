package com.transformsai.nutrifitai.data.repo;

import android.content.Context;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.data.db.AppDatabase;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.reminders.ReminderScheduler;
import com.transformsai.nutrifitai.sync.Collections;
import com.transformsai.nutrifitai.sync.SyncEngine;
import com.transformsai.nutrifitai.sync.SyncScheduler;
import com.transformsai.nutrifitai.util.DateUtil;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.auth.UserProfileChangeRequest;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import com.transformsai.nutrifitai.social.InboxWorker;
import com.transformsai.nutrifitai.social.SocialCleanup;
import com.google.firebase.firestore.FirebaseFirestore;

/** Firebase Authentication plus account-scoped local cleanup. */
public class AuthRepository {
    private final AppGraph g;
    private final FirebaseAuth auth = FirebaseAuth.getInstance();

    public AuthRepository(AppGraph g) {
        this.g = g;
    }

    public void signIn(String email, String password, Result.Callback<Boolean> cb) {
        g.runIo(() -> {
            try {
                Tasks.await(auth.signInWithEmailAndPassword(email, password), 30, TimeUnit.SECONDS);
                g.postResult(cb, Result.ok(bootstrap(null)));
            } catch (Exception e) {
                g.postResult(cb, Result.fail(message(e)));
            }
        });
    }

    public void signUp(String name, String email, String password, Result.Callback<Boolean> cb) {
        g.runIo(() -> {
            try {
                Tasks.await(auth.createUserWithEmailAndPassword(email, password), 30, TimeUnit.SECONDS);
                FirebaseUser u = auth.getCurrentUser();
                if (u != null) {
                    try {
                        Tasks.await(u.updateProfile(new UserProfileChangeRequest.Builder().setDisplayName(name).build()), 15, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        // display name is optional; the profile row below holds it
                    }
                }
                g.postResult(cb, Result.ok(bootstrap(name)));
            } catch (Exception e) {
                g.postResult(cb, Result.fail(message(e)));
            }
        });
    }

    public void signInWithGoogle(String idToken, Result.Callback<Boolean> cb) {
        g.runIo(() -> {
            try {
                AuthCredential cred = GoogleAuthProvider.getCredential(idToken, null);
                Tasks.await(auth.signInWithCredential(cred), 30, TimeUnit.SECONDS);
                FirebaseUser u = auth.getCurrentUser();
                g.postResult(cb, Result.ok(bootstrap(u == null ? null : u.getDisplayName())));
            } catch (Exception e) {
                g.postResult(cb, Result.fail(message(e)));
            }
        });
    }

    public void sendPasswordReset(String email, Result.Callback<Void> cb) {
        g.runIo(() -> {
            try {
                Tasks.await(auth.sendPasswordResetEmail(email), 30, TimeUnit.SECONDS);
                g.postResult(cb, Result.ok(null));
            } catch (Exception e) {
                g.postResult(cb, Result.fail(message(e)));
            }
        });
    }

    /**
     * After any sign-in: pull cloud data (best effort, bounded), make sure a local profile row
     * exists, and report whether onboarding is complete. Runs on the IO thread.
     */
    private boolean bootstrap(@Nullable String displayName) {
        String uid = g.session.requireUid();
        try {
            new SyncEngine(g.context, g.db, uid).pull();
        } catch (Exception ignored) {
            // Offline or Firestore not reachable: continue with local data; WorkManager retries later.
        }
        ProfileEntity p = g.db.profiles().get(uid);
        if (p == null) {
            p = new ProfileEntity();
            p.uid = uid;
            p.name = displayName;
            p.email = g.session.email();
            p.createdAt = DateUtil.nowMillis();
            p.updatedAt = p.createdAt;
            ProfileEntity fp = p;
            g.db.runInTransaction(() -> {
                g.db.profiles().upsert(fp);
                g.outbox.enqueue(uid, Collections.PROFILE, uid);
            });
        }
        SyncScheduler.requestSync(g.context);
        SyncScheduler.schedulePeriodic(g.context);
        ReminderScheduler.rescheduleAsync(g.context);
        return p.onboarded;
    }

    public boolean isPasswordUser() {
        FirebaseUser u = auth.getCurrentUser();
        if (u == null) return false;
        for (UserInfo i : u.getProviderData()) if (EmailAuthProvider.PROVIDER_ID.equals(i.getProviderId())) return true;
        return false;
    }

    /** Signs out and removes this account's local cache, images and reminders. */
    public void signOut(Result.Callback<Void> cb) {
        String uid = g.session.uid();
        g.presence.stop();
        SyncScheduler.cancelAll(g.context);
        g.runIo(() -> {
            if (uid != null) wipeLocal(g.context, g.db, uid);
            auth.signOut();
            g.prefs.clearAccount(uid == null ? "" : uid);
            g.aiAccess.reset();
            g.inbox.stop();
            InboxWorker.cancel(g.context);
            g.postResult(cb, Result.ok(null));
        });
    }

    /**
     * Account deletion: re-authenticate, delete Firestore docs, Storage images and RTDB presence,
     * delete the Auth user, then wipe local data. Pass either password or googleIdToken.
     */
    public void deleteAccount(@Nullable String password, @Nullable String googleIdToken, Result.Callback<Void> cb) {
        g.runIo(() -> {
            FirebaseUser u = auth.getCurrentUser();
            if (u == null) {
                g.postResult(cb, Result.fail("Not signed in."));
                return;
            }
            String uid = u.getUid();
            try {
                AuthCredential cred = password != null
                        ? EmailAuthProvider.getCredential(u.getEmail() == null ? "" : u.getEmail(), password)
                        : GoogleAuthProvider.getCredential(googleIdToken, null);
                Tasks.await(u.reauthenticate(cred), 30, TimeUnit.SECONDS);
                g.presence.stop();
                SyncScheduler.cancelAll(g.context);
                new SyncEngine(g.context, g.db, uid).deleteAllRemote();
                SocialCleanup.deleteAllBlocking(g, uid);
                Tasks.await(FirebaseFirestore.getInstance().collection("aiAccess").document(uid).delete(), 30, TimeUnit.SECONDS);
                g.presence.deleteStatus(uid);
                Tasks.await(u.delete(), 30, TimeUnit.SECONDS);
                wipeLocal(g.context, g.db, uid);
                g.prefs.clearAccount(uid);
                g.postResult(cb, Result.ok(null));
            } catch (Exception e) {
                g.postResult(cb, Result.fail(message(e)));
            }
        });
    }

    /** Removes every row owned by uid plus its private image files. */
    public static void wipeLocal(Context c, AppDatabase db, String uid) {
        ReminderScheduler.cancelAll(c);
        List<String> images = db.diary().localImages(uid);
        images.addAll(db.ai().localImages(uid));
        ProfileEntity p = db.profiles().get(uid);
        if (p != null && p.avatarLocalPath != null) images.add(p.avatarLocalPath);
        db.runInTransaction(() -> {
            db.diary().wipe(uid);
            db.water().wipe(uid);
            db.weights().wipe(uid);
            db.goals().wipe(uid);
            db.foods().wipeFavorites(uid);
            db.foods().wipeCustom(uid);
            db.recipes().wipe(uid);
            db.reminders().wipe(uid);
            db.ai().wipe(uid);
            db.chat().wipeMessages(uid);
            db.chat().wipeConversations(uid);
            db.sync().wipeOps(uid);
            db.sync().wipeMeta(uid);
            db.imports().wipe(uid);
            db.workouts().wipeSessions(uid);
            db.workouts().wipePlans(uid);
            db.activity().wipeSteps(uid);
            db.activity().wipeFasts(uid);
            db.profiles().wipe(uid);
        });
        for (String path : images) {
            if (path == null) continue;
            File f = new File(path);
            if (f.exists() && !f.delete()) f.deleteOnExit();
        }
    }

    public int pendingOps() {
        String uid = g.session.uid();
        return uid == null ? 0 : g.db.sync().pendingCount(uid);
    }

    public String message(Exception e) {
        Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        Context c = g.context;
        if (t instanceof FirebaseAuthWeakPasswordException) return c.getString(R.string.err_weak_password);
        if (t instanceof FirebaseAuthUserCollisionException) return c.getString(R.string.err_email_in_use);
        if (t instanceof FirebaseAuthInvalidUserException) return c.getString(R.string.err_no_account);
        if (t instanceof FirebaseAuthInvalidCredentialsException) return c.getString(R.string.err_bad_credentials);
        if (t instanceof FirebaseAuthRecentLoginRequiredException) return c.getString(R.string.err_recent_login);
        if (t instanceof FirebaseTooManyRequestsException) return c.getString(R.string.err_too_many);
        if (t instanceof FirebaseNetworkException) return c.getString(R.string.err_network);
        if (t instanceof java.util.concurrent.TimeoutException) return c.getString(R.string.err_timeout);
        if (t instanceof com.google.firebase.FirebaseException && t.getMessage() != null
                && t.getMessage().contains("CONFIGURATION_NOT_FOUND")) return c.getString(R.string.err_auth_not_configured);
        return c.getString(R.string.err_firebase_unavailable);
    }
}
