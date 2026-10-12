package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The inspector records protocol, never credentials or bulk binary payloads. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WireCaptureTest {
    private static final String KEY = "sk-wire-capture-secret-123";

    private static String repeat(char c, int n) { StringBuilder s = new StringBuilder(n); for (int i = 0; i < n; i++) s.append(c); return s.toString(); }

    @Test public void requestKeepsProtocolButDropsImagesOpaqueBlobsAndTheKey() throws Exception {
        String image = "data:image/png;base64," + repeat('A', 5000);
        JSONObject body = new JSONObject().put("model", "m")
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", "describe " + KEY))
                        .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", image))))))
                .put("encrypted", repeat('Z', 3000))
                .put("source", new JSONObject().put("type", "base64").put("data", repeat('Q', 4096)));
        String recorded = WireCapture.request("https://api.example/v1/chat/completions", body, KEY);

        assertFalse("the key never appears", recorded.contains(KEY));
        assertTrue(recorded.contains("[redacted-key]"));
        JSONObject parsed = new JSONObject(recorded);
        assertEquals("https://api.example/v1/chat/completions", parsed.getString("url"));
        assertEquals("m", parsed.getJSONObject("body").getString("model"));
        JSONObject sent = parsed.getJSONObject("body");
        assertEquals("data:image/png;base64,<5000 base64 chars omitted>",
                sent.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url"));
        assertEquals("<3000 opaque chars omitted>", sent.getString("encrypted"));
        assertEquals("<4096 opaque chars omitted>", sent.getJSONObject("source").getString("data"));
        assertFalse(recorded.contains(repeat('A', 100)));
        assertEquals("the caller's body is not modified", 5000 + "data:image/png;base64,".length(),
                body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url").length());
    }

    @Test public void oversizedRequestIsTruncatedWithAMarker() throws Exception {
        StringBuilder prose = new StringBuilder();
        while (prose.length() < WireCapture.MAX_CHARS + 10_000) prose.append("long readable prompt text, not base64. ");
        JSONObject body = new JSONObject().put("model", "m").put("input", prose.toString());
        JSONObject parsed = new JSONObject(WireCapture.request("https://api.example/v1/responses", body, KEY));
        assertTrue(parsed.getBoolean("truncated"));
        assertEquals(WireCapture.MAX_CHARS, parsed.getString("bodyText").length());
    }

    @Test public void responseKeepsStatusAndLinesBoundedAndScrubbed() throws Exception {
        WireCapture.Response response = new WireCapture.Response();
        response.status(200);
        response.line("event: message");
        response.line("data: {\"delta\":\"echo " + KEY + "\"}");
        response.line("data: {\"image\":\"" + repeat('B', 4000) + "\"}");
        JSONObject parsed = new JSONObject(response.finish(KEY));
        assertEquals(200, parsed.getInt("status"));
        assertFalse(parsed.getBoolean("truncated"));
        String text = parsed.getString("text");
        assertTrue(text.startsWith("event: message\n"));
        assertFalse(text.contains(KEY));
        assertTrue(text.contains("<4000 opaque chars omitted>"));

        WireCapture.Response big = new WireCapture.Response();
        for (int i = 0; i < 40_000; i++) big.line("data: {\"delta\":\"chunk " + i + "\"}");
        JSONObject bounded = new JSONObject(big.finish(KEY));
        assertTrue(bounded.getBoolean("truncated"));
        assertTrue(bounded.getString("text").length() <= WireCapture.MAX_CHARS);
    }
}
