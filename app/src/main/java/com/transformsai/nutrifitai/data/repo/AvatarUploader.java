package com.transformsai.nutrifitai.data.repo;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.sync.Collections;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.data.remote.BucketClient;

import java.io.File;

/** Uploads the avatar to the private bucket at users/{uid}/avatar.jpg (owner-only). */
final class AvatarUploader {
    private AvatarUploader() {
    }

    static void upload(AppGraph g, String uid, String localPath) {
        try {
            File f = new File(localPath);
            if (!f.exists()) return;
            String path = BucketClient.avatarPath(uid);
            new BucketClient(g.prefs).upload(path, f);
            ProfileEntity p = g.db.profiles().get(uid);
            if (p == null) return;
            p.avatarRemotePath = path;
            p.updatedAt = DateUtil.nowMillis();
            g.db.runInTransaction(() -> {
                g.db.profiles().upsert(p);
                g.outbox.enqueue(uid, Collections.PROFILE, uid);
            });
            g.outbox.kick();
        } catch (Exception ignored) {
            // Offline: the local avatar still shows; upload is retried next time the user changes it.
        }
    }
}
