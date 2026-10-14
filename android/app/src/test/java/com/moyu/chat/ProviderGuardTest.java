package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/** Pure parts of the provider guard: model-list parsing, Retry-After, retry backoff, route caps, budgets, cost. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ProviderGuardTest {
    @Test public void modelListsOfEveryCommonShapeParseWithSaneLimitsOnly() throws Exception {
        Map<String, JSONObject> openAi = ProviderGuard.parseModels("{\"object\":\"list\",\"data\":[{\"id\":\"gpt\"},{\"id\":\"gpt\"},{\"id\":\"big\",\"context_length\":200000,\"max_output_tokens\":64000}]}");
        assertEquals("duplicates collapse", 2, openAi.size());
        assertEquals(0, openAi.get("gpt").length());
        assertEquals(200_000, openAi.get("big").getInt("contextTokens"));
        assertEquals(64_000, openAi.get("big").getInt("maxOutputTokens"));

        Map<String, JSONObject> router = ProviderGuard.parseModels("{\"data\":[{\"id\":\"r\",\"top_provider\":{\"context_length\":131072,\"max_completion_tokens\":8192},"
                + "\"architecture\":{\"input_modalities\":[\"text\",\"image\"]}}]}");
        assertEquals(131_072, router.get("r").getInt("contextTokens"));
        assertEquals(8_192, router.get("r").getInt("maxOutputTokens"));
        assertEquals("text,image", router.get("r").getString("inputModalities"));

        Map<String, JSONObject> odd = ProviderGuard.parseModels("{\"models\":[{\"name\":\"n\",\"context_window\":100,\"max_tokens\":999999999}]}");
        assertTrue(odd.containsKey("n"));
        assertFalse("implausible limits are dropped", odd.get("n").has("contextTokens"));
        assertFalse(odd.get("n").has("maxOutputTokens"));
        assertEquals(2, ProviderGuard.parseModels("[\"a\",{\"id\":\"b\"}]").size());
        assertTrue(ProviderGuard.parseModels("<html>").isEmpty());
        assertTrue(ProviderGuard.parseModels("{\"error\":\"x\"}").isEmpty());
    }

    private static Response response(String retryAfter) {
        Response.Builder b = new Response.Builder().request(new Request.Builder().url("https://example.com/").build())
                .protocol(Protocol.HTTP_1_1).code(429).message("Too Many Requests");
        if (retryAfter != null) b.header("Retry-After", retryAfter);
        return b.build();
    }

    @Test public void retryAfterAcceptsSecondsAndDatesAndCapsLongWaits() {
        assertEquals(0L, ProviderErrors.retryAfterMillis(response(null)));
        assertEquals(1_500L, ProviderErrors.retryAfterMillis(response("1.5")));
        assertEquals(0L, ProviderErrors.retryAfterMillis(response("soon")));
        assertTrue("an hour is more than we wait", ProviderErrors.retryAfterMillis(response("3600")) > ProviderErrors.MAX_RETRY_AFTER_MS);
        String inTenSeconds = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneId.of("GMT")).plusSeconds(10));
        long wait = ProviderErrors.retryAfterMillis(response(inTenSeconds));
        assertTrue(String.valueOf(wait), wait > 5_000 && wait <= 10_000);

        assertEquals(2_000L, ProviderRoundLoop.retryDelayMs(0, 0L));
        assertEquals(6_000L, ProviderRoundLoop.retryDelayMs(1, 0L));
        assertEquals(300L, ProviderRoundLoop.retryDelayMs(0, 300L));
        assertEquals("a longer ask than we wait ends the turn", -1L, ProviderRoundLoop.retryDelayMs(0, ProviderErrors.MAX_RETRY_AFTER_MS + 1));
    }

    @Test public void routeGateQueuesBeyondTheCapAndStartsTheNextWhenASlotFrees() {
        RouteGate gate = new RouteGate();
        List<String> started = new ArrayList<>();
        assertTrue(gate.enter("r", 0, () -> fail("no cap never queues")));
        assertTrue(gate.enter("r", 1, () -> started.add("first")));
        Runnable second = () -> started.add("second");
        Runnable third = () -> started.add("third");
        assertFalse(gate.enter("r", 1, second));
        assertFalse(gate.enter("r", 1, third));
        assertEquals(2, gate.queued("r"));
        assertTrue("a stopped turn leaves the queue", gate.withdraw("r", third));
        gate.leave("r");
        assertEquals(List.of("second"), started);
        assertEquals("the slot passed to the queued turn", 1, gate.active("r"));
        gate.leave("r");
        assertEquals(0, gate.active("r"));
        assertTrue("other routes are independent", gate.enter("other", 1, () -> { }));
    }

    @Test public void routeDialectCarriesAConcurrencyCapForEveryProtocol() throws Exception {
        for (String protocol : new String[]{OpenAiCompatibleTransport.CHAT_COMPLETIONS, OpenAiCompatibleTransport.RESPONSES, AnthropicMessagesTransport.PROTOCOL}) {
            RouteDialect d = RouteDialect.parse(protocol, new JSONObject().put("maxConcurrent", "2"));
            assertEquals(2, d.maxConcurrent);
            assertEquals(2, d.toJson(protocol).getInt("maxConcurrent"));
            assertEquals("0 means no cap and is not stored", 0, RouteDialect.parse(protocol, d.toJson(protocol).put("maxConcurrent", 0)).maxConcurrent);
            assertFalse(RouteDialect.parse(protocol, new JSONObject()).toJson(protocol).has("maxConcurrent"));
        }
        try { RouteDialect.parse(OpenAiCompatibleTransport.CHAT_COMPLETIONS, new JSONObject().put("maxConcurrent", 99)); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("并发上限")); }
    }

    @Test public void budgetsAreValidatedAndWindowsFollowTheLocalCalendar() throws Exception {
        JSONObject budget = ProviderConfigService.normalizeBudget(new JSONObject().put("currency", "cny").put("daily", "5").put("monthly", ""));
        assertEquals("CNY", budget.getString("currency"));
        assertEquals(5d, budget.getDouble("daily"), 0);
        assertFalse(budget.has("monthly"));
        assertEquals("no amounts clears the budget", 0, ProviderConfigService.normalizeBudget(new JSONObject().put("currency", "USD")).length());
        try { ProviderConfigService.normalizeBudget(new JSONObject().put("daily", -1)); fail(); } catch (IllegalArgumentException expected) { }
        try { ProviderConfigService.normalizeBudget(new JSONObject().put("currency", "EUR").put("daily", 1)); fail(); } catch (IllegalArgumentException expected) { }
        String[] windows = ProviderConfigService.budgetWindows(ZonedDateTime.of(2026, 10, 14, 15, 30, 0, 0, ZoneId.of("Asia/Shanghai")));
        assertEquals("2026-10-13T16:00:00Z", windows[0]);
        assertEquals("2026-09-30T16:00:00Z", windows[1]);
    }

    @Test public void turnCostPricesEachCallAndFlagsMissingUsage() throws Exception {
        TurnUsage usage = new TurnUsage();
        usage.setPricing(ModelPricing.normalize(new JSONObject().put("currency", "USD").put("input", 2).put("output", 10).put("cacheRead", 0.5)));
        ChatTransport fake = (provider, key, model, effort, prompt, sink) -> { sink.onUsageDetails(1_000_000, 100_000, 400_000, null, 1_000_000, null); sink.onCompleted(); return () -> { }; };
        usage.track(fake, TurnUsage.Kind.MAIN).stream(null, "", "", "", null, new ChatTransport.Sink() {
            @Override public void onTextDelta(String delta) { }
            @Override public void onUsage(Integer in, Integer out) { }
            @Override public void onCompleted() { }
            @Override public void onError(String code, String summary, boolean retryable) { }
        });
        JSONObject projected = new JSONObject();
        usage.project(projected);
        JSONObject cost = projected.getJSONObject("cost");
        assertEquals("600k × $2 + 400k × $0.5 + 100k × $10", 1.2 + 0.2 + 1.0, cost.getDouble("amount"), 1e-9);
        assertFalse(cost.getBoolean("partial"));
        ChatTransport silent = (provider, key, model, effort, prompt, sink) -> { sink.onCompleted(); return () -> { }; };
        usage.track(silent, TurnUsage.Kind.AUXILIARY).stream(null, "", "", "", null, new ChatTransport.Sink() {
            @Override public void onTextDelta(String delta) { }
            @Override public void onUsage(Integer in, Integer out) { }
            @Override public void onCompleted() { }
            @Override public void onError(String code, String summary, boolean retryable) { }
        });
        JSONObject again = new JSONObject();
        usage.project(again);
        assertTrue("a call without usage makes the sum a lower bound", again.getJSONObject("cost").getBoolean("partial"));
        TurnUsage unpriced = new TurnUsage();
        JSONObject none = new JSONObject();
        unpriced.project(none);
        assertFalse(none.has("cost"));
    }
}
