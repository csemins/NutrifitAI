package com.transformsai.nutrifitai.ui.social;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ActivityChallengeLobbyBinding;
import com.transformsai.nutrifitai.databinding.ItemSocialRowBinding;
import com.transformsai.nutrifitai.fitness.ExerciseLibrary;
import com.transformsai.nutrifitai.fitness.WorkoutPlan;
import com.transformsai.nutrifitai.social.SocialModels.Challenge;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.train.WorkoutActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Before a challenge: who accepted, the routine and the rules. The host starts it; every phone then counts
 * down to the same start time (Firestore startAt) and opens the camera workout in challenge mode.
 */
public class ChallengeLobbyActivity extends BaseActivity {
    private static final String EXTRA_ID = "challenge_id";

    public static Intent intent(Context c, String challengeId) {
        return new Intent(c, ChallengeLobbyActivity.class).putExtra(EXTRA_ID, challengeId);
    }

    private ActivityChallengeLobbyBinding b;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String uid, id;
    @Nullable private Challenge current;
    private boolean launched;
    private final Runnable tick = this::tickCountdown;

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        uid = uidOrFinish();
        id = getIntent().getStringExtra(EXTRA_ID);
        if (uid == null || id == null) {
            finish();
            return;
        }
        b = ActivityChallengeLobbyBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, false);
        InsetsUtil.padding(b.bottom, false, true);
        b.bar.title.setText(R.string.challenge_lobby);
        b.bar.back.setOnClickListener(v -> finish());
        graph().challenges.challenge(id).observe(this, this::render);
    }

    private void render(@Nullable Challenge c) {
        if (c == null) return;
        current = c;
        b.challengeTitle.setText(c.title);
        graph().runIo(() -> {
            String routine = routineText(c);
            main.post(() -> {
                if (b != null) b.routine.setText(routine);
            });
        });
        b.players.removeAllViews();
        for (String p : c.participants) {
            ItemSocialRowBinding row = ItemSocialRowBinding.inflate(getLayoutInflater(), b.players, false);
            String name = p.equals(uid) ? getString(R.string.you) : c.nameOf(p);
            com.transformsai.nutrifitai.ui.common.Avatars.person(row.avatar, p, name);
            row.title.setText(name);
            row.subtitle.setText(p.equals(c.host) ? getString(R.string.challenge_host)
                    : c.declined.contains(p) ? getString(R.string.challenge_declined)
                    : c.accepted.contains(p) ? getString(R.string.challenge_in) : getString(R.string.challenge_invited));
            b.players.addView(row.getRoot());
        }
        boolean host = uid.equals(c.host);
        boolean answered = c.accepted.contains(uid) || c.declined.contains(uid);
        int ready = c.accepted.size();
        b.secondary.setVisibility(View.GONE);
        if (Challenge.FINISHED.equals(c.status)) {
            startActivity(ChallengeResultActivity.intent(this, c.id));
            finish();
            return;
        }
        if (Challenge.LIVE.equals(c.status)) {
            b.status.setText(R.string.challenge_status_live);
            b.primary.setText(R.string.challenge_join);
            b.primary.setEnabled(true);
            b.primary.setOnClickListener(v -> launch());
            if (c.accepted.contains(uid)) tickCountdown();
            return;
        }
        if (!answered) {
            b.status.setText(getString(R.string.challenge_invited_by, c.hostName));
            b.primary.setText(R.string.challenge_accept);
            b.primary.setEnabled(true);
            b.primary.setOnClickListener(v -> graph().challenges.answer(c, true));
            b.secondary.setVisibility(View.VISIBLE);
            b.secondary.setText(R.string.challenge_decline);
            b.secondary.setOnClickListener(v -> {
                graph().challenges.answer(c, false);
                finish();
            });
        } else if (c.declined.contains(uid)) {
            b.status.setText(R.string.challenge_you_declined);
            b.primary.setText(R.string.challenge_accept);
            b.primary.setEnabled(false);
        } else if (host) {
            b.status.setText(ready >= 2 ? getString(R.string.challenge_ready_n, ready) : getString(R.string.challenge_waiting_accept));
            b.primary.setText(R.string.challenge_start);
            b.primary.setEnabled(ready >= 2);
            Ui.onClick(b.primary, v -> graph().challenges.start(c));
        } else {
            b.status.setText(getString(R.string.challenge_waiting_host, c.hostName));
            b.primary.setText(R.string.challenge_start);
            b.primary.setEnabled(false);
        }
    }

    private String routineText(Challenge c) {
        WorkoutPlan p = new WorkoutPlan();
        ExerciseLibrary lib = ExerciseLibrary.get(this);
        WorkoutPlan.parseSteps(c.stepsJson, p, lib);
        List<String> parts = new ArrayList<>();
        for (WorkoutPlan.Step st : p.steps) {
            com.transformsai.nutrifitai.fitness.Exercise x = lib.byId(st.exerciseId);
            if (x == null) continue;
            parts.add(getString(x.isHold() ? R.string.challenge_step_hold : R.string.challenge_step_reps, x.name, st.target));
        }
        return String.join(" · ", parts);
    }

    /** Shared countdown to startAt, then the workout opens in challenge mode. */
    private void tickCountdown() {
        main.removeCallbacks(tick);
        if (current == null || launched || b == null) return;
        long left = current.startAt - System.currentTimeMillis();
        if (left <= 0) {
            launch();
            return;
        }
        b.countdown.setVisibility(View.VISIBLE);
        b.countdown.setText(String.valueOf((left + 999) / 1000));
        main.postDelayed(tick, Math.min(250, left));
    }

    private void launch() {
        if (launched || current == null) return;
        launched = true;
        startActivity(WorkoutActivity.forChallenge(this, current.id));
        finish();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
