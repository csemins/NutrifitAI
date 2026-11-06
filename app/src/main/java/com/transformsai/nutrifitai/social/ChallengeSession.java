package com.transformsai.nutrifitai.social;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.transformsai.nutrifitai.data.remote.FirebaseConfig;
import com.transformsai.nutrifitai.fitness.pose.Pose;
import com.transformsai.nutrifitai.social.SocialModels.LivePlayer;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One player's side of a live challenge, over the Realtime Database (live/{challengeId}/{uid}). It publishes my
 * counted reps, form faults, held seconds, current step, score and a compact pose (for the friends' "call"
 * tiles), and follows everyone else. Writes are throttled; the node is removed if the app disconnects
 * before finishing, so a player who drops out does not look frozen.
 */
public final class ChallengeSession {
    private static final long STATS_EVERY_MS = 300, POSE_EVERY_MS = 160;

    private final DatabaseReference room, mine;
    private final String uid, name;
    private final long parMs;
    private final MutableLiveData<List<LivePlayer>> others = new MutableLiveData<>(new ArrayList<>());
    private ValueEventListener listener;
    private long lastStats, lastPose, startedAt;
    private int reps, faults, step, steps, score;
    private double holdSec;
    private String exercise = "";
    private boolean done;

    public ChallengeSession(String challengeId, String uid, String name, long parMs) {
        this.room = FirebaseConfig.database().getReference("live").child(challengeId);
        this.mine = room.child(uid);
        this.uid = uid;
        this.name = name;
        this.parMs = parMs;
    }

    /** Joins the room (ready) and starts following the other players. */
    public void join() {
        startedAt = SystemClock.elapsedRealtime();
        Map<String, Object> m = base();
        m.put("ready", true);
        mine.onDisconnect().removeValue();
        mine.setValue(m);
        listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot s) {
                List<LivePlayer> list = new ArrayList<>();
                for (DataSnapshot c : s.getChildren()) {
                    if (uid.equals(c.getKey())) continue;
                    LivePlayer p = c.getValue(LivePlayer.class);
                    if (p == null) continue;
                    p.uid = c.getKey();
                    list.add(p);
                }
                others.setValue(ChallengeScore.ranked(list));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                // permission or network: tiles keep their last state
            }
        };
        room.addValueEventListener(listener);
    }

    public LiveData<List<LivePlayer>> others() {
        return others;
    }

    public void rep() {
        reps++;
        publish(true);
    }

    public void fault() {
        faults++;
        publish(true);
    }

    /** Called on every engine change: current step, exercise and held time. */
    public void progress(int stepIndex, int stepCount, String exerciseName, double heldSecondsTotal) {
        step = stepIndex;
        steps = stepCount;
        exercise = exerciseName == null ? "" : exerciseName;
        holdSec = heldSecondsTotal;
        publish(false);
    }

    public void pose(Pose p, boolean mirror) {
        long now = SystemClock.elapsedRealtime();
        if (done || now - lastPose < POSE_EVERY_MS) return;
        lastPose = now;
        Map<String, Object> m = new HashMap<>();
        m.put("pose", PoseCodec.encode(p, mirror));
        m.put("at", System.currentTimeMillis());
        mine.updateChildren(m);
    }

    /** @return my final score. */
    public int finish(boolean completed) {
        done = completed;
        long elapsed = SystemClock.elapsedRealtime() - startedAt;
        score = ChallengeScore.score(reps, holdSec, faults, completed, elapsed, parMs);
        Map<String, Object> m = base();
        m.put("done", completed);
        m.put("finishMs", elapsed);
        mine.onDisconnect().cancel(); // keep the final result in the room
        mine.updateChildren(m);
        return score;
    }

    /** Sends a reaction (shown on everyone's screen for a moment). */
    public void react(String key) {
        Map<String, Object> m = new HashMap<>();
        m.put("reaction", key);
        m.put("reactionAt", System.currentTimeMillis());
        m.put("at", System.currentTimeMillis());
        mine.updateChildren(m);
    }

    /** Me as a scoreboard entry. */
    public LivePlayer me() {
        LivePlayer p = new LivePlayer();
        p.uid = uid;
        p.name = name;
        p.reps = reps;
        p.faults = faults;
        p.score = score;
        p.done = done;
        p.at = System.currentTimeMillis();
        return p;
    }

    /** Points so far (updated with every published change). */
    public int score() {
        return score;
    }

    public int reps() {
        return reps;
    }

    public int faults() {
        return faults;
    }

    public long elapsedMs() {
        return SystemClock.elapsedRealtime() - startedAt;
    }

    public void leave() {
        if (listener != null) room.removeEventListener(listener);
        listener = null;
    }

    private void publish(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastStats < STATS_EVERY_MS) return;
        lastStats = now;
        score = ChallengeScore.score(reps, holdSec, faults, false, now - startedAt, parMs);
        mine.updateChildren(base());
    }

    private Map<String, Object> base() {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("at", System.currentTimeMillis());
        m.put("reps", reps);
        m.put("faults", faults);
        m.put("holdSec", Math.round(holdSec * 10) / 10.0);
        m.put("step", step);
        m.put("steps", steps);
        m.put("score", score);
        m.put("exercise", exercise);
        return m;
    }
}
