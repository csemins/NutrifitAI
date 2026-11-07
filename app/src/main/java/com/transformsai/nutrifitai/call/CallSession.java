package com.transformsai.nutrifitai.call;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.BuildConfig;
import com.transformsai.nutrifitai.data.remote.FirebaseConfig;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A voice/video call between up to 6 people (each pair connects directly, or through a TURN relay; a mesh).
 * Signalling goes through the Realtime Database:
 * <ul>
 * <li>rtc/{room}/members/{uid} = {name, video, audio, at}: who is in. {@code at} is this person's session id (join
 * time in microseconds plus a random tie-breaker) and stays fixed for the whole call.</li>
 * <li>rtc/{room}/sig/{to}/{from} = {sdp: {type, sdp, from, to, n}, ice: {...}}: offer/answer and ICE candidates.
 * Every description carries the sender's and receiver's session ids, so data left over from an earlier call in the
 * same room (rooms are reused per chat) is ignored instead of breaking the new call. Writing a description replaces
 * the whole node, which also clears that pair's old candidates.</li>
 * </ul>
 * Robustness: relay (TURN) credentials come from the server before connecting, so calls work behind carrier NAT;
 * a person whose membership briefly disappears (a network blip) keeps their connection for a grace period; a
 * failed or stalled connection is restarted (ICE restart) by the side that made the offer.
 */
public final class CallSession {
    public static final int MAX_PEOPLE = 6;
    private static final long GRACE_MS = 12_000, STALL_MS = 5_000;
    private static final int MAX_RESTARTS = 4;
    private static final String BYE = "bye";

    /** Another person in the call. */
    public static final class Peer {
        public final String uid;
        public String name = "";
        public boolean videoOn, audioOn;
        @Nullable public VideoTrack video;
        public boolean connected;
        long sid;

        Peer(String uid) {
            this.uid = uid;
        }
    }

    /** Per-person connection state (reset when they rejoin with a new session). */
    private static final class Link {
        final PeerConnection pc;
        @Nullable String appliedSdp;
        int appliedIce;
        boolean remoteReady;
        /** Offer number: answers to an older offer (e.g. before an ICE restart) are ignored. */
        int n;
        int restarts;
        @Nullable Runnable stallCheck;

        Link(PeerConnection pc) {
            this.pc = pc;
        }
    }

    private static EglBase egl;
    private static PeerConnectionFactory factory;

    private final Context app;
    private final String me, myName;
    private final long mySid;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DatabaseReference members, inbox, connectedRef;
    private final DatabaseReference root;
    private final Map<String, Link> links = new HashMap<>();
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private final Map<String, DataSnapshot> latestSignal = new HashMap<>();
    private final Map<String, Runnable> pendingDrops = new HashMap<>();
    private final MutableLiveData<List<Peer>> peersLive = new MutableLiveData<>(new ArrayList<>());
    private final List<PeerConnection.IceServer> ice = new ArrayList<>();
    @Nullable private VideoCapturer capturer;
    @Nullable private VideoSource videoSource;
    @Nullable private VideoTrack localVideo;
    @Nullable private AudioSource audioSource;
    @Nullable private AudioTrack localAudio;
    private boolean videoOn, audioOn, joined, signalling;
    @Nullable private ValueEventListener membersListener, connectedListener;
    @Nullable private ChildEventListener inboxListener;
    private int savedAudioMode = AudioManager.MODE_NORMAL;

    public CallSession(Context c, String room, String me, String myName) {
        this.app = c.getApplicationContext();
        this.me = me;
        this.myName = myName;
        this.mySid = System.currentTimeMillis() * 1000 + new SecureRandom().nextInt(1000);
        this.root = FirebaseConfig.database().getReference("rtc").child(room);
        this.members = root.child("members");
        this.inbox = root.child("sig").child(me);
        this.connectedRef = FirebaseConfig.database().getReference(".info/connected");
        ice.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        ice.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        if (!BuildConfig.TURN_URL.isEmpty()) {
            ice.add(PeerConnection.IceServer.builder(BuildConfig.TURN_URL)
                    .setUsername(BuildConfig.TURN_USER).setPassword(BuildConfig.TURN_PASS).createIceServer());
        }
        ensureFactory(app);
    }

    public static synchronized EglBase egl() {
        if (egl == null) egl = EglBase.create();
        return egl;
    }

    private static synchronized void ensureFactory(Context app) {
        if (factory != null) return;
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions());
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl().getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl().getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    public LiveData<List<Peer>> peers() {
        return peersLive;
    }

    @Nullable
    public VideoTrack localVideo() {
        return localVideo;
    }

    public boolean videoOn() {
        return videoOn;
    }

    public boolean audioOn() {
        return audioOn;
    }

    /**
     * Joins the room. {@code capturer} supplies camera frames (a real camera, or the workout's tracked frames);
     * null means no video can be shared in this call. Local media starts at once; connecting to others starts once
     * the relay servers are known (a few seconds at most).
     */
    public void join(@Nullable VideoCapturer capturer, boolean shareVideo, boolean shareAudio) {
        if (joined) return;
        joined = true;
        this.capturer = capturer;
        if (capturer != null) {
            videoSource = factory.createVideoSource(capturer.isScreencast());
            capturer.initialize(org.webrtc.SurfaceTextureHelper.create("call-capture", egl().getEglBaseContext()), app,
                    videoSource.getCapturerObserver());
            localVideo = factory.createVideoTrack("v_" + me, videoSource);
        }
        audioSource = factory.createAudioSource(new MediaConstraints());
        localAudio = factory.createAudioTrack("a_" + me, audioSource);
        AudioManager am = app.getSystemService(AudioManager.class);
        if (am != null) {
            savedAudioMode = am.getMode();
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(true);
        }
        setVideo(shareVideo);
        setAudio(shareAudio);
        // anything addressed to me from an earlier call in this room is stale
        inbox.removeValue();
        new Thread(() -> {
            List<PeerConnection.IceServer> relay = relayServers();
            main.post(() -> {
                if (!joined) return;
                ice.addAll(relay);
                startSignalling();
            });
        }, "call-relay").start();
    }

    /**
     * Relay servers for this call (blocking, a few seconds at most): any external TURN servers the server lists,
     * plus the server's built-in relay reached through the local HTTPS bridge (CallRelayBridge). Fetched for every
     * call because the built-in relay's login changes when the server restarts.
     */
    private static List<PeerConnection.IceServer> relayServers() {
        String json = App.graph().ai.turnServers();
        List<PeerConnection.IceServer> out = IceServers.parse(json);
        IceServers.Entry own = IceServers.relay(json);
        if (own != null) {
            try {
                int port = CallRelayBridge.start();
                out.add(PeerConnection.IceServer.builder("turn:127.0.0.1:" + port + "?transport=tcp")
                        .setUsername(own.username).setPassword(own.credential).createIceServer());
            } catch (java.io.IOException ignored) {
                // no local bridge: direct connections only
            }
        }
        return out;
    }

    private void startSignalling() {
        signalling = true;
        listenConnection();
        listenMembers();
        listenSignals();
    }

    public void setVideo(boolean on) {
        videoOn = on && localVideo != null;
        if (localVideo != null) localVideo.setEnabled(videoOn);
        if (capturer != null) {
            try {
                if (videoOn) capturer.startCapture(640, 480, 15);
                else capturer.stopCapture();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                // already in that state
            }
        }
        publishState();
    }

    public void setAudio(boolean on) {
        audioOn = on;
        if (localAudio != null) localAudio.setEnabled(on);
        publishState();
    }

    private void publishState() {
        if (!joined || !signalling) return;
        Map<String, Object> m = new HashMap<>();
        m.put("name", myName);
        m.put("video", videoOn);
        m.put("audio", audioOn);
        m.put("at", mySid);
        members.child(me).setValue(m);
    }

    // ------------------------------------------------------------------ presence
    /** After a dropped database connection, re-arm the disconnect cleanup and put myself back in the room. */
    private void listenConnection() {
        connectedListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot s) {
                if (!Boolean.TRUE.equals(s.getValue(Boolean.class)) || !joined) return;
                members.child(me).onDisconnect().removeValue();
                publishState();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError e) { }
        };
        connectedRef.addValueEventListener(connectedListener);
    }

    // ------------------------------------------------------------------ membership
    private void listenMembers() {
        membersListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot s) {
                List<String> present = new ArrayList<>();
                for (DataSnapshot c : s.getChildren()) {
                    String uid = c.getKey();
                    Long sid = c.child("at").getValue(Long.class);
                    if (uid == null || uid.equals(me) || sid == null) continue;
                    if (present.size() >= MAX_PEOPLE - 1) break;
                    present.add(uid);
                    Runnable pending = pendingDrops.remove(uid);
                    if (pending != null) main.removeCallbacks(pending); // came back after a blip
                    Peer p = peers.get(uid);
                    if (p == null) {
                        p = new Peer(uid);
                        p.sid = sid;
                        peers.put(uid, p);
                    } else if (p.sid != sid) {
                        // rejoined (new session): start this pair over
                        closeLink(uid);
                        p.sid = sid;
                        p.video = null;
                        p.connected = false;
                    }
                    p.name = String.valueOf(c.child("name").getValue());
                    p.videoOn = Boolean.TRUE.equals(c.child("video").getValue(Boolean.class));
                    p.audioOn = Boolean.TRUE.equals(c.child("audio").getValue(Boolean.class));
                    // the lower uid offers, so each pair connects exactly once
                    if (!links.containsKey(uid) && offerer(uid)) offer(uid, false);
                    process(uid);
                }
                for (String gone : new ArrayList<>(peers.keySet())) {
                    if (!present.contains(gone)) leaving(gone);
                }
                emit();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                // keep the current peers
            }
        };
        members.addValueEventListener(membersListener);
    }

    private boolean offerer(String uid) {
        return me.compareTo(uid) < 0;
    }

    /** Membership vanished: drop at once unless media is flowing, in which case allow a short reconnect window. */
    private void leaving(String uid) {
        Peer p = peers.get(uid);
        if (p == null || pendingDrops.containsKey(uid)) return;
        if (!p.connected) {
            drop(uid);
            return;
        }
        Runnable r = () -> {
            pendingDrops.remove(uid);
            drop(uid);
            emit();
        };
        pendingDrops.put(uid, r);
        main.postDelayed(r, GRACE_MS);
    }

    private void emit() {
        peersLive.setValue(new ArrayList<>(peers.values()));
    }

    private void closeLink(String uid) {
        Link l = links.remove(uid);
        if (l != null) {
            if (l.stallCheck != null) main.removeCallbacks(l.stallCheck);
            l.pc.close();
        }
    }

    private void drop(String uid) {
        closeLink(uid);
        peers.remove(uid);
        latestSignal.remove(uid);
        Runnable pending = pendingDrops.remove(uid);
        if (pending != null) main.removeCallbacks(pending);
        inbox.child(uid).removeValue();
    }

    // ------------------------------------------------------------------ signalling
    private Link link(String uid) {
        Link existing = links.get(uid);
        if (existing != null) return existing;
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(new ArrayList<>(ice));
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        // keep gathering so a switch between Wi-Fi and mobile data can recover
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        Observer obs = new Observer(uid);
        PeerConnection pc = factory.createPeerConnection(cfg, obs);
        if (pc == null) throw new IllegalStateException("peer connection");
        if (localAudio != null) pc.addTrack(localAudio, List.of("s_" + me));
        if (localVideo != null) pc.addTrack(localVideo, List.of("s_" + me));
        else pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
        Link l = new Link(pc);
        obs.link = l;
        links.put(uid, l);
        return l;
    }

    private DatabaseReference outbox(String to) {
        return root.child("sig").child(to).child(me);
    }

    /** Writes a description, replacing the pair's node (so the old candidates go with it). */
    private void sendSdp(String uid, String type, String sdp, int n) {
        Peer p = peers.get(uid);
        if (p == null) return;
        Map<String, Object> d = new HashMap<>();
        d.put("type", type);
        d.put("sdp", sdp);
        d.put("from", mySid);
        d.put("to", p.sid);
        d.put("n", n);
        Map<String, Object> node = new HashMap<>();
        node.put("sdp", d);
        outbox(uid).setValue(node);
    }

    private void offer(String uid, boolean iceRestart) {
        Link l = link(uid);
        int n = ++l.n;
        l.remoteReady = l.remoteReady && !iceRestart;
        MediaConstraints mc = new MediaConstraints();
        if (iceRestart) mc.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart", "true"));
        l.pc.createOffer(new Sdp() {
            @Override
            public void onCreateSuccess(SessionDescription d) {
                main.post(() -> {
                    if (links.get(uid) != l) return;
                    sendSdp(uid, d.type.canonicalForm(), d.description, n);
                    l.pc.setLocalDescription(new Sdp(), d);
                });
            }
        }, mc);
    }

    private void listenSignals() {
        inboxListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot from, @Nullable String prev) {
                onSignal(from);
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot from, @Nullable String prev) {
                onSignal(from);
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot s) { }

            @Override
            public void onChildMoved(@NonNull DataSnapshot s, @Nullable String p) { }

            @Override
            public void onCancelled(@NonNull DatabaseError e) { }
        };
        inbox.addChildEventListener(inboxListener);
    }

    private void onSignal(DataSnapshot from) {
        String uid = from.getKey();
        if (uid == null) return;
        latestSignal.put(uid, from);
        process(uid);
    }

    /** Applies the newest signal from {@code uid} once both of us are known; stale sessions are ignored. */
    private void process(String uid) {
        DataSnapshot from = latestSignal.get(uid);
        Peer p = peers.get(uid);
        if (from == null || p == null) return;
        DataSnapshot sdp = from.child("sdp");
        String type = sdp.child("type").getValue(String.class), text = sdp.child("sdp").getValue(String.class);
        Long fromSid = sdp.child("from").getValue(Long.class), toSid = sdp.child("to").getValue(Long.class);
        Integer n = sdp.child("n").getValue(Integer.class);
        if (type == null || text == null || fromSid == null || toSid == null) return;
        if (fromSid != p.sid || toSid != mySid) return; // left over from another call
        if (BYE.equals(type)) {
            drop(uid);
            emit();
            return;
        }
        boolean isOffer = "offer".equals(type);
        Link l = isOffer ? link(uid) : links.get(uid);
        if (l == null) return;
        if (!text.equals(l.appliedSdp)) {
            if (!isOffer && (n == null || n != l.n)) return; // answer to an older offer
            l.appliedSdp = text;
            l.appliedIce = 0;
            l.remoteReady = false;
            SessionDescription d = new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), text);
            l.pc.setRemoteDescription(new Sdp() {
                @Override
                public void onSetSuccess() {
                    main.post(() -> {
                        if (links.get(uid) != l) return;
                        l.remoteReady = true;
                        if (isOffer) answer(uid, l, n == null ? 0 : n);
                        applyIce(uid, l);
                    });
                }
            }, d);
            return;
        }
        applyIce(uid, l);
    }

    private void applyIce(String uid, Link l) {
        DataSnapshot from = latestSignal.get(uid);
        if (from == null || !l.remoteReady) return;
        int i = 0;
        for (DataSnapshot c : from.child("ice").getChildren()) {
            if (i++ < l.appliedIce) continue;
            String mid = c.child("mid").getValue(String.class), cand = c.child("c").getValue(String.class);
            Integer idx = c.child("i").getValue(Integer.class);
            if (cand != null && idx != null) l.pc.addIceCandidate(new IceCandidate(mid, idx, cand));
        }
        l.appliedIce = i;
    }

    private void answer(String uid, Link l, int n) {
        l.pc.createAnswer(new Sdp() {
            @Override
            public void onCreateSuccess(SessionDescription d) {
                main.post(() -> {
                    if (links.get(uid) != l) return;
                    sendSdp(uid, d.type.canonicalForm(), d.description, n);
                    l.pc.setLocalDescription(new Sdp(), d);
                });
            }
        }, new MediaConstraints());
    }

    /** The offering side restarts ICE when a connection fails or stays disconnected (network change, relay hiccup). */
    private void onState(String uid, Link l, PeerConnection.PeerConnectionState s) {
        Peer p = peers.get(uid);
        if (p == null) return;
        p.connected = s == PeerConnection.PeerConnectionState.CONNECTED;
        if (p.connected) l.restarts = 0;
        if (l.stallCheck != null) {
            main.removeCallbacks(l.stallCheck);
            l.stallCheck = null;
        }
        if (offerer(uid) && l.restarts < MAX_RESTARTS) {
            if (s == PeerConnection.PeerConnectionState.FAILED) {
                l.restarts++;
                offer(uid, true);
            } else if (s == PeerConnection.PeerConnectionState.DISCONNECTED) {
                l.stallCheck = () -> {
                    l.stallCheck = null;
                    if (links.get(uid) == l && l.pc.connectionState() == PeerConnection.PeerConnectionState.DISCONNECTED) {
                        l.restarts++;
                        offer(uid, true);
                    }
                };
                main.postDelayed(l.stallCheck, STALL_MS);
            }
        }
        emit();
    }

    /** Peer connection events arrive on WebRTC's thread; they are applied on the main thread, current links only. */
    private final class Observer implements PeerConnection.Observer {
        private final String uid;
        @Nullable Link link;

        Observer(String uid) {
            this.uid = uid;
        }

        private boolean current() {
            return link != null && links.get(uid) == link;
        }

        @Override
        public void onIceCandidate(IceCandidate c) {
            Map<String, Object> m = new HashMap<>();
            m.put("mid", c.sdpMid);
            m.put("i", c.sdpMLineIndex);
            m.put("c", c.sdp);
            main.post(() -> {
                if (current()) outbox(uid).child("ice").push().setValue(m);
            });
        }

        @Override
        public void onAddTrack(RtpReceiver receiver, org.webrtc.MediaStream[] streams) {
            MediaStreamTrack t = receiver.track();
            if (t instanceof VideoTrack) {
                main.post(() -> {
                    Peer p = peers.get(uid);
                    if (p == null || !current()) return;
                    p.video = (VideoTrack) t;
                    emit();
                });
            }
        }

        @Override
        public void onConnectionChange(PeerConnection.PeerConnectionState s) {
            main.post(() -> {
                if (current()) onState(uid, link, s);
            });
        }

        @Override public void onSignalingChange(PeerConnection.SignalingState s) { }
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) { }
        @Override public void onIceConnectionReceivingChange(boolean b) { }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) { }
        @Override public void onIceCandidatesRemoved(IceCandidate[] c) { }
        @Override public void onAddStream(org.webrtc.MediaStream s) { }
        @Override public void onRemoveStream(org.webrtc.MediaStream s) { }
        @Override public void onDataChannel(org.webrtc.DataChannel d) { }
        @Override public void onRenegotiationNeeded() { }
    }

    /** SdpObserver with empty defaults. */
    private static class Sdp implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription d) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String e) { }
        @Override public void onSetFailure(String e) { }
    }

    /** Leaves the room and releases the camera, microphone and connections. */
    public void leave() {
        if (!joined) return;
        joined = false;
        if (signalling) {
            // tell everyone right away (otherwise they would wait out the reconnect window)
            for (Peer p : peers.values()) sendSdp(p.uid, BYE, "", 0);
            if (membersListener != null) members.removeEventListener(membersListener);
            if (inboxListener != null) inbox.removeEventListener(inboxListener);
            if (connectedListener != null) connectedRef.removeEventListener(connectedListener);
            members.child(me).onDisconnect().cancel();
            members.child(me).removeValue();
            inbox.removeValue();
        }
        signalling = false;
        for (Runnable r : pendingDrops.values()) main.removeCallbacks(r);
        pendingDrops.clear();
        for (String uid : new ArrayList<>(links.keySet())) closeLink(uid);
        peers.clear();
        latestSignal.clear();
        emit();
        if (capturer != null) {
            try {
                capturer.stopCapture();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ignored) {
                // not capturing
            }
            capturer.dispose();
        }
        if (videoSource != null) videoSource.dispose();
        if (audioSource != null) audioSource.dispose();
        AudioManager am = app.getSystemService(AudioManager.class);
        if (am != null) {
            am.setSpeakerphoneOn(false);
            am.setMode(savedAudioMode);
        }
    }
}
