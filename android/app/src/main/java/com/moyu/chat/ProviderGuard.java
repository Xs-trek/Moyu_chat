package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Account checks the user starts (or that follow saving a key): verify the key with the endpoint's model
 * list (a free call), read that list for the model editor and the "discovered" capability layer, query
 * the balance where the vendor has an endpoint for it, and run a tiny connection test on one model.
 * Same rules as the transports: the key only goes to a confirmed host, redirects are never followed,
 * and nothing returned here ever contains the key. All methods block; callers run them off the UI worker.
 */
final class ProviderGuard {
    static final String KEY_UNKNOWN = "unknown", KEY_VALID = "valid", KEY_INVALID = "invalid", KEY_UNVERIFIED = "unverified", KEY_ERROR = "error";
    /** Bodies above this are not parsed (a model list is a few hundred KB at most). */
    private static final long MAX_BODY_BYTES = 4L * 1024 * 1024;
    private static final int MAX_MODELS = 1000;
    static final long PROBE_TIMEOUT_MS = 60_000L;

    private final ChatDatabase db;
    private final ChatSecretStore secrets;
    private final ProviderConfigService config;
    private final ChatTransportRegistry transports;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .fastFallback(true)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build();

    ProviderGuard(ChatDatabase db, ChatSecretStore secrets, ProviderConfigService config, ChatTransportRegistry transports) {
        this.db = db; this.secrets = secrets; this.config = config; this.transports = transports;
    }

    // ---------- Key and model list ----------

    /**
     * Checks the account's key against one route's model list and records the outcome as the key status:
     * 2xx valid, 401 invalid, 404/405 the endpoint has no list (unverified), anything else error.
     */
    JSONObject verifyKey(String providerId) throws Exception {
        ChatDatabase.Provider account = db.getProvider(providerId);
        if (account == null) throw new IllegalArgumentException("账号不存在");
        ChatDatabase.Route route = verificationRoute(providerId);
        if (route == null) throw new IllegalStateException("这个账号还没有路线");
        JSONObject listed = listModels(route.id);
        int status = listed.optInt("status", 0);
        String keyStatus = listed.optBoolean("ok") ? KEY_VALID
                : status == 401 ? KEY_INVALID
                : status == 404 || status == 405 ? KEY_UNVERIFIED
                : KEY_ERROR;
        String checkedAt = Instant.now().toString();
        db.setKeyStatus(providerId, keyStatus, checkedAt);
        return listed.put("keyStatus", keyStatus).put("checkedAt", checkedAt).put("routeLabel", route.label);
    }

    /** A preset route known to list models first, then any route. */
    private ChatDatabase.Route verificationRoute(String providerId) {
        ChatDatabase.Route fallback = null;
        for (ChatDatabase.Route route : db.listRoutes(providerId)) {
            ProviderPresets.RouteSpec spec = ProviderPresets.get().pristineSpec(route);
            if (spec != null && spec.listModels) return route;
            if (fallback == null) fallback = route;
        }
        return fallback;
    }

    /**
     * The route's own model list ({@code GET …/models}; Messages routes {@code GET …/v1/models}). Context and
     * output limits or image input the endpoint reports become the "discovered" layer of matching models.
     */
    JSONObject listModels(String routeId) throws Exception {
        ChatDatabase.Route route = db.getRoute(routeId);
        if (route == null) throw new IllegalArgumentException("路线不存在");
        String protocol = ChatDatabase.normalizeProtocol(route.protocol);
        String root = ProviderEndpoint.normalize(route.baseUrl, protocol);
        String url = AnthropicMessagesTransport.PROTOCOL.equals(protocol) ? root + "/v1/models" : root + "/models";
        String endpoint = ProviderConfigService.endpointKey(route);
        JSONObject out = new JSONObject().put("routeId", route.id).put("host", ProviderEndpoint.host(route.baseUrl)).put("url", url);
        Call call = get(route, url);
        if (!call.ok) return out.put("ok", false).put("status", call.status).put("code", call.code).put("summary", call.summary);
        Map<String, JSONObject> models = parseModels(call.body);
        if (!endpoint.equals(ProviderConfigService.endpointKey(db.getRoute(route.id)))) {
            // The route was edited while the list was being read: it describes the old endpoint, so drop it.
            return out.put("ok", false).put("code", "endpoint_changed").put("summary", "读取期间路线地址已更改，这次结果已丢弃，请重新读取");
        }
        config.rememberDiscovered(route.id, endpoint, models);
        applyDiscovered(route, models);
        JSONArray list = new JSONArray();
        for (Map.Entry<String, JSONObject> e : models.entrySet()) list.put(new JSONObject(e.getValue().toString()).put("id", e.getKey()));
        return out.put("ok", true).put("status", call.status).put("models", list);
    }

    /** Model ids with whatever limits the endpoint states, in the endpoint's order. Unknown shapes give an empty map. */
    static Map<String, JSONObject> parseModels(String body) {
        LinkedHashMap<String, JSONObject> out = new LinkedHashMap<>();
        JSONArray items = null;
        try {
            String text = body == null ? "" : body.trim();
            if (text.startsWith("[")) items = new JSONArray(text);
            else {
                JSONObject root = new JSONObject(text);
                items = root.optJSONArray("data");
                if (items == null) items = root.optJSONArray("models");
            }
        } catch (Exception unparsable) { return out; }
        if (items == null) return out;
        for (int i = 0; i < items.length() && out.size() < MAX_MODELS; i++) {
            Object raw = items.opt(i);
            JSONObject item = raw instanceof JSONObject ? (JSONObject) raw : null;
            String id = item != null ? item.optString("id", item.optString("name", "")).trim() : raw instanceof String ? ((String) raw).trim() : "";
            if (id.isEmpty() || id.length() > 256 || out.containsKey(id)) continue;
            JSONObject fields = new JSONObject();
            if (item != null) try {
                JSONObject top = item.optJSONObject("top_provider");
                int context = firstInt(item, "context_length", "context_window", "max_context_length", "max_input_tokens");
                if (context <= 0 && top != null) context = firstInt(top, "context_length");
                int output = firstInt(item, "max_output_tokens", "max_completion_tokens", "max_tokens");
                if (output <= 0 && top != null) output = firstInt(top, "max_completion_tokens");
                if (context >= 2_048 && context <= 4_000_000) fields.put("contextTokens", context);
                if (output >= 256 && (context <= 0 || output < context) && output <= 4_000_000) fields.put("maxOutputTokens", output);
                JSONObject architecture = item.optJSONObject("architecture");
                JSONArray modalities = architecture != null ? architecture.optJSONArray("input_modalities") : item.optJSONArray("input_modalities");
                if (modalities != null) {
                    boolean image = false;
                    for (int m = 0; m < modalities.length(); m++) if ("image".equals(modalities.optString(m))) image = true;
                    fields.put("inputModalities", image ? "text,image" : "text");
                }
            } catch (org.json.JSONException ignored) { }
            out.put(id, fields);
        }
        return out;
    }

    private static int firstInt(JSONObject o, String... keys) {
        for (String key : keys) {
            long value = o.optLong(key, 0L);
            if (value > 0 && value <= Integer.MAX_VALUE) return (int) value;
        }
        return 0;
    }

    /** Re-resolves the route's models with what the list says (or that a model is missing from it). Best effort per model. */
    private void applyDiscovered(ChatDatabase.Route route, Map<String, JSONObject> models) {
        if (models.isEmpty()) return;
        for (ChatDatabase.ModelProfile m : db.listModelProfiles(route.providerId)) {
            if (!route.id.equals(m.routeId)) continue;
            try {
                JSONObject fields = models.get(m.modelId);
                JSONObject discovered = fields == null ? new JSONObject().put("listed", false) : new JSONObject(fields.toString()).put("listed", true);
                if (discovered.toString().equals(DeploymentResolver.json(m.discovered).toString())) continue;
                String previous = m.discovered;
                m.discovered = discovered.toString();
                try {
                    DeploymentResolver.apply(route, m);
                    ModelProfileValidator.validateResolvedProfile(route, m);
                } catch (IllegalArgumentException invalid) {
                    m.discovered = previous;
                    continue; // never let an endpoint's numbers make a working model invalid
                }
                db.saveModelProfile(m);
            } catch (Exception ignored) { }
        }
    }

    // ---------- Balance ----------

    /** Account balance from the vendor's balance endpoint (DeepSeek today); refused for vendors without one. */
    JSONObject balance(String providerId) throws Exception {
        ChatDatabase.Route route = null;
        ProviderPresets.RouteSpec spec = null;
        for (ChatDatabase.Route r : db.listRoutes(providerId)) {
            ProviderPresets.RouteSpec s = ProviderPresets.get().pristineSpec(r);
            if (s != null && !s.balancePath.isEmpty()) { route = r; spec = s; break; }
        }
        if (route == null) throw new IllegalStateException("这个服务商没有可查询的余额接口");
        String url = ProviderEndpoint.normalize(route.baseUrl, ChatDatabase.normalizeProtocol(route.protocol)) + spec.balancePath;
        JSONObject out = new JSONObject().put("checkedAt", Instant.now().toString()).put("host", ProviderEndpoint.host(route.baseUrl));
        Call call = get(route, url);
        if (!call.ok) return out.put("ok", false).put("status", call.status).put("code", call.code).put("summary", call.summary);
        JSONObject body;
        try { body = new JSONObject(call.body); } catch (Exception unparsable) { return out.put("ok", false).put("summary", "余额接口返回了无法识别的内容"); }
        JSONArray balances = new JSONArray();
        JSONArray infos = body.optJSONArray("balance_infos");
        if (infos != null) for (int i = 0; i < infos.length(); i++) {
            JSONObject info = infos.optJSONObject(i); if (info == null) continue;
            balances.put(new JSONObject().put("currency", info.optString("currency", ""))
                    .put("total", info.optString("total_balance", "")).put("granted", info.optString("granted_balance", ""))
                    .put("toppedUp", info.optString("topped_up_balance", "")));
        }
        return out.put("ok", true).put("available", body.optBoolean("is_available", true)).put("balances", balances);
    }

    // ---------- Connection test ----------

    /**
     * Sends one tiny request on the model's deployment and records what came back: success or the failure,
     * time to first output and in total, whether usage and reasoning text were reported. Stored with the
     * model (probe.result); it does not change any capability value.
     */
    JSONObject probe(String profileId) throws Exception {
        ChatDatabase.ModelProfile profile = db.getModelProfile(profileId);
        if (profile == null) throw new IllegalArgumentException("模型不存在");
        ChatDatabase.Route route = db.getRoute(profile.routeId);
        ChatDatabase.Provider account = db.getProvider(profile.providerId);
        if (route == null || account == null) throw new IllegalStateException("模型使用的路线已删除");
        String key = secrets.get(account.id);
        if (key == null) throw new IllegalStateException("这个账号还没有填写 API Key");
        config.requireConfirmedHost(route);
        ChatTransport transport = transports.forProtocol(ChatDatabase.normalizeProtocol(route.protocol));
        if (transport == null) throw new IllegalStateException("不支持这个账号的协议");
        ChatDatabase.Provider provider = ChatDatabase.deploymentProvider(account, route, profile);
        int cap = ReasoningPolicy.helperOutputCap(provider, ReasoningPolicy.DEFAULT, 64);
        if (provider.maxOutputTokens > 0) cap = Math.min(cap, Math.max(64, provider.maxOutputTokens));
        provider.maxOutputTokens = Math.max(64, cap);
        provider.sendOutputCap = true;
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("text", "Reply with the single word OK."));
        CanonicalPrompt prompt = new CanonicalPrompt("You are a connection test. Answer in one word.", messages, false, 0, 0, messages.length());

        long started = System.nanoTime();
        AtomicLong first = new AtomicLong(0L);
        StringBuilder text = new StringBuilder();
        AtomicBoolean usage = new AtomicBoolean(false), reasoning = new AtomicBoolean(false);
        AtomicReference<String[]> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        ChatTransport.Sink sink = new ChatTransport.Sink() {
            @Override public void onReasoningDelta(String delta) { if (delta != null && !delta.isEmpty()) { reasoning.set(true); first.compareAndSet(0L, System.nanoTime()); } }
            @Override public void onTextDelta(String delta) {
                if (delta == null || delta.isEmpty()) return;
                first.compareAndSet(0L, System.nanoTime());
                synchronized (text) { if (text.length() < 200) text.append(delta); }
            }
            @Override public void onUsage(Integer in, Integer out) { if (in != null || out != null) usage.set(true); }
            @Override public void onCompleted() { done.countDown(); }
            @Override public void onError(String code, String summary, boolean retryable) { failure.compareAndSet(null, new String[]{code, summary}); done.countDown(); }
            @Override public void onClientToolBatch(JSONArray calls) { failure.compareAndSet(null, new String[]{"unexpected_tool_call", "测试请求不带工具，模型却请求调用工具"}); done.countDown(); }
        };
        ChatTransport.Turn turn = transport.stream(provider, key, profile.modelId, ReasoningPolicy.DEFAULT, prompt, sink);
        if (!done.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            if (turn != null) try { turn.timeout(); } catch (Exception ignored) { }
            failure.compareAndSet(null, new String[]{"timeout", "60 秒内没有完成"});
        }
        long end = System.nanoTime();
        String reply;
        synchronized (text) { reply = text.toString().trim(); }
        String[] failed = failure.get();
        if (failed == null && reply.isEmpty()) failed = new String[]{"empty_output", "请求成功，但没有收到文字输出（可能输出上限都花在了推理上）"};
        JSONObject result = new JSONObject().put("checkedAt", Instant.now().toString()).put("ok", failed == null)
                .put("host", ProviderEndpoint.host(route.baseUrl))
                .put("totalMs", TimeUnit.NANOSECONDS.toMillis(end - started))
                .put("usageReported", usage.get()).put("reasoningSeen", reasoning.get())
                .put("reply", reply.length() > 80 ? reply.substring(0, 80) : reply);
        if (first.get() > 0L) result.put("firstOutputMs", TimeUnit.NANOSECONDS.toMillis(first.get() - started));
        if (failed != null) result.put("code", failed[0] == null ? "" : failed[0]).put("summary", failed[1] == null ? "" : failed[1]);
        ChatDatabase.ModelProfile current = db.getModelProfile(profileId);
        // Stored only while the model still points at the endpoint and model id that were tested.
        if (current != null && route.id.equals(current.routeId) && profile.modelId.equals(current.modelId)
                && ProviderConfigService.endpointKey(route).equals(ProviderConfigService.endpointKey(db.getRoute(route.id)))) {
            JSONObject probe = DeploymentResolver.json(current.probe).put("result", result);
            current.probe = probe.toString();
            db.saveModelProfile(current);
        }
        if (failed == null) db.setKeyStatus(account.id, KEY_VALID, Instant.now().toString());
        else if ("http_401".equals(failed[0])) db.setKeyStatus(account.id, KEY_INVALID, Instant.now().toString());
        return result;
    }

    // ---------- HTTP ----------

    private static final class Call {
        boolean ok; int status; String code = "", summary = "", body = "";
    }

    /** GET with the account key, the route's auth and extra headers; never follows a redirect. */
    private Call get(ChatDatabase.Route route, String url) throws Exception {
        String key = secrets.get(route.providerId);
        if (key == null) throw new IllegalStateException("这个账号还没有填写 API Key");
        config.requireConfirmedHost(route);
        String protocol = ChatDatabase.normalizeProtocol(route.protocol);
        Request.Builder request = new Request.Builder().url(url).get().header("Accept", "application/json");
        RouteHeaders.apply(request, RouteHeaders.parse(route.headers));
        ProviderAuth.apply(request, route.authMode, protocol, key);
        if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) request.header("anthropic-version", "2023-06-01");
        Call call = new Call();
        try (Response response = http.newCall(request.build()).execute()) {
            call.status = response.code();
            call.body = boundedBody(response.body());
            if (response.isSuccessful()) { call.ok = true; return call; }
            if (response.isRedirect()) {
                call.code = "redirect";
                call.summary = "端点要求跳转到别的地址（HTTP " + call.status + "）：为了不把 Key 带到别处，应用不跟随跳转，请检查 Base URL";
                return call;
            }
            call.code = "http_" + call.status;
            call.summary = WireCapture.scrub(ProviderErrors.httpSummary(call.status, errorDetail(call.body), ProviderErrors.requestId(response)), key);
            return call;
        } catch (IOException error) {
            call.code = "network_error";
            call.summary = WireCapture.scrub(ProviderErrors.networkSummary(error), key);
            return call;
        }
    }

    private static String boundedBody(ResponseBody body) throws IOException {
        if (body == null) return "";
        if (body.contentLength() > MAX_BODY_BYTES) return "";
        okio.BufferedSource source = body.source();
        source.request(MAX_BODY_BYTES + 1);
        if (source.getBuffer().size() > MAX_BODY_BYTES) return "";
        return source.readUtf8();
    }

    private static String errorDetail(String raw) {
        String detail = raw == null ? "" : raw.trim();
        try {
            JSONObject body = new JSONObject(detail);
            JSONObject error = body.optJSONObject("error");
            detail = error != null ? error.optString("message", detail) : body.optString("message", detail);
        } catch (Exception notJson) { }
        return detail.length() > 400 ? detail.substring(0, 400) : detail;
    }
}
