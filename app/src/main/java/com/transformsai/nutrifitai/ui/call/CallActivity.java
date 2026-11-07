package com.transformsai.nutrifitai.ui.call;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.call.CallSession;
import com.transformsai.nutrifitai.databinding.ActivityCallBinding;
import com.transformsai.nutrifitai.databinding.ItemCallTileBinding;
import com.transformsai.nutrifitai.ui.common.Avatars;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;

import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.RendererCommon;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A voice or video call with a friend or a friend group (up to {@link CallSession#MAX_PEOPLE}). Everyone chooses
 * whether to share camera and microphone; tiles show video, or the person's avatar while their camera is off.
 * The camera and microphone are released when the call ends or the screen is closed.
 */
public class CallActivity extends BaseActivity {
    private static final String EXTRA_ROOM = "room", EXTRA_TITLE = "title", EXTRA_VIDEO = "video";

    private ActivityCallBinding b;
    @Nullable private CallSession call;
    @Nullable private CameraVideoCapturer capturer;
    private String me, title;
    private boolean wantVideo, joined, frontCamera = true;
    private long startedAt;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, ItemCallTileBinding> tiles = new HashMap<>();
    private final Map<String, VideoTrack> attached = new HashMap<>();
    private List<CallSession.Peer> peers = new ArrayList<>();
    private final List<String> shownIds = new ArrayList<>();
    @Nullable private com.transformsai.nutrifitai.data.db.entity.ProfileEntity myProfile;

    private final com.transformsai.nutrifitai.ui.common.Permissions.Asker asker = new com.transformsai.nutrifitai.ui.common.Permissions.Asker(this, () -> this);
    private final ActivityResultLauncher<String[]> perms = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> start());

    public static Intent intent(Context c, String room, String title, boolean video) {
        return new Intent(c, CallActivity.class).putExtra(EXTRA_ROOM, room).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_VIDEO, video);
    }

    @Override
    protected boolean alwaysDark() {
        return true;
    }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        me = uidOrFinish();
        String room = getIntent().getStringExtra(EXTRA_ROOM);
        if (me == null) return;
        if (room == null) {
            finish();
            return;
        }
        title = getIntent().getStringExtra(EXTRA_TITLE);
        wantVideo = getIntent().getBooleanExtra(EXTRA_VIDEO, false);
        b = ActivityCallBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        b.title.setText(title);
        b.status.setText(R.string.call_connecting);
        b.hangup.setOnClickListener(v -> finish());
        b.mic.setOnClickListener(v -> {
            if (call == null) return;
            if (!has(Manifest.permission.RECORD_AUDIO)) { // declined before: ask again now that it's needed
                asker.run(com.transformsai.nutrifitai.ui.common.Permissions.MIC, R.string.perm_why_mic, () -> {
                    if (call == null) return;
                    call.setAudio(true);
                    renderControls();
                    renderTiles();
                });
                return;
            }
            call.setAudio(!call.audioOn());
            renderControls();
            renderTiles();
        });
        b.camera.setOnClickListener(v -> {
            if (call == null) return;
            if (!has(Manifest.permission.CAMERA)) {
                wantVideo = true;
                asker.run(com.transformsai.nutrifitai.ui.common.Permissions.CAMERA, R.string.perm_why_camera, this::start);
                return;
            }
            call.setVideo(!call.videoOn());
            renderControls();
            renderTiles();
        });
        b.flip.setOnClickListener(v -> {
            if (capturer == null) return;
            frontCamera = !frontCamera;
            capturer.switchCamera(null);
            ItemCallTileBinding t = tiles.get(me);
            if (t != null) t.video.setMirror(frontCamera);
        });
        graph().profile.observe(me).observe(this, p -> {
            myProfile = p;
            renderTiles();
        });
        graph().runIo(() -> {
            String name = graph().cards.myName();
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                call = new CallSession(this, room, me, name);
                call.peers().observe(this, l -> {
                    peers = l == null ? new ArrayList<>() : l;
                    renderTiles();
                });
                List<String> need = new ArrayList<>();
                if (!has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
                if (wantVideo && !has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
                if (need.isEmpty()) start();
                else perms.launch(need.toArray(new String[0]));
            });
        });
    }

    private boolean has(String p) {
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED;
    }

    /** Joins once; later calls (after granting a permission) only switch tracks on. */
    private void start() {
        if (call == null || isFinishing()) return;
        boolean audio = has(Manifest.permission.RECORD_AUDIO);
        boolean video = wantVideo && has(Manifest.permission.CAMERA);
        if (!joined) {
            joined = true;
            capturer = has(Manifest.permission.CAMERA) ? createCapturer() : null;
            call.join(capturer, video, audio);
            startedAt = SystemClock.elapsedRealtime();
            tickTimer();
            if (!audio) Ui.snack(b.getRoot(), getString(R.string.call_no_mic));
        } else {
            if (capturer == null && has(Manifest.permission.CAMERA)) {
                Ui.snack(b.getRoot(), getString(R.string.call_rejoin_for_camera));
            }
            call.setAudio(audio);
            call.setVideo(video);
        }
        renderControls();
        renderTiles();
    }

    @Nullable
    private CameraVideoCapturer createCapturer() {
        Camera2Enumerator e = new Camera2Enumerator(this);
        String back = null;
        for (String name : e.getDeviceNames()) {
            if (e.isFrontFacing(name)) return e.createCapturer(name, null);
            if (back == null) back = name;
        }
        frontCamera = false;
        return back == null ? null : e.createCapturer(back, null);
    }

    private void renderControls() {
        if (call == null) return;
        boolean mic = call.audioOn(), cam = call.videoOn();
        b.mic.setImageResource(mic ? R.drawable.ic_mic : R.drawable.ic_mic_off);
        b.mic.setActivated(!mic);
        b.mic.setImageTintList(ContextCompat.getColorStateList(this, mic ? R.color.share_text : R.color.share_bg));
        b.mic.setContentDescription(getString(mic ? R.string.call_mute : R.string.call_unmute));
        b.camera.setImageResource(cam ? R.drawable.ic_video : R.drawable.ic_video_off);
        b.camera.setActivated(!cam);
        b.camera.setImageTintList(ContextCompat.getColorStateList(this, cam ? R.color.share_text : R.color.share_bg));
        b.camera.setContentDescription(getString(cam ? R.string.call_camera_off : R.string.call_camera_on));
        b.flip.setVisibility(cam && capturer != null ? View.VISIBLE : View.GONE);
    }

    /** Me first, then everyone else; 1-2 people stack, 3-4 make a 2×2 grid, 5-6 a 3×2 grid. */
    private void renderTiles() {
        if (b == null || call == null) return;
        List<String> ids = new ArrayList<>();
        ids.add(me);
        for (CallSession.Peer p : peers) ids.add(p.uid);
        if (ids.equals(shownIds)) { // same people: update in place (re-adding video surfaces would flicker)
            for (String uid : ids) bindTile(uid, tiles.get(uid));
            renderStatus();
            return;
        }
        shownIds.clear();
        shownIds.addAll(ids);
        // drop tiles of people who left
        for (String uid : new ArrayList<>(tiles.keySet())) {
            if (!ids.contains(uid)) release(uid);
        }
        for (ItemCallTileBinding t : tiles.values()) {
            if (t.getRoot().getParent() != null) ((LinearLayout) t.getRoot().getParent()).removeView(t.getRoot());
        }
        b.grid.removeAllViews();
        int n = ids.size();
        int cols = n <= 2 ? 1 : 2;
        LinearLayout row = null;
        for (int i = 0; i < n; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                b.grid.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            }
            String uid = ids.get(i);
            ItemCallTileBinding t = tiles.get(uid);
            if (t == null) {
                t = ItemCallTileBinding.inflate(LayoutInflater.from(this), row, false);
                t.video.init(CallSession.egl().getEglBaseContext(), null);
                t.video.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
                t.video.setEnableHardwareScaler(true);
                tiles.put(uid, t);
            }
            row.addView(t.getRoot());
            bindTile(uid, t);
        }
        renderStatus();
    }

    private void renderStatus() {
        int connected = 0;
        for (CallSession.Peer p : peers) if (p.connected) connected++;
        b.status.setText(peers.isEmpty() ? getString(R.string.call_waiting)
                : getResources().getQuantityString(R.plurals.call_in_call, connected + 1, connected + 1) + " · " + elapsed());
    }

    private void bindTile(String uid, ItemCallTileBinding t) {
        boolean mine = uid.equals(me);
        CallSession.Peer peer = null;
        for (CallSession.Peer p : peers) if (p.uid.equals(uid)) peer = p;
        String name = mine ? getString(R.string.you) : peer == null ? "" : peer.name;
        VideoTrack track = mine ? (call != null && call.videoOn() ? call.localVideo() : null)
                : peer != null && peer.videoOn ? peer.video : null;
        VideoTrack was = attached.get(uid);
        if (was != track) {
            if (was != null) was.removeSink(t.video);
            if (track != null) track.addSink(t.video);
            if (track == null) t.video.clearImage();
            if (track == null) attached.remove(uid);
            else attached.put(uid, track);
        }
        t.video.setMirror(mine && frontCamera);
        t.video.setVisibility(track == null ? View.INVISIBLE : View.VISIBLE);
        t.avatar.setVisibility(track == null ? View.VISIBLE : View.GONE);
        if (track == null) {
            if (mine) Avatars.mine(t.avatar, myProfile);
            else if (!uid.equals(t.avatar.getTag())) Avatars.person(t.avatar, uid, name);
        }
        t.name.setText(name);
        boolean muted = mine ? call != null && !call.audioOn() : peer != null && !peer.audioOn;
        t.muted.setVisibility(muted ? View.VISIBLE : View.GONE);
        t.connecting.setVisibility(!mine && peer != null && !peer.connected ? View.VISIBLE : View.GONE);
        t.getRoot().setContentDescription(name + (muted ? ", " + getString(R.string.call_muted) : "")
                + (track == null ? ", " + getString(R.string.call_camera_is_off) : ""));
    }

    private void release(String uid) {
        ItemCallTileBinding t = tiles.remove(uid);
        VideoTrack track = attached.remove(uid);
        if (t == null) return;
        if (track != null) track.removeSink(t.video);
        t.video.release();
    }

    private String elapsed() {
        long s = (SystemClock.elapsedRealtime() - startedAt) / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60);
    }

    private void tickTimer() {
        main.postDelayed(() -> {
            if (b == null || isFinishing()) return;
            if (!peers.isEmpty()) renderStatus();
            tickTimer();
        }, 1000);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        for (String uid : new ArrayList<>(tiles.keySet())) release(uid);
        if (call != null) call.leave(); // releases camera, microphone and connections
        call = null;
        super.onDestroy();
    }
}
