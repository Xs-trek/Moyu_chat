package com.moyu.chat;

import static org.junit.Assert.*;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.Test;

public class MoyuWebServiceTest {
    @Test public void parsesJsonRpcAndSseToolText() throws Exception {
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
            installExaMcp(server, "Title: Example\nURL: https://example.com/\nUseful context");
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
                assertEquals(3, server.getRequestCount());
                RecordedRequest request = takeToolCall(server);
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
            installExaMcp(server, "Title: Current\nURL: https://example.com/current\nFresh");
            try (MoyuWebService web = new MoyuWebService(server.url("/mcp").toString())) {
                MoyuWebService.Prepared ordinary = web.prepare("explain pointers", new ChatTurnOptions("", "auto"), null);
                assertFalse(ordinary.attempted);
                assertEquals(0, server.getRequestCount());

                MoyuWebService.Prepared fresh = web.prepare("检索一下最新版本", new ChatTurnOptions("", "auto"), null);
                assertTrue(fresh.attempted);
                assertTrue(fresh.hasContext());
                assertEquals(3, server.getRequestCount());
            }
        }
    }

    @Test public void autoContinuesImmediatelyPreviousWebSearchButNotUnrelatedTurns() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            installExaMcp(server, "Title: More\nURL: https://example.com/more\nMore context");
            MoyuWebService.FollowUpContext previous = new MoyuWebService.FollowUpContext(
                    "检索一下 GPT 最新变化",
                    new org.json.JSONArray().put(new JSONObject().put("url", "https://example.com/first")), true);
            try (MoyuWebService web = new MoyuWebService(server.url("/mcp").toString())) {
                MoyuWebService.Prepared continued = web.prepare("继续看看更多讨论",
                        new ChatTurnOptions("", "auto"), null, null, previous);
                assertTrue(continued.attempted);
                assertTrue(continued.hasContext());
                assertEquals(3, server.getRequestCount());
                RecordedRequest request = takeToolCall(server);
                String body = request.getBody().readUtf8();
                assertTrue(body.contains("GPT 最新变化"));
                assertTrue(body.contains("继续看看更多讨论"));

                MoyuWebService.Prepared unrelated = web.prepare("解释一下指针",
                        new ChatTurnOptions("", "auto"), null, null, previous);
                assertFalse("an unrelated next turn must not revive older Web state", unrelated.attempted);
                assertEquals(3, server.getRequestCount());
            }
        }
    }

    @Test public void followUpFetchReusesPreviousSourceUrl() throws Exception {
        try (MoyuWebService web = new MoyuWebService("http://127.0.0.1:1/mcp")) {
            MoyuWebService.FollowUpContext previous = new MoyuWebService.FollowUpContext(
                    "查一下这个问题", new org.json.JSONArray().put(new JSONObject()
                    .put("url", "https://127.0.0.1/private").put("title", "Previous source")), true);
            MoyuWebService.Prepared prepared = web.prepare("打开刚才那个网页",
                    new ChatTurnOptions("", "auto"), null, null, previous);
            assertTrue("follow-up should attempt the inherited source instead of claiming no Web ability", prepared.attempted);
            assertEquals("unavailable", prepared.backend);
            assertTrue(prepared.promptSuffix.contains("Fetch failed for https://127.0.0.1/private"));
        }
    }

    @Test public void followUpWithoutPreviousSourceFallsBackToSearch() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
                    .setBody(exaResponse("Title: Recovered\nURL: https://example.com/recovered\nRecovered context")));
            MoyuWebService.FollowUpContext previous = new MoyuWebService.FollowUpContext(
                    "检索一个主题", new org.json.JSONArray(), true);
            try (MoyuWebService web = new MoyuWebService(server.url("/mcp").toString())) {
                MoyuWebService.Prepared prepared = web.prepare("打开刚才网页",
                        new ChatTurnOptions("", "auto"), null, null, previous);
                assertTrue(prepared.attempted);
                assertEquals("Exa", prepared.backend);
                assertEquals(1, server.getRequestCount());
                String body = server.takeRequest().getBody().readUtf8();
                assertTrue(body.contains("检索一个主题"));
                assertTrue(body.contains("打开刚才网页"));
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

    private static void installExaMcp(MockWebServer server, String toolText) {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                try {
                    String body = request.getBody().clone().readUtf8();
                    if (body.contains("\"method\":\"initialize\"")) {
                        return new MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json")
                                .addHeader("Mcp-Session-Id", "test-session")
                                .setBody(new JSONObject().put("jsonrpc", "2.0").put("id", 1)
                                        .put("result", new JSONObject().put("protocolVersion", "2025-06-18")
                                                .put("capabilities", new JSONObject())
                                                .put("serverInfo", new JSONObject().put("name", "exa-test").put("version", "1"))).toString());
                    }
                    if (body.contains("notifications/initialized")) return new MockResponse().setResponseCode(202);
                    if (body.contains("\"method\":\"tools/call\"")) {
                        return new MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json")
                                .setBody(exaResponse(toolText));
                    }
                    return new MockResponse().setResponseCode(400);
                } catch (Exception error) {
                    return new MockResponse().setResponseCode(500).setBody(error.toString());
                }
            }
        });
    }

    private static RecordedRequest takeToolCall(MockWebServer server) throws Exception {
        for (int i = 0; i < 3; i++) {
            RecordedRequest request = server.takeRequest();
            if (request.getBody().clone().readUtf8().contains("\"method\":\"tools/call\"")) return request;
        }
        fail("MCP tools/call request not observed");
        return null;
    }

    private static String exaResponse(String text) throws Exception {
        return new JSONObject().put("jsonrpc", "2.0").put("id", 1)
                .put("result", new JSONObject().put("content", new org.json.JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", text))))
                .toString();
    }
}
