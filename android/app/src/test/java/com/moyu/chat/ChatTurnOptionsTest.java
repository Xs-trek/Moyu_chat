package com.moyu.chat;

import static org.junit.Assert.*;

import org.junit.Test;

public class ChatTurnOptionsTest {
    @Test public void normalizesWebModes() {
        assertEquals(ChatTurnOptions.WEB_OFF, ChatTurnOptions.normalizeWebMode(" OFF "));
        assertEquals(ChatTurnOptions.WEB_AUTO, ChatTurnOptions.normalizeWebMode("unknown"));
        assertEquals(ChatTurnOptions.WEB_ON, ChatTurnOptions.normalizeWebMode("on"));
    }

    @Test public void capabilitiesAreProtocolBoundAndMessagesRequireExplicitWireVersion() {
        assertEquals("web_search", ChatTurnOptions.effectiveCapabilities(
                OpenAiCompatibleTransport.RESPONSES, "web_search,web_fetch"));
        assertEquals("", ChatTurnOptions.effectiveCapabilities(
                OpenAiCompatibleTransport.CHAT_COMPLETIONS, "web_search,web_fetch"));
        assertEquals("", ChatTurnOptions.effectiveCapabilities(
                AnthropicMessagesTransport.PROTOCOL, "web_search,web_fetch"));
        assertEquals("web_search_20260209,web_fetch_20250910", ChatTurnOptions.effectiveCapabilities(
                AnthropicMessagesTransport.PROTOCOL, "web_search_20260209,web_fetch_20250910"));
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

        ChatTurnOptions genericMessages = ChatTurnOptions.forSession(session, AnthropicMessagesTransport.PROTOCOL);
        assertEquals("generic Messages capability must fall back instead of guessing a tool schema", "", genericMessages.toolCapabilities);
        assertFalse(genericMessages.supports(ChatTurnOptions.WEB_SEARCH));
        assertEquals("", new ChatTurnOptions("web_search", ChatTurnOptions.WEB_ON).wireType(ChatTurnOptions.WEB_SEARCH));

        session.toolCapabilities = "web_search_20260209,web_fetch_20250910";
        ChatTurnOptions explicitMessages = ChatTurnOptions.forSession(session, AnthropicMessagesTransport.PROTOCOL);
        assertTrue(explicitMessages.supports(ChatTurnOptions.WEB_SEARCH));
        assertTrue(explicitMessages.supports(ChatTurnOptions.WEB_FETCH));
        assertEquals("web_search_20260209", explicitMessages.wireType(ChatTurnOptions.WEB_SEARCH));
        assertEquals("web_fetch_20250910", explicitMessages.wireType(ChatTurnOptions.WEB_FETCH));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownNativeCapabilities() {
        ChatTurnOptions.normalizeCapabilities("web_search,shell");
    }
}
