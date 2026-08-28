package com.moyu.chat;

import static org.junit.Assert.*;

import org.junit.Test;

/** Conservative deployment-overlay matching: optional capabilities require an exact reviewed endpoint. */
public class ModelCatalogTest {
    private static ChatDatabase.Provider provider(String baseUrl) {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "p";
        p.displayName = "P";
        p.baseUrl = baseUrl;
        return p;
    }

    @Test public void unknownConfigModeFallsBackToManual() {
        assertEquals(ModelCatalog.CONFIG_MANUAL, ModelCatalog.normalizeConfigMode("garbage"));
        assertEquals(ModelCatalog.CONFIG_MANUAL, ModelCatalog.normalizeConfigMode(null));
    }

    @Test public void officialOpenAiOverlayRequiresExactV1Root() {
        ModelCatalog.Resolution exact = ModelCatalog.resolve(provider("https://api.openai.com/v1"), "gpt-5.6");
        assertEquals("moyu-official-openai-overlay", exact.source);
        assertTrue(exact.deploymentResolved);
        assertEquals(OpenAiCompatibleTransport.RESPONSES, exact.protocol);
        assertEquals(ClientToolRuntime.OPENAI_RESPONSES, exact.clientToolCalling);

        ModelCatalog.Resolution missingV1 = ModelCatalog.resolve(provider("https://api.openai.com"), "gpt-5.6");
        assertEquals("moyu-base-catalog", missingV1.source);
        assertFalse("same model name on an unreviewed endpoint is not an Auto deployment", missingV1.deploymentResolved);
        assertEquals(ClientToolRuntime.NONE, missingV1.clientToolCalling);
    }

    @Test public void officialAnthropicOverlayRequiresExactHostAndV1Root() {
        ModelCatalog.Resolution exact = ModelCatalog.resolve(provider("https://api.anthropic.com/v1"), "claude-opus-5");
        assertEquals("moyu-official-anthropic-overlay", exact.source);
        assertTrue(exact.deploymentResolved);
        assertEquals(AnthropicMessagesTransport.PROTOCOL, exact.protocol);
        assertEquals(ClientToolRuntime.ANTHROPIC_TOOLS, exact.clientToolCalling);

        ModelCatalog.Resolution lookalike = ModelCatalog.resolve(provider("https://api.anthropic.com.evil.example/v1"), "claude-opus-5");
        assertEquals("moyu-base-catalog", lookalike.source);
        assertEquals(OpenAiCompatibleTransport.CHAT_COMPLETIONS, lookalike.protocol);
        assertEquals(ClientToolRuntime.NONE, lookalike.clientToolCalling);
    }

    @Test public void openCodeGoOverlayRequiresExactReviewedRoot() {
        ModelCatalog.Resolution exact = ModelCatalog.resolve(provider("https://opencode.ai/zen/go/v1"), "glm-5.3-flash");
        assertEquals("moyu-opencode-go-overlay", exact.source);
        assertEquals(ReasoningPolicy.WIRE_OPENAI_CHAT_EFFORT, exact.reasoningWire);
        assertEquals("low,high,max", exact.reasoningLevels);

        ModelCatalog.Resolution extended = ModelCatalog.resolve(provider("https://opencode.ai/zen/go/v1/extra"), "glm-5.3-flash");
        assertEquals("moyu-base-catalog", extended.source);
        assertEquals(ReasoningPolicy.WIRE_NONE, extended.reasoningWire);
        assertEquals("", extended.reasoningLevels);
    }
}
