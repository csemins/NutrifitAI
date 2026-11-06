package com.transformsai.nutrifitai.social;

import com.transformsai.nutrifitai.social.SocialModels.LivePlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Challenge points, computed the same way on every phone from what the camera tracker counted:
 *   +10 per counted rep, +2 per second held in good form,
 *   -15 per form fault (a rep the coach rejected, or a hold that broke form),
 *   finishing the whole routine: +50 and a speed bonus of 1 point per second under par time.
 * Ranking: more points first; on a tie, whoever finished, then the faster finish.
 */
public final class ChallengeScore {
    public static final int REP = 10, HOLD_SECOND = 2, FAULT = -15, FINISH = 50;

    private ChallengeScore() { }

    public static int score(int reps, double holdSeconds, int faults, boolean finished, long elapsedMs, long parMs) {
        int s = reps * REP + (int) Math.floor(holdSeconds) * HOLD_SECOND + faults * FAULT;
        if (finished) s += FINISH + (int) Math.max(0, (parMs - elapsedMs) / 1000);
        return s;
    }

    public static final Comparator<LivePlayer> RANKING = (a, b) -> {
        if (a.score != b.score) return Integer.compare(b.score, a.score);
        if (a.done != b.done) return a.done ? -1 : 1;
        if (a.done) return Long.compare(a.finishMs, b.finishMs);
        return 0;
    };

    public static List<LivePlayer> ranked(List<LivePlayer> players) {
        List<LivePlayer> out = new ArrayList<>(players);
        out.sort(RANKING);
        return out;
    }
}
