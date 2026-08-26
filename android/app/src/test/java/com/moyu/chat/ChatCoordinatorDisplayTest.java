package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ChatCoordinatorDisplayTest {
    @Test public void inheritedUiProjectionIsBoundedAndKeepsWholeTurns() throws Exception {
        JSONArray inherited = new JSONArray();
        for (int i = 1; i <= 600; i++) {
            inherited.put(new JSONObject().put("kind", "message").put("role", "user").put("text", "U" + i));
            inherited.put(new JSONObject().put("kind", "message").put("role", "assistant").put("text", "A" + i));
        }

        ChatCoordinator.DisplayTimeline display = ChatCoordinator.boundedInheritedDisplay(inherited);

        assertTrue("WebView must not receive the full imported history", display.items.length() <= 500);
        assertEquals(700, display.omittedInheritedItems);
        assertEquals("user", display.items.getJSONObject(0).optString("role"));
        assertEquals("U351", display.items.getJSONObject(0).optString("text"));
        assertEquals("assistant", display.items.getJSONObject(display.items.length() - 1).optString("role"));
        assertEquals("A600", display.items.getJSONObject(display.items.length() - 1).optString("text"));
        assertTrue(display.items.getJSONObject(0).optBoolean("inherited"));
    }

    @Test public void oversizedSingleInheritedTurnIsOmittedFromUiButReported() throws Exception {
        JSONArray inherited = new JSONArray()
                .put(new JSONObject().put("kind", "message").put("role", "user").put("text", repeat('x', 520_000)))
                .put(new JSONObject().put("kind", "message").put("role", "assistant").put("text", "answer"));
        ChatCoordinator.DisplayTimeline display = ChatCoordinator.boundedInheritedDisplay(inherited);
        assertEquals(0, display.items.length());
        assertEquals(2, display.omittedInheritedItems);
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }
}
