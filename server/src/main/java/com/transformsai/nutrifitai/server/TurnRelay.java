package com.transformsai.nutrifitai.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Call relay that runs on this machine and is reached through the HTTPS tunnel, so it needs no open ports, no
 * public IP and no third-party service. Each phone runs a tiny local bridge (CallRelayBridge in the app) that hands
 * WebRTC a "turn:127.0.0.1:port?transport=tcp" server and carries that TCP byte stream over HTTPS:
 * POST /v1/relay/open, POST /v1/relay/{id}/up?off=N (bytes from the phone), GET /v1/relay/{id}/down?off=N (a
 * streamed response with bytes for the phone). Offsets make both directions lossless across reconnects.
 * <p>
 * The byte stream is standard TURN over TCP (RFC 5766 framing, long-term credentials): Allocate, Refresh,
 * CreatePermission, ChannelBind, Send/Data indications and ChannelData. Allocations are virtual: every relayed
 * address is RELAY_IP:port on this server, and data sent to another allocation is handed over in memory. Data for
 * any address that is not one of our allocations is dropped, so this cannot be used as an open proxy.
 */
final class TurnRelay {
    static final int MAGIC = 0x2112A442;
    static final String REALM = "nutrifit";
    static final int MAX_SESSIONS = 4000, MAX_PER_USER = 24, MAX_DOWN_BUFFER = 16 << 20;
    static final long IDLE_MS = 90_000;
    /** Advertised relayed address; never routed on the internet (only meaningful inside this relay). */
    static final byte[] RELAY_IP = {10, (byte) 254, (byte) 254, 1};

    final String username = "nutrifit";
    final String password;
    private final byte[] key, nonce;
    private final LongSupplier clock;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<Integer, Session> byPort = new ConcurrentHashMap<>();
    private final AtomicInteger nextPort = new AtomicInteger(20000);
    private final SecureRandom random = new SecureRandom();

    TurnRelay(LongSupplier clock) {
        this.clock = clock;
        byte[] p = new byte[18];
        random.nextBytes(p);
        password = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(p);
        key = md5(username + ":" + REALM + ":" + password);
        byte[] n = new byte[12];
        random.nextBytes(n);
        nonce = hex(n).getBytes(StandardCharsets.US_ASCII);
    }

    /** One phone-side TCP connection to the relay. */
    final class Session {
        final String id, uid;
        private byte[] in = new byte[0];
        private long upOffset;
        private byte[] down = new byte[4096];
        private int downLen;
        private long downBase;
        private int port;
        private long allocExpires;
        private final Map<Integer, Integer> chanToPort = new HashMap<>(), portToChan = new HashMap<>();
        volatile long lastSeen;
        volatile boolean closed;

        Session(String id, String uid) {
            this.id = id;
            this.uid = uid;
            lastSeen = clock.getAsLong();
        }

        /** Appends bytes for the phone and wakes a waiting download. */
        synchronized void emit(byte[] b) {
            if (closed) return;
            if (downLen + b.length > MAX_DOWN_BUFFER) {
                closed = true; // the phone stopped reading: give up on this connection
                notifyAll();
                return;
            }
            if (downLen + b.length > down.length) down = Arrays.copyOf(down, Math.max(down.length * 2, downLen + b.length));
            System.arraycopy(b, 0, down, downLen, b.length);
            downLen += b.length;
            notifyAll();
        }

        /** The phone has received everything before {@code ack}: release it. */
        synchronized void ack(long ack) {
            if (ack <= downBase || ack > downBase + downLen) return;
            int drop = (int) (ack - downBase);
            System.arraycopy(down, drop, down, 0, downLen - drop);
            downLen -= drop;
            downBase = ack;
        }

        /**
         * Bytes from stream offset {@code from} on, waiting up to {@code waitMs} for some to arrive (empty array on
         * timeout). Null when the session is closed or {@code from} was already released.
         */
        synchronized byte[] read(long from, long waitMs) throws InterruptedException {
            lastSeen = clock.getAsLong();
            if (from < downBase) return null;
            long until = System.currentTimeMillis() + waitMs;
            while (downBase + downLen <= from && !closed) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) return new byte[0];
                wait(left);
            }
            if (closed) return null;
            int at = (int) (from - downBase);
            return Arrays.copyOfRange(down, at, downLen);
        }

        /** Data from another allocation: ChannelData when a channel is bound, else a Data indication. */
        synchronized void deliver(int fromPort, byte[] data) {
            if (closed || port == 0) return;
            Integer chan = portToChan.get(fromPort);
            if (chan != null) {
                byte[] f = new byte[4 + ((data.length + 3) & ~3)];
                f[0] = (byte) (chan >> 8);
                f[1] = (byte) (int) chan;
                f[2] = (byte) (data.length >> 8);
                f[3] = (byte) data.length;
                System.arraycopy(data, 0, f, 4, data.length);
                emit(f);
            } else {
                byte[] tid = new byte[12];
                random.nextBytes(tid);
                emit(message(0x0017, tid, List.of(attr(0x0012, xorAddress(fromPort)), attr(0x0013, data)), false));
            }
        }

        synchronized void close() {
            closed = true;
            if (port != 0) byPort.remove(port, this);
            port = 0;
            notifyAll();
        }
    }

    /** Data to hand to another allocation once the sender's lock is released. */
    private record Delivery(int toPort, int fromPort, byte[] data) { }

    // ------------------------------------------------------------------ sessions
    Session open(String uid) throws ApiException {
        sweep();
        if (sessions.size() >= MAX_SESSIONS) throw new ApiException(503, "relay_busy", "Call relay is busy.");
        long mine = sessions.values().stream().filter(s -> s.uid.equals(uid)).count();
        if (mine >= MAX_PER_USER) throw new ApiException(429, "relay_limit", "Too many relay connections.");
        byte[] b = new byte[16];
        random.nextBytes(b);
        Session s = new Session(hex(b), uid);
        sessions.put(s.id, s);
        return s;
    }

    Session get(String id, String uid) throws ApiException {
        Session s = sessions.get(id);
        if (s == null || s.closed || !s.uid.equals(uid)) throw new ApiException(404, "relay_gone", "Relay connection closed.");
        return s;
    }

    void close(Session s) {
        s.close();
        sessions.remove(s.id, s);
    }

    int size() {
        return sessions.size();
    }

    void sweep() {
        long now = clock.getAsLong();
        for (Session s : sessions.values()) {
            boolean expired;
            synchronized (s) {
                expired = s.port != 0 && now > s.allocExpires;
                if (expired) {
                    byPort.remove(s.port, s);
                    s.port = 0;
                }
            }
            if (s.closed || now - s.lastSeen > IDLE_MS) close(s);
        }
    }

    /**
     * Bytes from the phone at stream offset {@code off}; repeated bytes (a retried upload) are skipped.
     * Returns the new upload offset.
     */
    long up(Session s, long off, byte[] data) throws ApiException {
        List<Delivery> out = new ArrayList<>();
        long result;
        synchronized (s) {
            s.lastSeen = clock.getAsLong();
            if (off > s.upOffset) throw new ApiException(409, "relay_gap", "Missing relay data.");
            int skip = (int) Math.min(data.length, s.upOffset - off);
            if (skip < data.length) {
                byte[] fresh = Arrays.copyOfRange(data, skip, data.length);
                s.upOffset += fresh.length;
                byte[] all = Arrays.copyOf(s.in, s.in.length + fresh.length);
                System.arraycopy(fresh, 0, all, s.in.length, fresh.length);
                s.in = all;
                int used = 0;
                while (true) {
                    int frame = frameLength(s.in, used);
                    if (frame < 0 || used + frame > s.in.length) break;
                    handle(s, Arrays.copyOfRange(s.in, used, used + frame), out);
                    used += frame;
                }
                s.in = Arrays.copyOfRange(s.in, used, s.in.length);
                if (s.in.length > 70_000) throw new ApiException(400, "relay_frame", "Bad relay data.");
            }
            result = s.upOffset;
        }
        for (Delivery d : out) {
            Session to = byPort.get(d.toPort);
            if (to != null) to.deliver(d.fromPort, d.data);
        }
        return result;
    }

    /** TURN over TCP framing: STUN = 20 + length; ChannelData = 4 + length, padded to 4. -1 = need more bytes. */
    static int frameLength(byte[] b, int at) {
        if (b.length - at < 4) return -1;
        int len = ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
        if ((b[at] & 0xC0) == 0x40) return 4 + ((len + 3) & ~3);
        return 20 + len;
    }

    // ------------------------------------------------------------------ TURN
    private void handle(Session s, byte[] m, List<Delivery> out) {
        if ((m[0] & 0xC0) == 0x40) { // ChannelData
            int chan = u16(m, 0), len = u16(m, 2);
            Integer peer = s.chanToPort.get(chan);
            if (peer != null && s.port != 0 && 4 + len <= m.length) out.add(new Delivery(peer, s.port, Arrays.copyOfRange(m, 4, 4 + len)));
            return;
        }
        if (m.length < 20 || readInt(m, 4) != MAGIC) return;
        int type = u16(m, 0);
        Map<Integer, byte[]> a = new HashMap<>();
        int miAt = -1;
        for (int i = 20; i + 4 <= m.length; ) {
            int t = u16(m, i), l = u16(m, i + 2);
            if (i + 4 + l > m.length) break;
            if (t == 0x0008 && miAt < 0) miAt = i;
            a.putIfAbsent(t, Arrays.copyOfRange(m, i + 4, i + 4 + l));
            i += 4 + ((l + 3) & ~3);
        }
        byte[] tid = Arrays.copyOfRange(m, 8, 20);
        switch (type) {
            case 0x0001 -> s.emit(message(0x0101, tid, List.of(attr(0x0020, xorAddress(9))), false));
            case 0x0016 -> { // Send indication
                byte[] peer = a.get(0x0012), data = a.get(0x0013);
                if (peer != null && data != null && s.port != 0) out.add(new Delivery(peerPort(peer), s.port, data));
            }
            case 0x0003, 0x0004, 0x0008, 0x0009 -> {
                if (!authorised(s, m, a, miAt, type, tid)) return;
                request(s, type, tid, a);
            }
            default -> { }
        }
    }

    private boolean authorised(Session s, byte[] m, Map<Integer, byte[]> a, int miAt, int type, byte[] tid) {
        byte[] user = a.get(0x0006), mi = a.get(0x0008), n = a.get(0x0015);
        if (miAt < 0 || user == null || !username.equals(new String(user, StandardCharsets.UTF_8))) {
            s.emit(error(type, tid, 401, "Unauthorized", true));
            return false;
        }
        byte[] signed = Arrays.copyOf(m, miAt);
        int len = miAt - 20 + 24;
        signed[2] = (byte) (len >> 8);
        signed[3] = (byte) len;
        if (!MessageDigest.isEqual(hmac(signed), mi)) {
            s.emit(error(type, tid, 401, "Unauthorized", true));
            return false;
        }
        if (n == null || !Arrays.equals(n, nonce)) {
            s.emit(error(type, tid, 438, "Stale Nonce", true));
            return false;
        }
        return true;
    }

    private void request(Session s, int type, byte[] tid, Map<Integer, byte[]> a) {
        long now = clock.getAsLong();
        switch (type) {
            case 0x0003 -> { // Allocate
                byte[] transport = a.get(0x0019);
                if (transport == null || transport[0] != 17) {
                    s.emit(error(type, tid, 442, "Unsupported Transport Protocol", false));
                    return;
                }
                if (s.port == 0) {
                    s.port = nextPort.getAndUpdate(p -> p >= 65000 ? 20000 : p + 1);
                    byPort.put(s.port, s);
                }
                int life = lifetime(a);
                s.allocExpires = now + life * 1000L;
                s.emit(message(0x0103, tid, List.of(attr(0x0016, xorAddress(s.port)), attr(0x000D, int32(life)),
                        attr(0x0020, xorAddress(9))), true));
            }
            case 0x0004 -> { // Refresh
                int life = lifetime(a);
                if (life == 0 && s.port != 0) {
                    byPort.remove(s.port, s);
                    s.port = 0;
                }
                s.allocExpires = now + life * 1000L;
                s.emit(message(0x0104, tid, List.of(attr(0x000D, int32(life))), true));
            }
            case 0x0008 -> s.emit(message(0x0108, tid, List.of(), true)); // CreatePermission: peers are ours anyway
            case 0x0009 -> { // ChannelBind
                byte[] ch = a.get(0x000C), peer = a.get(0x0012);
                int chan = ch == null ? 0 : u16(ch, 0);
                if (peer == null || chan < 0x4000 || chan > 0x7FFE) {
                    s.emit(error(type, tid, 400, "Bad Request", false));
                    return;
                }
                int port = peerPort(peer);
                Integer old = s.chanToPort.put(chan, port);
                if (old != null) s.portToChan.remove(old);
                s.portToChan.put(port, chan);
                s.emit(message(0x0109, tid, List.of(), true));
            }
            default -> { }
        }
    }

    private static int lifetime(Map<Integer, byte[]> a) {
        byte[] l = a.get(0x000D);
        int v = l == null || l.length < 4 ? 600 : readInt(l, 0);
        return Math.max(0, Math.min(v, 3600));
    }

    private static int peerPort(byte[] xorPeer) {
        return xorPeer.length < 4 ? 0 : (u16(xorPeer, 2) ^ (MAGIC >>> 16));
    }

    // ------------------------------------------------------------------ encoding
    byte[] message(int type, byte[] tid, List<byte[]> attrs, boolean sign) {
        int len = 0;
        for (byte[] x : attrs) len += x.length;
        byte[] m = new byte[20 + len + (sign ? 24 : 0)];
        m[0] = (byte) (type >> 8);
        m[1] = (byte) type;
        m[2] = (byte) ((len + (sign ? 24 : 0)) >> 8);
        m[3] = (byte) (len + (sign ? 24 : 0));
        putInt(m, 4, MAGIC);
        System.arraycopy(tid, 0, m, 8, 12);
        int at = 20;
        for (byte[] x : attrs) {
            System.arraycopy(x, 0, m, at, x.length);
            at += x.length;
        }
        if (sign) {
            byte[] mac = hmac(Arrays.copyOf(m, at));
            m[at] = 0;
            m[at + 1] = 0x08;
            m[at + 2] = 0;
            m[at + 3] = 20;
            System.arraycopy(mac, 0, m, at + 4, 20);
        }
        return m;
    }

    private byte[] error(int type, byte[] tid, int code, String reason, boolean challenge) {
        byte[] r = reason.getBytes(StandardCharsets.UTF_8);
        byte[] v = new byte[4 + r.length];
        v[2] = (byte) (code / 100);
        v[3] = (byte) (code % 100);
        System.arraycopy(r, 0, v, 4, r.length);
        List<byte[]> attrs = new ArrayList<>(List.of(attr(0x0009, v)));
        if (challenge) {
            attrs.add(attr(0x0014, REALM.getBytes(StandardCharsets.US_ASCII)));
            attrs.add(attr(0x0015, nonce));
        }
        return message(type | 0x0110, tid, attrs, false);
    }

    static byte[] attr(int type, byte[] v) {
        byte[] a = new byte[4 + ((v.length + 3) & ~3)];
        a[0] = (byte) (type >> 8);
        a[1] = (byte) type;
        a[2] = (byte) (v.length >> 8);
        a[3] = (byte) v.length;
        System.arraycopy(v, 0, a, 4, v.length);
        return a;
    }

    static byte[] xorAddress(int port) {
        byte[] v = new byte[8];
        v[1] = 0x01;
        int p = port ^ (MAGIC >>> 16);
        v[2] = (byte) (p >> 8);
        v[3] = (byte) p;
        for (int i = 0; i < 4; i++) v[4 + i] = (byte) (RELAY_IP[i] ^ (MAGIC >>> (24 - 8 * i)));
        return v;
    }

    private static byte[] int32(int v) {
        byte[] b = new byte[4];
        putInt(b, 0, v);
        return b;
    }

    static int u16(byte[] b, int at) {
        return ((b[at] & 0xff) << 8) | (b[at + 1] & 0xff);
    }

    static int readInt(byte[] b, int at) {
        return (u16(b, at) << 16) | u16(b, at + 2);
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] md5(String s) {
        try {
            return MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
