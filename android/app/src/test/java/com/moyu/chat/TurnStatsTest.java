package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

/** Per model × mode aggregation: latency, helper share of tokens, how often helpers acted, ratings. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TurnStatsTest {
    private static JSONObject span(String stage, String status, long in, long out, JSONObject detail) throws Exception {
        return new JSONObject().put("stage", stage).put("status", status).put("inputTokens", in).put("outputTokens", out).put("detail", detail == null ? new JSONObject() : detail);
    }

    @Test public void aggregatesPerModelAndModeWithHelperShareAndActions() throws Exception {
        TurnStats stats = new TurnStats();
        JSONObject smart = new JSONObject().put("modelName", "Model A").put("effectiveContextMode", "smart");
        stats.add(smart, new JSONObject().put("firstOutputLatencyMs", 800).put("requestDurationMs", 4000), "ok", 1, Arrays.asList(
                span("route", "ok", 900, 100, null),
                span("web_plan", "ok", 0, 0, new JSONObject().put("useWeb", true)),
                span("generate", "ok", 8000, 1000, null),
                span("verify", "ok", 1500, 500, new JSONObject().put("repaired", true)),
                span("repair", "ok", 1000, 500, null)));
        stats.add(smart, new JSONObject().put("firstOutputLatencyMs", 1200).put("requestDurationMs", 6000), "failed", -1, Arrays.asList(
                span("route", "skipped", 0, 0, null),
                span("generate", "ok", 4000, 500, null),
                span("verify", "skipped", 0, 0, null)));
        stats.add(new JSONObject().put("modelName", "Model A").put("contextMode", "minimal"), new JSONObject(), "ok", null, Collections.emptyList());

        JSONArray rows = stats.toJson();
        assertEquals(2, rows.length());
        JSONObject row = rows.getJSONObject(0);
        assertEquals("most used first", "smart", row.getString("contextMode"));
        assertEquals(2, row.getInt("turns"));
        assertEquals(1, row.getInt("ok"));
        assertEquals(1, row.getInt("failed"));
        assertEquals(1000, row.getLong("avgFirstOutputMs"));
        assertEquals(5000, row.getLong("avgDurationMs"));
        assertEquals("route 1000 + verify 2000 + repair 1500", 4500, row.getLong("helperTokens"));
        assertEquals(13500, row.getLong("mainTokens"));
        assertEquals(4500d / 18000d, row.getDouble("helperShare"), 1e-9);
        assertEquals("skipped helpers do not count as acting", 1, row.getInt("routed"));
        assertEquals(1, row.getInt("verified"));
        assertEquals(1, row.getInt("repaired"));
        assertEquals(1, row.getInt("webUsed"));
        assertEquals(1, row.getInt("ratedUp"));
        assertEquals(1, row.getInt("ratedDown"));
        assertFalse("no tokens, no share", rows.getJSONObject(1).has("helperShare"));
    }
}
