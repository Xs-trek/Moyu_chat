package com.moyu.chat;

import static org.junit.Assert.*;

import org.junit.Test;

public class ChatTurnOptionsTest {
    @Test public void normalizesWebModes() {
        assertEquals(ChatTurnOptions.WEB_OFF, ChatTurnOptions.normalizeWebMode(" OFF "));
        assertEquals(ChatTurnOptions.WEB_AUTO, ChatTurnOptions.normalizeWebMode("unknown"));
        assertEquals(ChatTurnOptions.WEB_ON, ChatTurnOptions.normalizeWebMode("on"));
    }

    @Test public void capabilitiesAreProtocolBound() {
        assertEquals("web_search", ChatTurnOptions.effectiveCapabilities(
                OpenAiCompatibleTransport.RESPONSES, "web_search,web_fetch"));
        assertEquals("", ChatTurnOptions.effectiveCapabilities(
                OpenAiCompatibleTransport.CHAT_COMPLETIONS, "web_search,web_fetch"));
        assertEquals("web_search,web_fetch", ChatTurnOptions.effectiveCapabilities(
                AnthropicMessagesTransport.PROTOCOL, "web_search,web_fetch"));
    }

    @Test public void sessionSnapshotsDriveTurnOptions() {
        ChatDatabase.Session session = new ChatDatabase.Session();
        session.toolCapabilities = "web_fetch,web_search";
        session.webMode = ChatTurnOptions.WEB_ON;

        ChatTurnOptions responses = ChatTurnOptions.forSession(session, OpenAiCompatibleTransport.RESPONSES);
        assertTrue(responses.webEnabled());
        assertTrue(responses.forceFallback());
        assertTrue(responses.supports(ChatTurnOptions.WEB_SEARCH));
        assertFalse(responses.supports(ChatTurnOptions.WEB_FETCH));

        ChatTurnOptions chat = ChatTurnOptions.forSession(session, OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        assertEquals("", chat.toolCapabilities);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownNativeCapabilities() {
        ChatTurnOptions.normalizeCapabilities("web_search,shell");
    }
}
