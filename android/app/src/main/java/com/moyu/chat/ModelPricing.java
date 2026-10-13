package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Per-million-token prices of one deployment, in the currency the vendor bills (USD or CNY; never
 * converted). Optional tiers switch prices by prompt size; an optional off-peak rule scales prices
 * outside the vendor's peak windows. Estimates are estimates: holidays and promotions are ignored.
 */
final class ModelPricing {
    private static final String[] PRICE_KEYS = {"input", "output", "cacheRead", "cacheWrite"};

    private ModelPricing() { }

    /** Validated canonical copy. Empty object = no price known. */
    static JSONObject normalize(JSONObject raw) {
        JSONObject out = new JSONObject();
        if (raw == null || raw.length() == 0) return out;
        try {
            String currency = raw.optString("currency", "USD").trim().toUpperCase(java.util.Locale.ROOT);
            if (!("USD".equals(currency) || "CNY".equals(currency))) throw new IllegalArgumentException("价格币种只支持 USD 或 CNY");
            out.put("currency", currency);
            copyPrices(raw, out, true);
            JSONArray tiers = raw.optJSONArray("tiers");
            if (tiers != null && tiers.length() > 0) {
                JSONArray normalized = new JSONArray();
                long previous = 0;
                for (int i = 0; i < tiers.length(); i++) {
                    JSONObject tier = tiers.optJSONObject(i);
                    if (tier == null) throw new IllegalArgumentException("价格分档格式无效");
                    long above = tier.optLong("above", -1);
                    if (above <= previous) throw new IllegalArgumentException("价格分档的 above 必须递增且大于 0");
                    previous = above;
                    JSONObject t = new JSONObject().put("above", above);
                    copyPrices(tier, t, false);
                    normalized.put(t);
                }
                out.put("tiers", normalized);
            }
            JSONObject offPeak = raw.optJSONObject("offPeak");
            if (offPeak != null) {
                double multiplier = offPeak.optDouble("multiplier", Double.NaN);
                if (!(multiplier > 0 && multiplier <= 1)) throw new IllegalArgumentException("闲时折扣倍率必须在 0–1 之间");
                JSONArray windows = offPeak.optJSONArray("peakUtc");
                if (windows == null || windows.length() == 0) throw new IllegalArgumentException("闲时规则需要高峰时段 peakUtc");
                for (int i = 0; i < windows.length(); i++) window(windows.optString(i));
                out.put("offPeak", new JSONObject().put("multiplier", multiplier).put("peakUtc", windows)
                        .put("weekdaysOnly", offPeak.optBoolean("weekdaysOnly", false)));
            }
            return out;
        } catch (org.json.JSONException error) { throw new IllegalArgumentException("价格格式无效"); }
    }

    private static void copyPrices(JSONObject from, JSONObject to, boolean requireBase) throws org.json.JSONException {
        for (String key : PRICE_KEYS) {
            if (!from.has(key)) {
                if (requireBase && ("input".equals(key) || "output".equals(key))) throw new IllegalArgumentException("价格至少需要 input 和 output");
                continue;
            }
            double value = from.optDouble(key, Double.NaN);
            if (!(value >= 0) || value > 10_000) throw new IllegalArgumentException("价格 " + key + " 无效：" + from.opt(key));
            to.put(key, value);
        }
    }

    private static LocalTime[] window(String text) {
        String[] parts = text == null ? new String[0] : text.split("-");
        if (parts.length != 2) throw new IllegalArgumentException("高峰时段格式应为 HH:mm-HH:mm：" + text);
        try { return new LocalTime[]{LocalTime.parse(parts[0].trim()), LocalTime.parse(parts[1].trim())}; }
        catch (Exception error) { throw new IllegalArgumentException("高峰时段格式应为 HH:mm-HH:mm：" + text); }
    }

    /**
     * Estimated cost of one call; null when no price is known. {@code inputTokens} is the whole prompt
     * (cache reads and writes included), as the transports report it.
     */
    static JSONObject estimate(JSONObject pricing, long inputTokens, long outputTokens, long cachedInputTokens, long cacheWriteTokens, Instant at) {
        if (pricing == null || !pricing.has("input") || !pricing.has("output")) return null;
        try {
            JSONObject prices = new JSONObject(pricing.toString());
            JSONArray tiers = pricing.optJSONArray("tiers");
            if (tiers != null) for (int i = 0; i < tiers.length(); i++) {
                JSONObject tier = tiers.optJSONObject(i);
                if (tier != null && inputTokens > tier.optLong("above")) for (String key : PRICE_KEYS) if (tier.has(key)) prices.put(key, tier.getDouble(key));
            }
            double input = prices.getDouble("input"), output = prices.getDouble("output");
            double cacheRead = prices.optDouble("cacheRead", input), cacheWrite = prices.optDouble("cacheWrite", input);
            long cached = Math.max(0, cachedInputTokens), written = Math.max(0, cacheWriteTokens);
            long uncached = Math.max(0, inputTokens - cached - written);
            double amount = (uncached * input + cached * cacheRead + written * cacheWrite + Math.max(0, outputTokens) * output) / 1_000_000d;
            JSONObject offPeak = pricing.optJSONObject("offPeak");
            boolean discounted = offPeak != null && at != null && !inPeak(offPeak, at);
            if (discounted) amount *= offPeak.getDouble("multiplier");
            return new JSONObject().put("currency", pricing.optString("currency", "USD")).put("amount", amount).put("offPeak", discounted);
        } catch (org.json.JSONException error) { return null; }
    }

    private static boolean inPeak(JSONObject offPeak, Instant at) {
        ZonedDateTime utc = at.atZone(ZoneOffset.UTC);
        if (offPeak.optBoolean("weekdaysOnly", false) && (utc.getDayOfWeek() == DayOfWeek.SATURDAY || utc.getDayOfWeek() == DayOfWeek.SUNDAY)) return false;
        LocalTime time = utc.toLocalTime();
        JSONArray windows = offPeak.optJSONArray("peakUtc");
        if (windows != null) for (int i = 0; i < windows.length(); i++) {
            LocalTime[] w = window(windows.optString(i));
            if (!time.isBefore(w[0]) && time.isBefore(w[1])) return true;
        }
        return false;
    }
}
