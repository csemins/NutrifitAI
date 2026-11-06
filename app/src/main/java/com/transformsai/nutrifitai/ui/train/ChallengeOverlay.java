package com.transformsai.nutrifitai.ui.train;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.call.CallSession;
import com.transformsai.nutrifitai.call.FrameCapturer;
import com.transformsai.nutrifitai.databinding.ActivityWorkoutBinding;
import com.transformsai.nutrifitai.databinding.ItemLiveTileBinding;
import com.transformsai.nutrifitai.databinding.ItemScoreRowBinding;
import com.transformsai.nutrifitai.fitness.WorkoutEngine;
import com.transformsai.nutrifitai.fitness.pose.Pose;
import com.transformsai.nutrifitai.social.ChallengeScore;
import com.transformsai.nutrifitai.social.ChallengeSession;
import com.transformsai.nutrifitai.social.FeedRepository;
import com.transformsai.nutrifitai.social.SocialModels.LivePlayer;
import com.transformsai.nutrifitai.ui.social.ReactionPicker;
import com.transformsai.nutrifitai.ui.widget.Dp;

import org.webrtc.RendererCommon;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Challenge mode on the workout screen. Forwards what the camera tracker counts to {@link ChallengeSession} and
 * shows: a live scoreboard (everyone, ranked, points updating as reps land), the other players as call tiles
 * (their camera video when they share it, otherwise their tracked stick figure), reactions flying across the
 * screen, and my call controls (microphone, camera video or stick figure only, react). The call runs in the
 * challenge's room; my video comes from the same camera frames the tracker sees. Manual +1 reps never count.
 */
final class ChallengeOverlay {
    /** A hold that keeps breaking form costs at most one fault per this interval. */
    private static final long HOLD_FAULT_GAP_MS = 3000;
    private static final long STALE_MS = 6000, REACTION_SHOW_MS = 2500;
    private static final String[] MEDALS = {"🥇", "🥈", "🥉"};

    private final ChallengeSession session;
    private final ActivityWorkoutBinding b;
    private final Context c;
    @Nullable private CallSession call;
    @Nullable private FrameCapturer frames;
    private final Map<String, ItemLiveTileBinding> tiles = new HashMap<>();
    private final Map<String, VideoTrack> attached = new HashMap<>();
    private final Map<String, Long> seenReactions = new HashMap<>();
    private List<LivePlayer> others = new ArrayList<>();
    private List<CallSession.Peer> peers = new ArrayList<>();
    private long lastHoldFault, startedAt;
    private boolean shareVideo, shareAudio;
    private int lastScore;

    ChallengeOverlay(ChallengeSession session, ActivityWorkoutBinding b, LifecycleOwner owner, String room, String me) {
        this.session = session;
        this.b = b;
        this.c = b.getRoot().getContext();
        b.livePanel.setVisibility(View.VISIBLE);
        b.scoreboard.setVisibility(View.VISIBLE);
        b.muscleCard.setVisibility(View.GONE); // the scoreboard takes its corner
        startedAt = SystemClock.elapsedRealtime();
        session.others().observe(owner, list -> {
            others = list == null ? new ArrayList<>() : list;
            renderBoard();
            renderTiles();
            showNewReactions();
        });
        session.join();

        // the call: everyone starts with mic off and stick-figure only; each person opts in
        frames = new FrameCapturer();
        call = new CallSession(c, room, me, session.me().name);
        call.join(frames, false, false);
        call.peers().observe(owner, l -> {
            peers = l == null ? new ArrayList<>() : l;
            renderTiles();
        });
        b.liveMic.setOnClickListener(v -> toggleMic());
        b.liveCam.setOnClickListener(v -> toggleVideo());
        b.liveReact.setOnClickListener(v -> ReactionPicker.show(v, null, key -> {
            if (key == null) return;
            session.react(key);
            flyReaction(c.getString(R.string.you), key);
        }));
        renderControls();
        renderBoard();
    }

    // ------------------------------------------------------------------ tracker events
    void rep() {
        session.rep();
        renderBoard();
    }

    void fault() {
        session.fault();
        renderBoard();
    }

    void holdFault() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastHoldFault < HOLD_FAULT_GAP_MS) return;
        lastHoldFault = now;
        fault();
    }

    void pose(Pose p, boolean mirror) {
        session.pose(p, mirror);
    }

    /** Submit thread: my camera frame, sent to the call while I share video. */
    void frame(Bitmap upright) {
        FrameCapturer f = frames;
        if (f != null && shareVideo && f.running()) f.push(upright);
    }

    void progress(WorkoutEngine e) {
        double held = e.heldMs() / 1000.0;
        for (WorkoutEngine.StepResult r : e.results()) held += r.heldMs / 1000.0;
        String name = e.exercise() == null ? "" : e.exercise().name;
        session.progress(e.index(), e.stepCount(), name, held);
        renderBoard();
    }

    /** @return my final points. */
    int finish(WorkoutEngine e) {
        boolean completed = e.results().size() >= e.stepCount();
        for (WorkoutEngine.StepResult r : e.results()) completed &= !r.skipped;
        return session.finish(completed);
    }

    ChallengeSession session() {
        return session;
    }

    void leave() {
        session.leave();
        for (String uid : new ArrayList<>(tiles.keySet())) release(uid);
        if (call != null) call.leave();
        call = null;
        frames = null;
    }

    /** Camera stopped (screen hidden): stop sending video until the user turns it on again. */
    void cameraStopped() {
        if (shareVideo) toggleVideo();
    }

    /** Once a second: keeps the scoreboard clock moving. */
    void tick() {
        renderBoard();
    }

    // ------------------------------------------------------------------ call controls
    private boolean has(String perm) {
        return ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void toggleMic() {
        if (call == null) return;
        if (!shareAudio && !has(Manifest.permission.RECORD_AUDIO)) {
            if (c instanceof WorkoutActivity) ((WorkoutActivity) c).requestMic();
            return;
        }
        shareAudio = !shareAudio;
        call.setAudio(shareAudio);
        renderControls();
    }

    void micGranted() {
        if (!shareAudio) toggleMic();
    }

    private void toggleVideo() {
        if (call == null) return;
        shareVideo = !shareVideo;
        call.setVideo(shareVideo);
        renderControls();
    }

    private void renderControls() {
        b.liveMic.setImageResource(shareAudio ? R.drawable.ic_mic : R.drawable.ic_mic_off);
        b.liveMic.setActivated(shareAudio);
        b.liveMic.setImageTintList(ContextCompat.getColorStateList(c, shareAudio ? R.color.share_bg : android.R.color.white));
        b.liveMic.setContentDescription(c.getString(shareAudio ? R.string.call_mute : R.string.call_unmute));
        b.liveCam.setImageResource(shareVideo ? R.drawable.ic_video : R.drawable.ic_stick);
        b.liveCam.setActivated(shareVideo);
        b.liveCam.setImageTintList(ContextCompat.getColorStateList(c, shareVideo ? R.color.share_bg : android.R.color.white));
        b.liveCam.setContentDescription(c.getString(shareVideo ? R.string.live_share_stick : R.string.live_share_video));
    }

    // ------------------------------------------------------------------ scoreboard
    private void renderBoard() {
        LivePlayer me = session.me();
        List<LivePlayer> all = new ArrayList<>(others);
        all.add(me);
        all = ChallengeScore.ranked(all);
        long s = (SystemClock.elapsedRealtime() - startedAt) / 1000;
        b.scoreTitle.setText(c.getString(R.string.live_board_title, String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60)));
        while (b.scoreRows.getChildCount() < all.size()) {
            b.scoreRows.addView(ItemScoreRowBinding.inflate(LayoutInflater.from(c), b.scoreRows, false).getRoot());
        }
        while (b.scoreRows.getChildCount() > all.size()) b.scoreRows.removeViewAt(b.scoreRows.getChildCount() - 1);
        StringBuilder spoken = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            LivePlayer p = all.get(i);
            ItemScoreRowBinding row = ItemScoreRowBinding.bind(b.scoreRows.getChildAt(i));
            boolean mine = p.uid.equals(me.uid);
            row.place.setText(i < MEDALS.length ? MEDALS[i] : String.valueOf(i + 1));
            row.name.setText(mine ? c.getString(R.string.you) : p.name);
            row.points.setText(String.valueOf(Math.max(0, p.score)));
            row.getRoot().setBackgroundResource(mine ? R.drawable.bg_score_me : 0);
            if (p.done) row.name.setText(row.name.getText() + " ✓");
            spoken.append(i + 1).append(". ").append(row.name.getText()).append(' ').append(Math.max(0, p.score)).append(". ");
        }
        b.scoreboard.setContentDescription(c.getString(R.string.cd_scoreboard, spoken.toString()));
        int now = me.score;
        if (now != lastScore) {
            popPoints(now - lastScore);
            lastScore = now;
        }
    }

    /** "+10" / "-15" floating up from the scoreboard. */
    private void popPoints(int delta) {
        if (delta == 0) return;
        TextView t = new TextView(c);
        t.setText(delta > 0 ? "+" + delta : String.valueOf(delta));
        t.setTextSize(22);
        t.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(c, R.font.jakarta_extrabold));
        t.setTextColor(ContextCompat.getColor(c, delta > 0 ? R.color.share_lime : R.color.nl_error));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        int[] at = new int[2], root = new int[2];
        b.scoreboard.getLocationInWindow(at);
        b.fxLayer.getLocationInWindow(root);
        lp.leftMargin = at[0] - root[0] + b.scoreboard.getWidth() + (int) Dp.px(c, 6);
        lp.topMargin = at[1] - root[1] + (int) Dp.px(c, 10);
        b.fxLayer.addView(t, lp);
        t.setScaleX(0.4f);
        t.setScaleY(0.4f);
        t.animate().scaleX(1f).scaleY(1f).translationY(-Dp.px(c, 40)).setInterpolator(new OvershootInterpolator())
                .setDuration(380).withEndAction(() -> t.animate().alpha(0f).translationY(-Dp.px(c, 70)).setDuration(450)
                        .withEndAction(() -> b.fxLayer.removeView(t)).start()).start();
    }

    // ------------------------------------------------------------------ reactions
    private void showNewReactions() {
        long now = System.currentTimeMillis();
        for (LivePlayer p : others) {
            if (p.reaction == null || p.reactionAt <= 0 || now - p.reactionAt > REACTION_SHOW_MS * 2) continue;
            Long seen = seenReactions.get(p.uid);
            if (seen != null && seen >= p.reactionAt) continue;
            seenReactions.put(p.uid, p.reactionAt);
            flyReaction(p.name, p.reaction);
            ItemLiveTileBinding t = tiles.get(p.uid);
            if (t != null) {
                t.reaction.setText(FeedRepository.emoji(p.reaction));
                t.reaction.setVisibility(View.VISIBLE);
                t.reaction.setScaleX(0f);
                t.reaction.setScaleY(0f);
                t.reaction.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator(3f)).setDuration(300).start();
                t.reaction.postDelayed(() -> t.reaction.setVisibility(View.GONE), REACTION_SHOW_MS);
            }
        }
    }

    /** A big emoji with the sender's name drifting up the right side of the screen. */
    private void flyReaction(String who, String key) {
        TextView t = new TextView(c);
        t.setText(FeedRepository.emoji(key) + "\n" + who);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(14);
        t.setShadowLayer(8, 0, 2, 0x99000000);
        android.text.SpannableString big = new android.text.SpannableString(t.getText());
        big.setSpan(new android.text.style.RelativeSizeSpan(3f), 0, FeedRepository.emoji(key).length(), 0);
        t.setText(big);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        b.fxLayer.announceForAccessibility(c.getString(R.string.cd_reaction_from, who, ReactionPicker.label(c, key)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = (int) Dp.px(c, 24 + (float) Math.random() * 60);
        lp.bottomMargin = (int) Dp.px(c, 220);
        b.fxLayer.addView(t, lp);
        t.setAlpha(0f);
        t.animate().alpha(1f).translationY(-Dp.px(c, 260)).setDuration(2200).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> b.fxLayer.removeView(t)).start();
        t.postDelayed(() -> t.animate().alpha(0f).setDuration(600).start(), 1500);
    }

    // ------------------------------------------------------------------ tiles
    private void renderTiles() {
        List<String> ids = new ArrayList<>();
        for (LivePlayer p : others) ids.add(p.uid);
        for (String uid : new ArrayList<>(tiles.keySet())) if (!ids.contains(uid)) release(uid);
        boolean order = b.liveTiles.getChildCount() == ids.size();
        for (int i = 0; order && i < ids.size(); i++) order = b.liveTiles.getChildAt(i) == tileFor(ids.get(i)).getRoot();
        if (!order) { // re-order only when the ranking changes (moving video surfaces makes them blink)
            b.liveTiles.removeAllViews();
            for (String uid : ids) b.liveTiles.addView(tileFor(uid).getRoot());
        }
        for (int i = 0; i < others.size(); i++) bindTile(others.get(i), i, tiles.get(others.get(i).uid));
    }

    private ItemLiveTileBinding tileFor(String uid) {
        ItemLiveTileBinding t = tiles.get(uid);
        if (t == null) {
            t = ItemLiveTileBinding.inflate(LayoutInflater.from(c), b.liveTiles, false);
            t.video.init(CallSession.egl().getEglBaseContext(), null);
            t.video.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
            t.video.setZOrderMediaOverlay(true);
            tiles.put(uid, t);
        }
        return t;
    }

    private void bindTile(LivePlayer p, int index, ItemLiveTileBinding t) {
        CallSession.Peer peer = null;
        for (CallSession.Peer x : peers) if (x.uid.equals(p.uid)) peer = x;
        VideoTrack track = peer != null && peer.videoOn && peer.connected ? peer.video : null;
        VideoTrack was = attached.get(p.uid);
        if (was != track) {
            if (was != null) was.removeSink(t.video);
            if (track != null) {
                track.addSink(t.video);
                attached.put(p.uid, track);
            } else {
                attached.remove(p.uid);
                t.video.clearImage();
            }
        }
        t.video.setVisibility(track == null ? View.GONE : View.VISIBLE);
        t.pose.setVisibility(track == null ? View.VISIBLE : View.GONE);
        t.pose.setPose(p.done || track != null ? null : p.pose);
        t.muted.setVisibility(peer != null && peer.audioOn ? View.VISIBLE : View.GONE);
        // ranking is from the full board (me included)
        int place = 1;
        for (LivePlayer o : others) if (ChallengeScore.RANKING.compare(o, p) < 0) place++;
        if (ChallengeScore.RANKING.compare(session.me(), p) < 0) place++;
        t.rank.setText(place <= MEDALS.length ? MEDALS[place - 1] : c.getString(R.string.live_rank, place));
        t.name.setText(p.name);
        t.stats.setText(c.getString(R.string.live_stats, p.reps, Math.max(0, p.score)));
        boolean stale = System.currentTimeMillis() - p.at > STALE_MS;
        t.live.setText(p.done ? c.getString(R.string.live_done) : stale ? c.getString(R.string.live_waiting) : c.getString(R.string.live_on));
        t.live.setTextColor(c.getColor(p.done ? R.color.nl_lime : stale ? R.color.nl_text_2 : R.color.nl_error));
        t.getRoot().setContentDescription(c.getString(R.string.cd_live_tile, p.name, place, p.reps, p.faults, Math.max(0, p.score)));
    }

    private void release(String uid) {
        ItemLiveTileBinding t = tiles.remove(uid);
        VideoTrack track = attached.remove(uid);
        if (t == null) return;
        if (track != null) track.removeSink(t.video);
        t.video.release();
        if (t.getRoot().getParent() != null) b.liveTiles.removeView(t.getRoot());
    }
}
