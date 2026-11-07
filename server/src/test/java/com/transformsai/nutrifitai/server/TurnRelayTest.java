package com.transformsai.nutrifitai.server;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TurnRelayTest {
    final TurnRelay relay = new TurnRelay(System::currentTimeMillis);
    final byte[] tid = new byte[12];

    /** A simulated phone: everything it sends and the stream offsets it has read. */
    final class Phone {
        final TurnRelay.Session s;
        long up, down;

        Phone(String uid) throws ApiException {
            s = relay.open(uid);
        }

        void send(byte[] frame) throws ApiException {
            up = relay.up(s, up, frame);
        }

        /** Next whole frame from the relay. */
        byte[] next() throws Exception {
            byte[] b = s.read(down, 1000);
            assertNotNull(b);
            int len = TurnRelay.frameLength(b, 0);
            assertTrue(len > 0 && len <= b.length, "a whole frame arrived");
            down += len;
            return Arrays.copyOf(b, len);
        }
    }

    static int type(byte[] m) {
        return TurnRelay.u16(m, 0);
    }

    byte[] allocate(Phone p) throws Exception {
        byte[] transport = TurnRelay.attr(0x0019, new byte[]{17, 0, 0, 0});
        p.send(relay.message(0x0003, tid, List.of(transport), false));
        byte[] challenge = p.next();
        assertEquals(0x0113, type(challenge), "first allocate is challenged (401)");
        byte[] nonce = attr(challenge, 0x0015);
        List<byte[]> a = new ArrayList<>(List.of(transport, TurnRelay.attr(0x0006, relay.username.getBytes(StandardCharsets.UTF_8)),
                TurnRelay.attr(0x0014, TurnRelay.REALM.getBytes(StandardCharsets.UTF_8)), TurnRelay.attr(0x0015, nonce)));
        p.send(relay.message(0x0003, tid, a, true)); // signed with the shared long-term key
        byte[] ok = p.next();
        assertEquals(0x0103, type(ok), "allocate succeeds with the right credentials");
        return attr(ok, 0x0016);
    }

    static byte[] attr(byte[] m, int t) {
        for (int i = 20; i + 4 <= m.length; ) {
            int at = TurnRelay.u16(m, i), l = TurnRelay.u16(m, i + 2);
            if (at == t) return Arrays.copyOfRange(m, i + 4, i + 4 + l);
            i += 4 + ((l + 3) & ~3);
        }
        return null;
    }

    @Test
    void twoPhonesExchangeDataThroughTheRelay() throws Exception {
        Phone a = new Phone("alice"), b = new Phone("bob");
        byte[] relayA = allocate(a), relayB = allocate(b);
        assertFalse(Arrays.equals(relayA, relayB));
        // A sends to B's relayed address (Send indication) -> B gets a Data indication from A's relayed address
        byte[] payload = "hello-bob".getBytes(StandardCharsets.UTF_8);
        a.send(relay.message(0x0016, tid, List.of(TurnRelay.attr(0x0012, relayB), TurnRelay.attr(0x0013, payload)), false));
        byte[] data = b.next();
        assertEquals(0x0017, type(data));
        assertArrayEquals(payload, attr(data, 0x0013));
        assertArrayEquals(relayA, attr(data, 0x0012));
        // B binds a channel to A; A's next packet reaches B as ChannelData
        byte[] chan = {0x40, 0x01, 0, 0};
        List<byte[]> bind = new ArrayList<>(List.of(TurnRelay.attr(0x000C, chan), TurnRelay.attr(0x0012, relayA),
                TurnRelay.attr(0x0006, relay.username.getBytes(StandardCharsets.UTF_8)),
                TurnRelay.attr(0x0014, TurnRelay.REALM.getBytes(StandardCharsets.UTF_8)), TurnRelay.attr(0x0015, nonceOf(b))));
        b.send(relay.message(0x0009, tid, bind, true));
        assertEquals(0x0109, type(b.next()));
        a.send(relay.message(0x0016, tid, List.of(TurnRelay.attr(0x0012, relayB), TurnRelay.attr(0x0013, payload)), false));
        byte[] cd = b.next();
        assertEquals(0x4001, TurnRelay.u16(cd, 0));
        assertArrayEquals(payload, Arrays.copyOfRange(cd, 4, 4 + payload.length));
    }

    private byte[] lastNonce;

    private byte[] nonceOf(Phone p) throws Exception {
        if (lastNonce != null) return lastNonce;
        p.send(relay.message(0x0008, tid, List.of(), false)); // unauthenticated request -> challenge with the nonce
        lastNonce = attr(p.next(), 0x0015);
        return lastNonce;
    }

    @Test
    void wrongPasswordIsRejectedAndOutsideAddressesAreDropped() throws Exception {
        Phone a = new Phone("alice");
        TurnRelay other = new TurnRelay(System::currentTimeMillis); // different password
        byte[] transport = TurnRelay.attr(0x0019, new byte[]{17, 0, 0, 0});
        a.send(relay.message(0x0003, tid, List.of(transport), false));
        byte[] nonce = attr(a.next(), 0x0015);
        a.send(other.message(0x0003, tid, List.of(transport, TurnRelay.attr(0x0006, "nutrifit".getBytes(StandardCharsets.UTF_8)),
                TurnRelay.attr(0x0014, TurnRelay.REALM.getBytes(StandardCharsets.UTF_8)), TurnRelay.attr(0x0015, nonce)), true));
        assertEquals(0x0113, type(a.next()), "bad credentials -> 401");
        // a Send to an address that is not one of our allocations goes nowhere
        a.send(relay.message(0x0016, tid, List.of(TurnRelay.attr(0x0012, TurnRelay.xorAddress(9999)),
                TurnRelay.attr(0x0013, new byte[]{1, 2, 3})), false));
        assertEquals(0, a.s.read(a.down, 200).length);
    }

    @Test
    void retriedUploadsAreNotAppliedTwiceAndSplitFramesReassemble() throws Exception {
        Phone a = new Phone("alice");
        byte[] m = relay.message(0x0001, tid, List.of(), false); // Binding request
        long after = relay.up(a.s, 0, Arrays.copyOf(m, 7));      // first 7 bytes
        assertEquals(7, after);
        assertEquals(0, a.s.read(0, 100).length, "half a frame: nothing yet");
        assertEquals(m.length, relay.up(a.s, 0, m));            // retry from 0 with the whole frame
        assertEquals(0x0101, type(a.next()));
        assertEquals(m.length, relay.up(a.s, 0, m));            // duplicate: ignored
        assertEquals(0, a.s.read(a.down, 100).length);
        assertThrows(ApiException.class, () -> relay.up(a.s, m.length + 5, m)); // gap
    }
}
