package com.transformsai.nutrifitai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.transformsai.nutrifitai.call.IceServers;

import org.junit.Test;

import java.util.List;

public class IceServersTest {
    @Test
    public void readsRelayListFromServer() {
        List<IceServers.Entry> e = IceServers.entries("{\"ttl\":3600,\"iceServers\":["
                + "{\"urls\":[\"stun:stun.cloudflare.com:3478\"]},"
                + "{\"urls\":[\"turn:turn.cloudflare.com:3478?transport=udp\",\"turns:turn.cloudflare.com:443?transport=tcp\"],"
                + "\"username\":\"u\",\"credential\":\"c\"}]}");
        assertEquals(2, e.size());
        assertEquals(2, e.get(1).urls.size());
        assertEquals("u", e.get(1).username);
        assertEquals("", e.get(0).username);
    }

    @Test
    public void dropsUnsafeOrMalformedEntries() {
        assertTrue(IceServers.entries(null).isEmpty());
        assertTrue(IceServers.entries("").isEmpty());
        assertTrue(IceServers.entries("not json").isEmpty());
        assertTrue(IceServers.entries("{\"iceServers\":{}}").isEmpty());
        List<IceServers.Entry> e = IceServers.entries("{\"iceServers\":[{\"urls\":[\"http://x\",\"javascript:y\"]},"
                + "{\"urls\":\"turn:ok:3478\",\"username\":5},7]}");
        assertEquals(1, e.size());
        assertEquals("turn:ok:3478", e.get(0).urls.get(0));
    }

    @Test
    public void emptyRelayListMeansDirectOnly() {
        assertTrue(IceServers.entries("{\"iceServers\":[],\"ttl\":21600}").isEmpty());
    }
}
