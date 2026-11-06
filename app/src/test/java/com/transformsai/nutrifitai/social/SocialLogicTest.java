package com.transformsai.nutrifitai.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.transformsai.nutrifitai.fitness.pose.Pose;
import com.transformsai.nutrifitai.social.SocialModels.LivePlayer;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class SocialLogicTest {
    private static LivePlayer player(String uid, int score, boolean done, long finishMs) {
        LivePlayer p = new LivePlayer();
        p.uid = uid;
        p.score = score;
        p.done = done;
        p.finishMs = finishMs;
        return p;
    }

    @Test
    public void scoreRewardsCleanRepsAndPenalisesFaults() {
        assertEquals(200, ChallengeScore.score(20, 0, 0, false, 0, 0));
        // three bad reps cost more than they would have earned
        assertEquals(20 * 10 - 3 * 15, ChallengeScore.score(20, 0, 3, false, 0, 0));
        // holds: 2 points per full second
        assertEquals(60, ChallengeScore.score(0, 30.9, 0, false, 0, 0));
        // finishing: +50 and one point per second under par; never a negative bonus
        assertEquals(200 + 50 + 20, ChallengeScore.score(20, 0, 0, true, 40_000, 60_000));
        assertEquals(200 + 50, ChallengeScore.score(20, 0, 0, true, 90_000, 60_000));
    }

    @Test
    public void rankingByPointsThenFinishThenSpeed() {
        List<LivePlayer> r = ChallengeScore.ranked(Arrays.asList(
                player("slowFinisher", 250, true, 70_000),
                player("quitter", 250, false, 0),
                player("fastFinisher", 250, true, 50_000),
                player("winner", 300, true, 90_000)));
        assertEquals("winner", r.get(0).uid);
        assertEquals("fastFinisher", r.get(1).uid);
        assertEquals("slowFinisher", r.get(2).uid);
        assertEquals("quitter", r.get(3).uid);
    }

    @Test
    public void poseRoundTripsAndMirrors() {
        Pose p = new Pose(640, 480);
        for (int i = 0; i < Pose.COUNT; i++) {
            p.x[i] = 320;
            p.y[i] = 240;
            p.v[i] = 0.9f;
        }
        p.x[Pose.L_WRIST] = 64; // 0.1 of the width
        p.v[Pose.R_ANKLE] = 0.1f; // not visible
        String s = PoseCodec.encode(p, false);
        assertTrue(s.length() < 400); // fits the Realtime Database rule
        float[] d = PoseCodec.decode(s);
        int wrist = indexOf(Pose.L_WRIST), ankle = indexOf(Pose.R_ANKLE);
        assertEquals(0.1f, d[wrist * 2], 0.002f);
        assertEquals(0.5f, d[wrist * 2 + 1], 0.002f);
        assertTrue(Float.isNaN(d[ankle * 2]));
        assertEquals(0.9f, PoseCodec.decode(PoseCodec.encode(p, true))[wrist * 2], 0.002f);
        assertNull(PoseCodec.decode("1,2;3,4"));
        assertNull(PoseCodec.decode(null));
    }

    private static int indexOf(int joint) {
        for (int i = 0; i < PoseCodec.JOINTS.length; i++) if (PoseCodec.JOINTS[i] == joint) return i;
        throw new AssertionError();
    }

    @Test
    public void reactionEmojiCoverEveryKey() {
        for (String k : FeedRepository.REACTIONS) assertNotEquals("", FeedRepository.emoji(k));
        assertEquals("👍", FeedRepository.emoji(null));
        assertEquals("🔥", FeedRepository.emoji("fire"));
    }

    @Test
    public void directMessageIdsAreSortedAndStable() {
        assertEquals(ChatRepository.dmId("b", "a"), ChatRepository.dmId("a", "b"));
        assertTrue(ChatRepository.dmId("zz", "aa").startsWith("dm_aa_zz"));
    }

    @Test
    public void mediaIdsAreLongAndUnguessable() {
        String a = SocialBase.randomId(), b = SocialBase.randomId();
        assertEquals(22, a.length());
        assertTrue(a.matches("^[A-Za-z0-9_-]{16,64}$")); // server bucket path rule
        assertNotEquals(a, b);
    }
}
