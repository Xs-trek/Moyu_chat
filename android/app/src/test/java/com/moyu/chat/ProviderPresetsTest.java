package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;

/**
 * The reviewed preset/catalog data and the layered resolution built on it. Catalog claims only apply
 * to the exact preset route they were reviewed for; everything else stays on conservative defaults.
 */
public class ProviderPresetsTest {
    private static ChatDatabase.Route route(String presetId, String presetRouteId) {
        ProviderPresets.RouteSpec spec = ProviderPresets.get().preset(presetId).route(presetRouteId);
        ChatDatabase.Route r = new ChatDatabase.Route();
        r.id = presetId + "/" + presetRouteId; r.providerId = "p"; r.label = spec.label;
        r.presetId = presetId; r.presetRouteId = presetRouteId; r.protocol = spec.protocol;
        r.baseUrl = spec.baseUrl; r.authMode = spec.authMode; r.dialect = spec.dialect.toString();
        return r;
    }

    private static ChatDatabase.ModelProfile model(ChatDatabase.Route route, String modelId, String overrides) {
        ChatDatabase.ModelProfile m = new ChatDatabase.ModelProfile();
        m.id = "m"; m.providerId = "p"; m.routeId = route.id; m.modelId = modelId; m.overrides = overrides;
        DeploymentResolver.apply(route, m);
        ModelProfileValidator.validateResolvedProfile(route, m);
        return m;
    }

    @Test public void everyPresetRouteIsAValidHttpsEndpointWithASupportedProtocol() {
        ProviderPresets presets = ProviderPresets.get();
        assertFalse(presets.version.isEmpty());
        ChatTransportRegistry transports = new ChatTransportRegistry();
        HashSet<String> ids = new HashSet<>();
        for (ProviderPresets.Preset preset : presets.presets) {
            assertTrue("duplicate preset " + preset.id, ids.add(preset.id));
            assertFalse(preset.id + " has no routes", preset.routes.isEmpty());
            for (ProviderPresets.RouteSpec spec : preset.routes) {
                assertTrue(spec.protocol, transports.supports(spec.protocol));
                RouteDialect.parse(spec.protocol, spec.dialect);
                if (spec.baseUrl.isEmpty()) { assertTrue("only custom presets leave the endpoint to the user", preset.custom()); continue; }
                assertTrue(spec.baseUrl, spec.baseUrl.startsWith("https://"));
                ChatDatabase.Route r = route(preset.id, spec.id);
                ModelProfileValidator.validateRoute(r);
                assertNotNull("a pristine preset route matches itself", presets.pristineSpec(r));
            }
        }
        transports.close();
        assertNotNull(presets.preset(ProviderPresets.CUSTOM_OPENAI));
        assertNotNull(presets.preset(ProviderPresets.CUSTOM_ANTHROPIC));
    }

    @Test public void everyCatalogEntryResolvesToAValidProfileOnItsRoute() throws Exception {
        HashSet<String> keys = new HashSet<>();
        for (ProviderPresets.CatalogModel c : ProviderPresets.get().models) {
            assertTrue("duplicate catalog entry " + c.preset + "/" + c.route + "/" + c.id, keys.add(c.preset + "/" + c.route + "/" + c.id));
            ProviderPresets.Preset preset = ProviderPresets.get().preset(c.preset);
            assertNotNull(c.preset, preset);
            assertNotNull(c.preset + "/" + c.route, preset.route(c.route));
            assertTrue(c.id + " retention " + c.retention, c.retention.isEmpty() || "none".equals(c.retention) || "30d".equals(c.retention) || "training".equals(c.retention));
            ChatDatabase.ModelProfile m = model(route(c.preset, c.route), c.id, "{}");
            assertEquals(c.preset + "/" + c.route + "/" + c.id, m.catalogKey);
            assertTrue("a catalog price is valid", m.pricing.equals("{}") || new JSONObject(m.pricing).has("input"));
        }
    }

    @Test public void catalogOnlyAppliesToThePristineReviewedEndpoint() {
        ChatDatabase.Route official = route("openai", "responses");
        assertNotNull(ProviderPresets.get().catalog(official, "gpt-6.1-sol"));
        assertNull("typo / lookalike model IDs get nothing", ProviderPresets.get().catalog(official, "gpt-6.1-soll"));

        ChatDatabase.Route moved = route("openai", "responses");
        moved.baseUrl = "https://api.openai.com.evil.example/v1";
        assertNull("a lookalike host is not the reviewed deployment", ProviderPresets.get().catalog(moved, "gpt-6.1-sol"));
        moved.baseUrl = "https://api.openai.com/v1/extra";
        assertNull(ProviderPresets.get().catalog(moved, "gpt-6.1-sol"));
        moved.baseUrl = "https://api.openai.com/v1/";
        assertNotNull("normalization (trailing slash) keeps the identity", ProviderPresets.get().catalog(moved, "gpt-6.1-sol"));

        ChatDatabase.Route custom = route("openai", "responses");
        custom.presetRouteId = "";
        assertNull("a custom route never inherits catalog claims", ProviderPresets.get().catalog(custom, "gpt-6.1-sol"));
        assertTrue(ProviderPresets.get().catalogFor(custom).isEmpty());
    }

    @Test public void unknownDeploymentsKeepConservativeDefaultsAndSayWhereEachValueCameFrom() throws Exception {
        ChatDatabase.Route route = route("openai", "chat");
        ChatDatabase.ModelProfile m = model(route, "some-new-model", "{}");
        assertEquals(ReasoningPolicy.WIRE_NONE, m.reasoningWire);
        assertEquals("", m.reasoningLevels);
        assertEquals(ClientToolRuntime.NONE, m.clientToolCalling);
        assertEquals("text", m.inputModalities);
        assertEquals(ContextBudgetManager.DEFAULT_CONTEXT_TOKENS, m.contextTokens);
        JSONObject sources = new JSONObject(m.sources);
        for (String field : DeploymentResolver.FIELDS) assertEquals(field, DeploymentResolver.SRC_DEFAULT, sources.getString(field));
        assertFalse("an undeclared output limit stays off the wire", DeploymentResolver.sendsOutputCap(route.protocol, m.sources));
        assertTrue("Messages always sends one", DeploymentResolver.sendsOutputCap(AnthropicMessagesTransport.PROTOCOL, m.sources));
    }

    @Test public void overridesWinOverTheCatalogAndSurviveRecomputation() throws Exception {
        ChatDatabase.Route route = route("anthropic", "messages");
        ChatDatabase.ModelProfile m = model(route, "claude-opus-5-5", "{\"maxOutputTokens\":\"64000\",\"harnessPreset\":\"reinforced\"}");
        assertEquals(64_000, m.maxOutputTokens);
        assertEquals(HarnessProfile.REINFORCED, m.harnessPreset);
        assertEquals(1_000_000, m.contextTokens);
        assertEquals("web_search_20260318,web_fetch_20260318", m.toolCapabilities);
        JSONObject sources = new JSONObject(m.sources);
        assertEquals(DeploymentResolver.SRC_MANUAL, sources.getString("maxOutputTokens"));
        assertEquals(DeploymentResolver.SRC_CATALOG, sources.getString("contextTokens"));
        assertEquals("30d", m.retention);
        assertEquals("Claude Opus 5.5", m.displayName);
        // Re-resolving (a catalog update, a route save) keeps the user's values.
        DeploymentResolver.apply(route, m);
        assertEquals(64_000, m.maxOutputTokens);
        assertEquals(new JSONObject("{\"maxOutputTokens\":64000,\"harnessPreset\":\"reinforced\"}").toString(), new JSONObject(m.overrides).toString());
    }

    @Test public void catalogThinkingModesAreEnforcedAndUnknownOverrideKeysRejected() {
        ChatDatabase.Route route = route("anthropic", "messages");
        try { model(route, "claude-opus-5-5", "{\"thinkingMode\":\"disabled\"}"); fail("Opus 5.5 cannot turn thinking off"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("thinking")); }
        assertEquals(ReasoningPolicy.THINKING_DISABLED, model(route, "claude-haiku-5-5", "{\"thinkingMode\":\"disabled\"}").thinkingMode);
        try { model(route, "claude-haiku-5-5", "{\"temperature\":\"0.2\"}"); fail("unknown fields must not be stored"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("temperature")); }
    }

    @Test public void deepSeekPresetCarriesItsThinkingSwitchAndToolReasoningReplay() {
        ChatDatabase.Route route = route("deepseek", "chat");
        RouteDialect dialect = RouteDialect.parse(route.protocol, route.dialect);
        assertEquals(RouteDialect.THINKING_TYPE, dialect.thinkingParam);
        assertTrue(dialect.replaysReasoning(true));
        assertFalse(dialect.replaysReasoning(false));
        ChatDatabase.ModelProfile m = model(route, "deepseek-flash", "{\"thinkingMode\":\"disabled\"}");
        assertEquals("low,high,max", m.reasoningLevels);
        assertEquals(ClientToolRuntime.OPENAI_CHAT, m.clientToolCalling);
        assertEquals(ReasoningPolicy.THINKING_DISABLED, m.thinkingMode);
    }

    @Test public void deepSeekDefaultsToItsStatelessResponsesRouteForFlashOnly() {
        ProviderPresets.Preset deepseek = ProviderPresets.get().preset("deepseek");
        assertEquals("the first preset route is the default for new models", "responses", deepseek.routes.get(0).id);
        ChatDatabase.Route route = route("deepseek", "responses");
        assertEquals("https://api.deepseek.com/responses", ProviderEndpoint.requestUrl(route.baseUrl, OpenAiCompatibleTransport.RESPONSES));
        assertFalse(RouteDialect.parse(route.protocol, route.dialect).store);
        ChatDatabase.ModelProfile flash = model(route, "deepseek-flash", "{}");
        assertEquals(ReasoningPolicy.WIRE_OPENAI_RESPONSES, flash.reasoningWire);
        assertEquals("effort none is the thinking switch on Responses", "none,low,high,max", flash.reasoningLevels);
        assertEquals(ClientToolRuntime.OPENAI_RESPONSES, flash.clientToolCalling);
        assertEquals(ProviderContinuationPolicy.NONE, flash.continuationMode);
        assertNull("Responses serves deepseek-flash only; V4 Pro is an alias routed to Flash",
                ProviderPresets.get().catalog(route, "deepseek-v4-pro"));
        assertNull(ProviderPresets.get().catalog(route("deepseek", "chat"), "deepseek-v4-pro"));
    }

    @Test public void openCodeRoutesShareOneKeyAcrossThreeProtocols() {
        ProviderPresets.Preset go = ProviderPresets.get().preset("opencode-go");
        HashSet<String> protocols = new HashSet<>();
        for (ProviderPresets.RouteSpec spec : go.routes) protocols.add(spec.protocol);
        assertEquals(3, protocols.size());
        assertEquals("https://opencode.ai/inference/go/v1/messages",
                ProviderEndpoint.requestUrl(go.route("messages").baseUrl, AnthropicMessagesTransport.PROTOCOL));
        assertEquals("https://opencode.ai/inference/go/v1/chat/completions",
                ProviderEndpoint.requestUrl(go.route("chat").baseUrl, OpenAiCompatibleTransport.CHAT_COMPLETIONS));
        assertEquals("training", ProviderPresets.get().catalog(route("opencode-go", "responses"), "muse-spark-1.3-contributor").retention);
    }
}
