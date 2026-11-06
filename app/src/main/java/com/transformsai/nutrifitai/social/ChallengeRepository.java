package com.transformsai.nutrifitai.social;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.data.remote.FirebaseConfig;
import com.transformsai.nutrifitai.social.SocialModels.Challenge;
import com.transformsai.nutrifitai.social.SocialModels.InboxItem;
import com.transformsai.nutrifitai.social.SocialModels.Person;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.Query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Workout challenges between friends or a group (2-8 people). Live progress is in RTDB live/{id}. */
public final class ChallengeRepository extends SocialBase {
    public ChallengeRepository(AppGraph g) {
        super(g);
    }

    public LiveData<List<Challenge>> challenges() {
        return new FirestoreLive<>(fs.collection("challenges").whereArrayContains("participants", me())
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(30), Challenge.class);
    }

    public LiveData<Challenge> challenge(String id) {
        return new FirestoreLive.Doc<>(fs.collection("challenges").document(id), Challenge.class);
    }

    /** Reads a challenge once (from the cache when offline) to build its workout. */
    public void load(String id, Result.Callback<Challenge> cb) {
        g.runIo(() -> {
            try {
                Challenge c = await(fs.collection("challenges").document(id).get()).toObject(Challenge.class);
                g.postResult(cb, c == null ? Result.fail("This challenge no longer exists.") : Result.ok(c));
            } catch (Exception e) {
                g.postResult(cb, Result.fail("Couldn't load the challenge. Check your connection."));
            }
        });
    }

    /** Creates the challenge and notifies everyone invited. {@code groupId} is set for a group challenge. */
    public void create(String title, String stepsJson, List<Person> people, @Nullable String groupId, Result.Callback<Challenge> cb) {
        g.runIo(() -> {
            try {
                Challenge c = new Challenge();
                c.id = "c_" + randomId();
                c.host = me();
                c.hostName = g.cards.myName();
                c.title = title;
                c.stepsJson = stepsJson;
                c.status = Challenge.OPEN;
                c.groupId = groupId;
                c.participants.add(me());
                c.names.put(me(), c.hostName);
                for (Person p : people) {
                    if (p.uid.equals(me())) continue;
                    c.participants.add(p.uid);
                    c.names.put(p.uid, p.name == null ? "Friend" : p.name);
                }
                c.accepted.add(me());
                c.createdAt = System.currentTimeMillis();
                await(fs.collection("challenges").document(c.id).set(c));
                for (String uid : c.participants) {
                    g.inbox.send(uid, InboxItem.CHALLENGE, c.hostName + " challenged you", c.title + " · tap to accept", c.id);
                }
                g.postResult(cb, Result.ok(c));
            } catch (Exception e) {
                g.postResult(cb, Result.fail("Couldn't create the challenge. Check your connection."));
            }
        });
    }

    public void answer(Challenge c, boolean accept) {
        fs.collection("challenges").document(c.id).update(accept ? "accepted" : "declined", FieldValue.arrayUnion(me()));
    }

    /** Host: everyone starts together a few seconds from now, and gets a "starting" notification. */
    public void start(Challenge c) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", Challenge.LIVE);
        m.put("startAt", System.currentTimeMillis() + 8000);
        fs.collection("challenges").document(c.id).update(m);
        g.runIo(() -> {
            for (String uid : c.accepted) {
                g.inbox.send(uid, InboxItem.CHALLENGE, c.title + " is starting", "Join now, " + c.hostName + " is ready", c.id);
            }
        });
    }

    public void reportResult(String challengeId, int score, int reps, int faults, long finishMs, boolean done) {
        Map<String, Object> r = new HashMap<>();
        r.put("score", score);
        r.put("reps", reps);
        r.put("faults", faults);
        r.put("finishMs", finishMs);
        r.put("done", done);
        fs.collection("challenges").document(challengeId).update("results." + me(), r);
    }

    /** Host: close the challenge once everyone has reported. */
    public void finish(String challengeId) {
        fs.collection("challenges").document(challengeId).update("status", Challenge.FINISHED);
    }

    /** Account deletion: challenges I hosted are deleted; my live node goes from the others. Blocking. */
    void deleteBlocking(String uid) throws Exception {
        for (DocumentSnapshot d : await(fs.collection("challenges").whereArrayContains("participants", uid).get()).getDocuments()) {
            FirebaseConfig.database().getReference("live").child(d.getId()).child(uid).removeValue();
            if (uid.equals(d.getString("host"))) await(d.getReference().delete());
        }
    }
}
