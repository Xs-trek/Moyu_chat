package com.moyu.chat;

import static org.junit.Assert.*;

import org.junit.Test;

/** Cloud endpoints HTTPS-only; loopback HTTP allowed for local model servers; no credentials/query/fragment. */
public class ProviderEndpointTest {
    @Test
    public void acceptsHttpsAndTrimsTrailingSlashes() {
        assertEquals("https://api.example.com/v1", ProviderEndpoint.normalize("https://api.example.com/v1/"));
        assertEquals("https://api.example.com", ProviderEndpoint.normalize("  https://api.example.com  "));
    }

    @Test
    public void acceptsLoopbackHttpOnly() {
        assertEquals("http://127.0.0.1:8080/v1", ProviderEndpoint.normalize("http://127.0.0.1:8080/v1"));
        assertEquals("http://localhost:1234", ProviderEndpoint.normalize("http://localhost:1234/"));
    }

    @Test
    public void rejectsNonLoopbackHttp() {
        assertRejected("http://api.example.com/v1");
        assertRejected("http://10.0.0.2/v1");
        assertRejected("ftp://example.com");
    }

    @Test
    public void rejectsCredentialsQueryFragmentAndGarbage() {
        assertRejected("https://user:pass@example.com");
        assertRejected("https://example.com/v1?x=1");
        assertRejected("https://example.com/v1#frag");
        assertRejected("");
        assertRejected(null);
        assertRejected("not a url");
    }

    private static void assertRejected(String raw) {
        try {
            ProviderEndpoint.normalize(raw);
            fail("expected rejection: " + raw);
        } catch (IllegalArgumentException expected) { }
    }
}
