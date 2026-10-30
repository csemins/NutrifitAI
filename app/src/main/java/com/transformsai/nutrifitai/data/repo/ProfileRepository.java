package com.transformsai.nutrifitai.data.repo;

import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.sync.Collections;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Ids;

/** Profile and goal snapshots. Changing a goal writes a NEW snapshot effective today. */
public class ProfileRepository {
    private final AppGraph g;

    public ProfileRepository(AppGraph g) {
        this.g = g;
    }

    public LiveData<ProfileEntity> observe(String uid) {
        return g.db.profiles().observe(uid);
    }

    public LiveData<GoalEntity> observeGoal(String uid, String date) {
        return g.db.goals().observeActive(uid, date);
    }

    public void get(Result.Callback<ProfileEntity> cb) {
        g.runIo(() -> g.postResult(cb, Result.ok(g.db.profiles().get(g.session.requireUid()))));
    }

    public void activeGoal(String date, Result.Callback<GoalEntity> cb) {
        g.runIo(() -> g.postResult(cb, Result.ok(g.db.goals().active(g.session.requireUid(), date))));
    }

    public void save(ProfileEntity p, Result.Callback<Void> cb) {
        g.runIo(() -> {
            saveBlocking(p);
            g.postResult(cb, Result.ok(null));
        });
    }

    /** Stores the profile locally and queues it for sync. Call off the main thread. */
    public void saveBlocking(ProfileEntity p) {
        String uid = g.session.requireUid();
        p.uid = uid;
        p.updatedAt = DateUtil.nowMillis();
        if (p.createdAt == 0) p.createdAt = p.updatedAt;
        g.db.runInTransaction(() -> {
            g.db.profiles().upsert(p);
            g.outbox.enqueue(uid, Collections.PROFILE, uid);
        });
        g.outbox.kick();
    }

    /** Saves profile + a new goal snapshot atomically (onboarding / goal edit). */
    public void saveProfileAndGoal(ProfileEntity p, GoalEntity goal, Result.Callback<Void> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            long now = DateUtil.nowMillis();
            p.uid = uid;
            p.updatedAt = now;
            if (p.createdAt == 0) p.createdAt = now;
            goal.id = Ids.newId();
            goal.uid = uid;
            goal.effectiveDate = DateUtil.today();
            goal.createdAt = now;
            goal.updatedAt = now;
            g.db.runInTransaction(() -> {
                g.db.profiles().upsert(p);
                g.db.goals().upsert(goal);
                g.outbox.enqueue(uid, Collections.PROFILE, uid);
                g.outbox.enqueue(uid, Collections.GOALS, goal.id);
            });
            g.outbox.kick();
            g.postResult(cb, Result.ok(null));
        });
    }

    public void setAvatar(String localPath, Result.Callback<Void> cb) {
        g.runIo(() -> {
            String uid = g.session.requireUid();
            ProfileEntity p = g.db.profiles().get(uid);
            if (p == null) {
                g.postResult(cb, Result.fail("Profile missing"));
                return;
            }
            p.avatarLocalPath = localPath;
            p.avatarRemotePath = null;
            p.updatedAt = DateUtil.nowMillis();
            g.db.runInTransaction(() -> {
                g.db.profiles().upsert(p);
                g.outbox.enqueue(uid, Collections.PROFILE, uid);
            });
            // Private copy for the account, and a shareable copy that friends see on posts and chats.
            g.runIo(() -> {
                AvatarUploader.upload(g, uid, localPath);
                try {
                    g.cards.publishPhotoBlocking(new java.io.File(localPath));
                } catch (Exception ignored) {
                    // offline: published the next time the photo changes
                }
            });
            g.postResult(cb, Result.ok(null));
        });
    }
}
