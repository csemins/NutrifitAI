package com.transformsai.nutrifitai.ui.social;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.remote.FirebaseConfig;
import com.transformsai.nutrifitai.databinding.ActivityChallengeResultBinding;
import com.transformsai.nutrifitai.databinding.ItemSocialRowBinding;
import com.transformsai.nutrifitai.social.ChallengeScore;
import com.transformsai.nutrifitai.social.SocialModels.Challenge;
import com.transformsai.nutrifitai.social.SocialModels.LivePlayer;
import com.transformsai.nutrifitai.social.SocialModels.Post;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.main.MainActivity;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Final ranking of a challenge from every player's live node (the same scoring on every phone). The host
 * closes the challenge once all players who joined have finished.
 */
public class ChallengeResultActivity extends BaseActivity {
    private static final String EXTRA_ID = "challenge_id";

    public static Intent intent(Context c, String challengeId) {
        return new Intent(c, ChallengeResultActivity.class).putExtra(EXTRA_ID, challengeId);
    }

    private ActivityChallengeResultBinding b;
    private String uid, id;
    @Nullable private Challenge challenge;
    private List<LivePlayer> ranked = new ArrayList<>();
    /** Players' profile photos for the podium (loaded once each). */
    private final java.util.Map<String, android.graphics.Bitmap> photos = new java.util.HashMap<>();
    private final java.util.Set<String> loading = new java.util.HashSet<>();
    private DatabaseReference room;
    private ValueEventListener listener;
    private boolean shared;

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        uid = uidOrFinish();
        id = getIntent().getStringExtra(EXTRA_ID);
        if (uid == null || id == null) {
            finish();
            return;
        }
        b = ActivityChallengeResultBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, false);
        InsetsUtil.padding(b.bottom, false, true);
        b.bar.title.setText(R.string.challenge_results);
        b.bar.back.setOnClickListener(v -> finish());
        Ui.onClick(b.done, v -> {
            startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_DEST, "social")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
        Ui.onClick(b.share, v -> share());
        graph().challenges.challenge(id).observe(this, c -> {
            challenge = c;
            render();
        });
        room = FirebaseConfig.database().getReference("live").child(id);
    }

    @Override
    protected void onStart() {
        super.onStart();
        listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot s) {
                List<LivePlayer> all = new ArrayList<>();
                for (DataSnapshot c : s.getChildren()) {
                    LivePlayer p = c.getValue(LivePlayer.class);
                    if (p == null) continue;
                    p.uid = c.getKey();
                    all.add(p);
                }
                ranked = ChallengeScore.ranked(all);
                render();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                // keep what is shown
            }
        };
        room.addValueEventListener(listener);
    }

    @Override
    protected void onStop() {
        if (listener != null) room.removeEventListener(listener);
        super.onStop();
    }

    private void render() {
        if (b == null) return;
        Challenge c = challenge;
        if (c != null) b.challengeTitle.setText(c.title);
        b.ranking.removeAllViews();
        b.podium.setEntries(podiumEntries());
        b.podium.setVisibility(ranked.isEmpty() ? View.GONE : View.VISIBLE);
        boolean allDone = !ranked.isEmpty();
        for (int i = 0; i < ranked.size(); i++) {
            LivePlayer p = ranked.get(i);
            allDone &= p.done || p.finishMs > 0; // finished, or left early (has a finish time)
            ItemSocialRowBinding row = ItemSocialRowBinding.inflate(getLayoutInflater(), b.ranking, false);
            String medal = i == 0 ? "🥇" : i == 1 ? "🥈" : i == 2 ? "🥉" : (i + 1) + ".";
            com.transformsai.nutrifitai.ui.common.Avatars.person(row.avatar, p.uid, p.name);
            row.title.setText(medal + "  " + getString(R.string.challenge_rank_row, uid.equals(p.uid) ? getString(R.string.you) : p.name, p.score));
            loadPhoto(p.uid);
            row.subtitle.setText(p.done
                    ? getString(R.string.challenge_rank_done, p.reps, p.faults, seconds(p.finishMs))
                    : getString(R.string.challenge_rank_partial, p.reps, p.faults));
            b.ranking.addView(row.getRoot());
        }
        if (!ranked.isEmpty()) {
            LivePlayer top = ranked.get(0);
            b.winner.setText(uid.equals(top.uid) ? getString(R.string.challenge_you_win) : getString(R.string.challenge_wins, top.name));
        }
        b.waiting.setVisibility(allDone ? View.GONE : View.VISIBLE);
        if (c != null && allDone && uid.equals(c.host) && !Challenge.FINISHED.equals(c.status)
                && ranked.size() >= c.accepted.size()) {
            graph().challenges.finish(c.id);
        }
        b.share.setEnabled(myPlace() > 0);
    }

    private static String seconds(long ms) {
        long s = ms / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
    }

    private int myPlace() {
        for (int i = 0; i < ranked.size(); i++) if (uid.equals(ranked.get(i).uid)) return i + 1;
        return 0;
    }

    /** Post the result to friends, or share it anywhere as an image card. */
    private void share() {
        int place = myPlace();
        if (place == 0 || challenge == null) return;
        String[] items = {getString(R.string.badge_post_friends), getString(R.string.badge_share_image)};
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(R.string.share)
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        post();
                        return;
                    }
                    com.transformsai.nutrifitai.ui.common.ShareCards.Card c = new com.transformsai.nutrifitai.ui.common.ShareCards.Card();
                    c.kicker = getString(place == 1 ? R.string.challenge_champion : R.string.post_type_challenge);
                    c.title = challenge.title;
                    c.subtitle = getString(place == 1 ? R.string.challenge_post_won : R.string.challenge_post_played,
                            challenge.title, place, ranked.size());
                    c.podium = podiumEntries(); // the podium shows everyone's points, so no stat tiles here
                    com.transformsai.nutrifitai.ui.common.ShareCards.share(this, c, getString(R.string.share));
                }).show();
    }

    private java.util.List<com.transformsai.nutrifitai.ui.widget.PodiumView.Entry> podiumEntries() {
        java.util.List<com.transformsai.nutrifitai.ui.widget.PodiumView.Entry> out = new ArrayList<>();
        for (LivePlayer p : ranked) {
            boolean mine = uid.equals(p.uid);
            out.add(new com.transformsai.nutrifitai.ui.widget.PodiumView.Entry(mine ? getString(R.string.you) : p.name, p.score, mine, photos.get(p.uid)));
        }
        return out;
    }

    /** Small copy of a player's photo for the podium drawing. */
    private void loadPhoto(String who) {
        if (who == null || photos.containsKey(who) || !loading.add(who)) return;
        graph().runIo(() -> {
            java.io.File f;
            if (who.equals(uid)) {
                com.transformsai.nutrifitai.data.db.entity.ProfileEntity me = graph().db.profiles().get(uid);
                f = me == null || me.avatarLocalPath == null ? null : new java.io.File(me.avatarLocalPath);
            } else {
                com.transformsai.nutrifitai.social.SocialModels.Person p = graph().cards.personBlocking(who);
                f = p == null ? null : graph().cards.photoBlocking(p.photo);
            }
            if (f == null || !f.isFile()) return;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 4;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (bmp == null) return;
            graph().main.post(() -> {
                if (isDestroyed()) return;
                photos.put(who, bmp);
                if (b != null) b.podium.setEntries(podiumEntries());
            });
        });
    }

    private void post() {
        int place = myPlace();
        if (place == 0 || challenge == null || shared) return;
        LivePlayer me = ranked.get(place - 1);
        Map<String, Object> data = new HashMap<>();
        data.put("place", place);
        data.put("score", me.score);
        data.put("reps", me.reps);
        data.put("faults", me.faults);
        data.put("players", ranked.size());
        data.put("title", challenge.title);
        String text = getString(place == 1 ? R.string.challenge_post_won : R.string.challenge_post_played, challenge.title, place, ranked.size());
        shared = true;
        render();
        graph().feed.post(Post.CHALLENGE, text, data, null, r -> {
            if (b == null) return;
            if (!r.ok()) shared = false;
            Ui.snack(b.getRoot(), r.ok() ? getString(R.string.share_done) : r.error);
            render();
        });
    }
}
