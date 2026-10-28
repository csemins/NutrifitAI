package com.transformsai.nutrifitai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.transformsai.nutrifitai.data.remote.AiHttpException;

import org.junit.Test;

import java.lang.reflect.Method;

/** Error mapping for the proxy (package-private static method exercised via reflection). */
public class AiApiClientErrorTest {
    private static AiHttpException map(int code, String body, String retryAfter) throws Exception {
        Method m = Class.forName("com.transformsai.nutrifitai.data.remote.AiApiClient")
                .getDeclaredMethod("mapError", int.class, String.class, String.class);
        m.setAccessible(true);
        return (AiHttpException) m.invoke(null, code, body, retryAfter);
    }

    @Test
    public void mapsStatusCodes() throws Exception {
        assertEquals("auth", map(401, "", null).code);
        assertFalse(map(401, "", null).retryable);
        AiHttpException rl = map(429, "{\"error\":{\"message\":\"slow down\"}}", "30");
        assertEquals("rate_limited", rl.code);
        assertTrue(rl.retryable);
        assertTrue(rl.getMessage().contains("30"));
        assertEquals("upstream", map(502, "{\"error\":{\"message\":\"bad upstream\"}}", null).code);
        assertEquals("bad upstream", map(502, "{\"error\":{\"message\":\"bad upstream\"}}", null).getMessage());
        assertEquals("timeout", map(504, "", null).code);
        assertEquals("too_large", map(413, "", null).code);
    }
}
