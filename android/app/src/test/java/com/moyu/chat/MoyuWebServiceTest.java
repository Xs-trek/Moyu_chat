package com.moyu.chat;

import static org.junit.Assert.*;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.Test;

public class MoyuWebServiceTest {
    @Test public void parsesJsonRpcAndSseToolText() {
        String payload = new JSONObject().put("jsonrpc", "2.0").put("id", 1)
                .put("result", new JSONObject().put("content", new org.json.JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", "alpha"))
                        .put(new JSONObject().put("type", "text").put("text", "beta"))))
                .toString();
        assertEquals("alpha\nbeta", MoyuWebService.parseMcpText(payload));
        assertEquals("alpha\nbeta", MoyuWebService.parseMcpText("event: message\ndata: " + payload + "\n\n"));
    }

    @Test public void blocksPrivateAndSpecialAddresses() throws Exception {
        assertFalse(MoyuWebService.isPublicAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(MoyuWebService.isPublicAddress(InetAddress.getByName("10.1.2.3")));
        assertFalse(MoyuWebService.isPublicAddress(InetAddress.getByName("100.64.1.2")));
        assertFalse(MoyuWebService.isPublicAddress(InetAddress.getByName("198.18.0.1")));
        assertFalse(MoyuWebService.isPublicAddress(InetAddress.getByName("fc00::1")));
        assertTrue(MoyuWebService.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(MoyuWebService.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test public void offNeverCallsFallbackAndOnForcesSearch() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(200)
                    .addHeader("Content-Type", "application/json")
                    .body(exaResponse("Title: Example\nURL: https://example.com/\nUseful context")).build());
            try (MoyuWebService web = new MoyuWebService(server.url("/mcp").toString())) {
                MoyuWebService.Prepared off = web.prepare("latest release", new ChatTurnOptions("", "off"), null);
                assertFalse(off.attempted);
                assertEquals(0, server.getRequestCount());

                List<String> stages = new ArrayList<>();
                MoyuWebService.Prepared on = web.prepare("hello", new ChatTurnOptions("", "on"), stages::add);
                assertTrue(on.attempted);
                assertTrue(on.hasContext());
                assertEquals("Exa", on.backend);
                assertEquals(1, on.sources.length());
                assertEquals("web_search", stages.get(0));
                RecordedRequest request = server.takeRequest();
                String body = request.getBody().readUtf8();
                assertTrue(body.contains("web_search_exa"));
                assertTrue(body.contains("\"numResults\":6"));
                assertTrue(body.contains("\"livecrawl\":\"fallback\""));
            }
        }
    }

    @Test public void autoSearchesFreshnessRequestsButNotOrdinaryText() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder().code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body("data: " + exaResponse("Title: Current\nURL: https://example.com/current\nFresh") + "\n\n").build());
            try (MoyuWebService web = new MoyuWebService(server.url("/mcp").toString())) {
                MoyuWebService.Prepared ordinary = web.prepare("explain pointers", new ChatTurnOptions("", "auto"), null);
                assertFalse(ordinary.attempted);
                assertEquals(0, server.getRequestCount());

                MoyuWebService.Prepared fresh = web.prepare("检索一下最新版本", new ChatTurnOptions("", "auto"), null);
                assertTrue(fresh.attempted);
                assertTrue(fresh.hasContext());
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    @Test public void privateHttpsFetchIsRejectedWithoutNetworkAccess() {
        try (MoyuWebService web = new MoyuWebService("http://127.0.0.1:1/mcp")) {
            MoyuWebService.Prepared prepared = web.prepare(
                    "read https://127.0.0.1/private", new ChatTurnOptions("web_search", "auto"), null);
            assertTrue(prepared.attempted);
            assertFalse(prepared.hasContext() && prepared.sources.length() > 0);
            assertEquals("unavailable", prepared.backend);
            assertTrue(prepared.promptSuffix.contains("Fetch failed"));
        }
    }

    private static String exaResponse(String text) {
        return new JSONObject().put("jsonrpc", "2.0").put("id", 1)
                .put("result", new JSONObject().put("content", new org.json.JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", text))))
                .toString();
    }
}
