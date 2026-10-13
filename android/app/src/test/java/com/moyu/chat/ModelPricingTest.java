package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.time.Instant;

/** Price tables: validation, prompt-size tiers, cache split and off-peak windows. */
public class ModelPricingTest {
    private static final Instant WEEKDAY_PEAK = Instant.parse("2026-10-13T02:30:00Z");    // Tuesday 02:30 UTC
    private static final Instant WEEKDAY_OFF = Instant.parse("2026-10-13T12:00:00Z");
    private static final Instant SATURDAY_PEAK_HOUR = Instant.parse("2026-10-17T02:30:00Z");

    @Test public void cacheReadsAndWritesArePricedSeparately() throws Exception {
        JSONObject pricing = ModelPricing.normalize(new JSONObject("{\"currency\":\"usd\",\"input\":4,\"cacheWrite\":5,\"cacheRead\":0.2,\"output\":20}"));
        assertEquals("USD", pricing.getString("currency"));
        // 1M prompt: 600k uncached, 300k cache read, 100k cache write; 100k output.
        JSONObject cost = ModelPricing.estimate(pricing, 1_000_000, 100_000, 300_000, 100_000, WEEKDAY_OFF);
        assertEquals(0.6 * 4 + 0.3 * 0.2 + 0.1 * 5 + 0.1 * 20, cost.getDouble("amount"), 1e-9);
        assertNull("no price, no estimate", ModelPricing.estimate(new JSONObject(), 10, 10, 0, 0, WEEKDAY_OFF));
    }

    @Test public void promptSizeTiersSwitchEveryPrice() throws Exception {
        JSONObject pricing = ModelPricing.normalize(new JSONObject(
                "{\"input\":0.1,\"cacheRead\":0.01,\"output\":0.5,\"tiers\":[{\"above\":100000,\"input\":0.5,\"cacheRead\":0.05,\"output\":2.5}]}"));
        assertEquals((100_000 * 0.1 + 1_000 * 0.5) / 1e6, ModelPricing.estimate(pricing, 100_000, 1_000, 0, 0, null).getDouble("amount"), 1e-12);
        assertEquals((100_001 * 0.5 + 1_000 * 2.5) / 1e6, ModelPricing.estimate(pricing, 100_001, 1_000, 0, 0, null).getDouble("amount"), 1e-12);
    }

    @Test public void offPeakDiscountAppliesOutsideWeekdayPeakWindows() throws Exception {
        JSONObject pricing = ModelPricing.normalize(new JSONObject(
                "{\"input\":0.3,\"output\":1.2,\"offPeak\":{\"multiplier\":0.5,\"peakUtc\":[\"01:00-04:00\",\"06:00-10:00\"],\"weekdaysOnly\":true}}"));
        double full = (1_000_000 * 0.3 + 1_000_000 * 1.2) / 1e6;
        assertEquals(full, ModelPricing.estimate(pricing, 1_000_000, 1_000_000, 0, 0, WEEKDAY_PEAK).getDouble("amount"), 1e-9);
        assertEquals(full / 2, ModelPricing.estimate(pricing, 1_000_000, 1_000_000, 0, 0, WEEKDAY_OFF).getDouble("amount"), 1e-9);
        assertTrue(ModelPricing.estimate(pricing, 1_000_000, 1_000_000, 0, 0, SATURDAY_PEAK_HOUR).getBoolean("offPeak"));
    }

    @Test public void invalidTablesAreRejected() {
        assertRejected("{\"currency\":\"EUR\",\"input\":1,\"output\":1}", "USD 或 CNY");
        assertRejected("{\"input\":1}", "input 和 output");
        assertRejected("{\"input\":-1,\"output\":1}", "input");
        assertRejected("{\"input\":1,\"output\":1,\"tiers\":[{\"above\":0,\"input\":2}]}", "above");
        assertRejected("{\"input\":1,\"output\":1,\"offPeak\":{\"multiplier\":2,\"peakUtc\":[\"01:00-02:00\"]}}", "0–1");
        assertRejected("{\"input\":1,\"output\":1,\"offPeak\":{\"multiplier\":0.5,\"peakUtc\":[\"1am\"]}}", "HH:mm");
    }

    private static void assertRejected(String json, String part) {
        try { ModelPricing.normalize(new JSONObject(json)); fail("expected failure: " + part); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(part)); }
        catch (org.json.JSONException bad) { throw new AssertionError(bad); }
    }
}
