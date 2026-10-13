package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Accounts, routes and model profiles: validation, resolution, persistence and their view projection.
 * Runs on the coordinator's intent worker. The API Key only ever passes through {@link #saveCredential}
 * (called from the native secure field) and is never part of any payload this class reads or returns.
 */
final class ProviderConfigService {
    private final ChatDatabase db;
    private final ChatSecretStore secrets;
    private final ChatTransportRegistry transports;

    ProviderConfigService(ChatDatabase db, ChatSecretStore secrets, ChatTransportRegistry transports) {
        this.db = db; this.secrets = secrets; this.transports = transports;
    }

    // ---------- Accounts and routes ----------

    /**
     * Creates or updates an account. {@code routes}, when present, is the complete route set: routes left
     * out are deleted with their models. A new account without routes gets its preset's routes.
     */
    JSONObject saveAccount(JSONObject p) throws Exception {
        String providerId = p.optString("providerId", "").trim();
        ChatDatabase.Provider account = providerId.isEmpty() ? null : db.getProvider(providerId);
        if (!providerId.isEmpty() && account == null) throw new IllegalArgumentException("账号不存在");
        boolean created = account == null;
        if (created) {
            account = new ChatDatabase.Provider();
            account.id = "provider-" + UUID.randomUUID();
            String presetId = p.optString("presetId", ProviderPresets.CUSTOM_OPENAI).trim();
            if (ProviderPresets.get().preset(presetId) == null) throw new IllegalArgumentException("未知的服务商预设：" + presetId);
            account.presetId = presetId;
        } else if (p.has("presetId") && !account.presetId.equals(p.optString("presetId").trim())) {
            throw new IllegalArgumentException("账号创建后不能更换服务商预设；请新建一个账号");
        }
        ProviderPresets.Preset preset = ProviderPresets.get().preset(account.presetId);
        String name = p.optString("displayName", "").trim();
        if (name.isEmpty()) name = preset == null ? "" : preset.name;
        account.displayName = bounded(name, 128, "显示名称");

        JSONArray routesIn = p.optJSONArray("routes");
        if (routesIn == null && created) routesIn = presetRoutes(preset);
        List<ChatDatabase.Route> routes = routesIn == null ? null : buildRoutes(account, preset, routesIn);
        Map<String, String> confirmedBefore = new HashMap<>();
        HashSet<String> trustedHosts = new HashSet<>();
        for (ChatDatabase.Route existing : db.listRoutes(account.id)) {
            String confirmed = db.confirmedHost(existing.id);
            confirmedBefore.put(existing.id, confirmed);
            if (!confirmed.isEmpty() && confirmed.equals(ProviderEndpoint.host(existing.baseUrl))) trustedHosts.add(confirmed);
        }

        List<ChatDatabase.ModelProfile> profiles = new ArrayList<>();
        if (routes != null) {
            Map<String, ChatDatabase.Route> byId = new HashMap<>();
            for (ChatDatabase.Route r : routes) byId.put(r.id, r);
            for (ChatDatabase.ModelProfile m : db.listModelProfiles(account.id)) {
                ChatDatabase.Route route = byId.get(m.routeId);
                if (route == null) continue; // deleted with its route
                try {
                    DeploymentResolver.apply(route, m);
                    ModelProfileValidator.validateResolvedProfile(route, m);
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("模型「" + m.displayName + "」在新的路线配置下无效：" + invalid.getMessage());
                }
                profiles.add(m);
            }
        }
        db.saveProviderRoutes(account, routes, profiles);
        // A host the stored key was already confirmed for stays trusted for this account's other routes;
        // any other host waits for an explicit confirmation before the key is sent there.
        JSONArray routeIds = new JSONArray();
        for (ChatDatabase.Route route : db.listRoutes(account.id)) {
            routeIds.put(route.id);
            String host = ProviderEndpoint.host(route.baseUrl);
            if (!host.equals(db.confirmedHost(route.id)) && trustedHosts.contains(host)) db.confirmRouteHost(route.id, host);
        }
        return new JSONObject().put("providerId", account.id).put("hasCredential", secrets.has(account.id)).put("routeIds", routeIds);
    }

    private static JSONArray presetRoutes(ProviderPresets.Preset preset) throws Exception {
        JSONArray out = new JSONArray();
        if (preset == null) return out;
        for (ProviderPresets.RouteSpec spec : preset.routes) {
            if (spec.baseUrl.isEmpty()) continue; // custom presets: the user supplies the endpoint
            out.put(new JSONObject().put("presetRouteId", spec.id).put("label", spec.label).put("protocol", spec.protocol)
                    .put("baseUrl", spec.baseUrl).put("authMode", spec.authMode).put("dialect", spec.dialect));
        }
        return out;
    }

    private List<ChatDatabase.Route> buildRoutes(ChatDatabase.Provider account, ProviderPresets.Preset preset, JSONArray in) throws Exception {
        Map<String, ChatDatabase.Route> existing = new HashMap<>();
        for (ChatDatabase.Route r : db.listRoutes(account.id)) existing.put(r.id, r);
        List<ChatDatabase.Route> out = new ArrayList<>();
        HashSet<String> identities = new HashSet<>();
        if (in.length() == 0) throw new IllegalArgumentException("账号至少需要一条路线");
        if (in.length() > 8) throw new IllegalArgumentException("一个账号最多 8 条路线");
        for (int i = 0; i < in.length(); i++) {
            JSONObject r = in.optJSONObject(i);
            if (r == null) throw new IllegalArgumentException("路线格式无效");
            String routeId = r.optString("routeId", "").trim();
            ChatDatabase.Route route;
            if (!routeId.isEmpty()) {
                route = existing.get(routeId);
                if (route == null) throw new IllegalArgumentException("路线不存在");
                String protocol = r.optString("protocol", route.protocol).trim();
                if (!protocol.equals(route.protocol)) throw new IllegalArgumentException("路线创建后不能更换协议；请新建一条路线");
            } else {
                route = new ChatDatabase.Route();
                route.id = "route-" + UUID.randomUUID();
                route.providerId = account.id;
                route.protocol = r.optString("protocol", "").trim();
                route.presetRouteId = r.optString("presetRouteId", "").trim();
                if (!route.presetRouteId.isEmpty() && (preset == null || preset.route(route.presetRouteId) == null)) {
                    throw new IllegalArgumentException("预设中没有这条路线：" + route.presetRouteId);
                }
                ProviderPresets.RouteSpec spec = preset == null ? null : preset.route(route.presetRouteId);
                if (spec != null && !spec.protocol.equals(route.protocol)) throw new IllegalArgumentException("路线协议与预设不一致");
                route.authMode = spec == null ? ProviderAuth.defaultForProtocol(route.protocol) : spec.authMode;
                route.dialect = spec == null ? "{}" : spec.dialect.toString();
            }
            route.presetId = account.presetId;
            String label = r.optString("label", "").trim();
            route.label = bounded(label.isEmpty() ? (route.label == null || route.label.isEmpty() ? route.protocol : route.label) : label, 64, "路线名称");
            route.baseUrl = r.optString("baseUrl", route.baseUrl == null ? "" : route.baseUrl);
            if (r.has("authMode")) route.authMode = r.optString("authMode", "");
            if (r.has("dialect")) route.dialect = r.optJSONObject("dialect") == null ? "{}" : r.optJSONObject("dialect").toString();
            if (r.has("headers")) route.headers = r.optJSONObject("headers") == null ? "{}" : r.optJSONObject("headers").toString();
            try { ModelProfileValidator.validateRoute(route); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("路线「" + route.label + "」：" + invalid.getMessage()); }
            if (!transports.supports(route.protocol)) throw new IllegalArgumentException("当前不支持协议 " + route.protocol);
            if (!identities.add(route.protocol + "\n" + route.baseUrl)) throw new IllegalArgumentException("两条路线的协议和 Base URL 完全相同");
            out.add(route);
        }
        return out;
    }

    /** Deletes an account, its routes, models and API Key. Chats stay and must switch model to continue. */
    void deleteAccount(String providerId) {
        if (db.getProvider(providerId) == null) throw new IllegalArgumentException("账号不存在");
        db.deleteProvider(providerId);
        secrets.put(providerId, "");
    }

    /** The user confirmed that this account's key may be sent to the route's current host. */
    void confirmHost(String routeId) {
        ChatDatabase.Route route = db.getRoute(routeId);
        if (route == null) throw new IllegalArgumentException("路线不存在");
        db.confirmRouteHost(route.id, ProviderEndpoint.host(route.baseUrl));
    }

    /** Refuses to send the key to a host nobody confirmed (a route edited after the key was stored). */
    void requireConfirmedHost(ChatDatabase.Route route) {
        if (route == null || !secrets.has(route.providerId)) return;
        String host = ProviderEndpoint.host(route.baseUrl);
        if (!host.equals(db.confirmedHost(route.id))) {
            throw new IllegalStateException("路线「" + route.label + "」的域名已改为 " + host + "：在 Provider 页面确认后，才会把 API Key 发往这个域名");
        }
    }

    /** Distinct hosts of the account's routes, shown in the native key dialog ("the key will be sent to …"). */
    String hostsOf(String providerId) {
        java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<>();
        for (ChatDatabase.Route route : db.listRoutes(providerId)) hosts.add(ProviderEndpoint.host(route.baseUrl));
        return String.join("、", hosts);
    }

    /** Native secure-field entry: validates the key's shape, stores it, and trusts the account's current hosts. */
    void saveCredential(String providerId, String apiKey) {
        ChatDatabase.Provider account = db.getProvider(providerId);
        if (account == null) throw new IllegalArgumentException("账号不存在");
        String key = ChatSecretStore.normalizeKey(apiKey);
        String previous = secrets.get(providerId);
        secrets.put(providerId, key);
        try {
            account.keyStatus = "unknown"; account.keyCheckedAt = "";
            db.saveProvider(account);
            db.bumpCredentialEpoch(providerId);
            for (ChatDatabase.Route route : db.listRoutes(providerId)) db.confirmRouteHost(route.id, ProviderEndpoint.host(route.baseUrl));
        } catch (RuntimeException failure) {
            try { secrets.put(providerId, previous == null ? "" : previous); } catch (RuntimeException ignored) { }
            throw failure;
        }
    }

    // ---------- Models ----------

    JSONObject saveModel(JSONObject p) throws Exception {
        ChatDatabase.Provider account = db.getProvider(p.optString("providerId", "").trim());
        if (account == null) throw new IllegalArgumentException("账号不存在");
        String profileId = p.optString("modelProfileId", "").trim();
        ChatDatabase.ModelProfile m = profileId.isEmpty() ? new ChatDatabase.ModelProfile() : db.getModelProfile(profileId);
        if (m == null) throw new IllegalArgumentException("模型不存在");
        if (profileId.isEmpty()) { m.id = "model-" + UUID.randomUUID(); m.providerId = account.id; }
        else if (!account.id.equals(m.providerId)) throw new IllegalArgumentException("模型属于另一个账号");
        String routeId = p.optString("routeId", m.routeId == null ? "" : m.routeId).trim();
        ChatDatabase.Route route = db.getRoute(routeId);
        if (route == null || !account.id.equals(route.providerId)) throw new IllegalArgumentException("请选择这个账号下的一条路线");
        if (m.routeId != null && !m.routeId.equals(route.id)) { m.discovered = "{}"; m.probe = "{}"; } // evidence is per route
        String modelId = p.optString("modelId", m.modelId == null ? "" : m.modelId).trim();
        if (modelId.isEmpty()) throw new IllegalArgumentException("Model ID 不能为空");
        m.modelId = bounded(modelId, 256, "Model ID");
        if (p.has("displayName")) m.displayName = p.optString("displayName", "").trim();
        if (m.displayName != null && m.displayName.length() > 128) throw new IllegalArgumentException("模型显示名称过长");
        if (p.has("enabled")) m.enabled = p.optBoolean("enabled", true);
        if (p.has("overrides")) m.overrides = DeploymentResolver.normalizeOverrides(p.optJSONObject("overrides")).toString();
        DeploymentResolver.apply(route, m);
        ModelProfileValidator.validateResolvedProfile(route, m);
        if (m.displayName == null || m.displayName.isEmpty()) m.displayName = m.modelId;
        ChatDatabase.ModelProfile duplicate = db.findModelProfileOnRoute(route.id, m.modelId);
        if (duplicate != null && !duplicate.id.equals(m.id)) {
            throw new IllegalArgumentException("这条路线上已有 Model ID「" + m.modelId + "」，请编辑已有模型");
        }
        db.saveModelProfile(m);
        return new JSONObject().put("modelProfileId", m.id);
    }

    void deleteModel(String profileId) {
        if (db.getModelProfile(profileId) == null) throw new IllegalArgumentException("模型不存在");
        db.deleteModelProfile(profileId);
    }

    void setModelEnabled(String profileId, boolean enabled) {
        ChatDatabase.ModelProfile m = db.getModelProfile(profileId);
        if (m == null) throw new IllegalArgumentException("模型不存在");
        m.enabled = enabled;
        db.saveModelProfile(m);
    }

    /** What saving would resolve to, field by field with sources, plus the validation error if any. Never throws for bad input. */
    JSONObject preview(JSONObject p) throws Exception {
        ChatDatabase.Route route = db.getRoute(p.optString("routeId", "").trim());
        JSONObject out = new JSONObject();
        if (route == null) return out.put("fields", new JSONArray()).put("error", "请选择路线");
        ChatDatabase.ModelProfile m = new ChatDatabase.ModelProfile();
        String profileId = p.optString("modelProfileId", "").trim();
        ChatDatabase.ModelProfile stored = profileId.isEmpty() ? null : db.getModelProfile(profileId);
        if (stored != null && route.id.equals(stored.routeId)) { m.discovered = stored.discovered; m.probe = stored.probe; }
        m.id = "preview"; m.providerId = route.providerId; m.modelId = p.optString("modelId", "").trim();
        String error = "";
        try {
            m.overrides = DeploymentResolver.normalizeOverrides(p.optJSONObject("overrides")).toString();
            DeploymentResolver.apply(route, m);
            ModelProfileValidator.validateResolvedProfile(route, m);
            if (m.modelId.isEmpty()) error = "填写 Model ID 后预览";
        } catch (IllegalArgumentException invalid) { error = invalid.getMessage(); }
        DeploymentResolver.Resolution r = DeploymentResolver.resolve(route, m.modelId, DeploymentResolver.json(m.overrides),
                DeploymentResolver.json(m.discovered), DeploymentResolver.json(m.probe));
        JSONArray fields = new JSONArray();
        for (String field : DeploymentResolver.FIELDS) {
            fields.put(new JSONObject().put("field", field).put("label", DeploymentResolver.label(field))
                    .put("value", r.values.opt(field)).put("source", r.sources.optString(field, DeploymentResolver.SRC_DEFAULT)));
        }
        out.put("fields", fields).put("error", error).put("catalog", catalogJson(r.catalog))
                .put("sendsOutputCap", DeploymentResolver.sendsOutputCap(route.protocol, r.sources.toString()));
        return out;
    }

    /** Reviewed models offered for a route (empty for custom or edited routes), marking ones already added. */
    JSONObject routeCatalog(String routeId) throws Exception {
        ChatDatabase.Route route = db.getRoute(routeId);
        if (route == null) throw new IllegalArgumentException("路线不存在");
        HashSet<String> added = new HashSet<>();
        for (ChatDatabase.ModelProfile m : db.listModelProfiles(route.providerId)) if (route.id.equals(m.routeId)) added.add(m.modelId);
        JSONArray models = new JSONArray();
        for (ProviderPresets.CatalogModel c : ProviderPresets.get().catalogFor(route)) models.put(catalogJson(c).put("added", added.contains(c.id)));
        return new JSONObject().put("routeId", route.id).put("models", models).put("version", ProviderPresets.get().version);
    }

    private static JSONObject catalogJson(ProviderPresets.CatalogModel c) throws Exception {
        if (c == null) return null;
        JSONObject out = new JSONObject().put("modelId", c.id).put("name", c.name).put("verified", c.verified).put("retention", c.retention);
        JSONObject pricing = c.raw.optJSONObject("pricing");
        if (pricing != null) out.put("pricing", ModelPricing.normalize(pricing));
        return out;
    }

    /** Re-resolves profiles stamped by an older catalog, so reviewed corrections reach existing models. Best effort. */
    void refreshCatalog() {
        String version = ProviderPresets.get().version;
        for (ChatDatabase.ModelProfile m : db.listAllModelProfiles()) {
            if (version.equals(m.catalogVersion)) continue;
            ChatDatabase.Route route = db.getRoute(m.routeId);
            if (route == null || ProviderPresets.get().catalog(route, m.modelId) == null && m.catalogKey.isEmpty()) continue;
            try {
                DeploymentResolver.apply(route, m);
                ModelProfileValidator.validateResolvedProfile(route, m);
                db.saveModelProfile(m);
            } catch (RuntimeException ignored) { /* keep the stored resolution; the editor will show the conflict */ }
        }
    }

    // ---------- Editor vocabulary and view ----------

    private static JSONObject option(String value, String hint, String... protocols) throws Exception {
        JSONObject o = new JSONObject().put("value", value).put("hint", hint);
        if (protocols.length > 0) { JSONArray p = new JSONArray(); for (String x : protocols) p.put(x); o.put("protocols", p); }
        return o;
    }

    /** Everything the WebView editors offer comes from here, so the page never hard-codes validator vocabulary. */
    static JSONObject editorOptions() throws Exception {
        String chat = OpenAiCompatibleTransport.CHAT_COMPLETIONS, responses = OpenAiCompatibleTransport.RESPONSES, messages = AnthropicMessagesTransport.PROTOCOL;
        JSONArray presets = new JSONArray();
        for (ProviderPresets.Preset preset : ProviderPresets.get().presets) {
            JSONArray routes = new JSONArray();
            for (ProviderPresets.RouteSpec spec : preset.routes) routes.put(new JSONObject().put("id", spec.id).put("label", spec.label)
                    .put("protocol", spec.protocol).put("baseUrl", spec.baseUrl).put("authMode", spec.authMode).put("dialect", spec.dialect));
            JSONArray headers = new JSONArray(); for (String h : preset.suggestedHeaders) headers.put(h);
            presets.put(new JSONObject().put("id", preset.id).put("name", preset.name).put("kind", preset.kind).put("note", preset.note)
                    .put("console", preset.console).put("pricingUrl", preset.pricingUrl).put("suggestedHeaders", headers).put("routes", routes));
        }
        return new JSONObject()
                .put("catalogVersion", ProviderPresets.get().version)
                .put("presets", presets)
                .put("protocol", new JSONArray()
                        .put(option(chat, "Base URL 含版本段，例如 …/v1，追加 /chat/completions"))
                        .put(option(responses, "Base URL 含版本段，例如 …/v1，追加 /responses"))
                        .put(option(messages, "Base URL 不含 /v1，追加 /v1/messages")))
                .put("authMode", new JSONArray()
                        .put(option(ProviderAuth.BEARER, "Authorization: Bearer 请求头"))
                        .put(option(ProviderAuth.X_API_KEY, "x-api-key 请求头"))
                        .put(option(ProviderAuth.API_KEY, "api-key 请求头")))
                .put("outputCap", new JSONArray()
                        .put(option(RouteDialect.CAP_MAX_TOKENS, "多数兼容端点", chat))
                        .put(option(RouteDialect.CAP_MAX_COMPLETION_TOKENS, "OpenAI 官方推理模型要求", chat))
                        .put(option(RouteDialect.CAP_MAX_OUTPUT_TOKENS, "Responses 标准字段", responses))
                        .put(option(RouteDialect.CAP_OMIT, "从不发送，由服务端决定", chat, responses)))
                .put("thinkingParam", new JSONArray()
                        .put(option(RouteDialect.THINKING_NONE, "这条路线没有思考开关", chat))
                        .put(option(RouteDialect.THINKING_TYPE, "thinking: {type: enabled|disabled}（DeepSeek、GLM、Kimi 等）", chat)))
                .put("reasoningReplay", new JSONArray()
                        .put(option(RouteDialect.REPLAY_NEVER, "不回传推理内容", chat))
                        .put(option(RouteDialect.REPLAY_WITH_TOOLS, "带工具的请求回传（DeepSeek 要求）", chat))
                        .put(option(RouteDialect.REPLAY_ALWAYS, "每条回答都回传（Kimi 思考模型要求）", chat)))
                .put("reasoningWire", new JSONArray()
                        .put(option(ReasoningPolicy.WIRE_NONE, "不发送推理档位"))
                        .put(option(ReasoningPolicy.WIRE_OPENAI_RESPONSES, "reasoning.effort", responses))
                        .put(option(ReasoningPolicy.WIRE_OPENAI_CHAT_EFFORT, "reasoning_effort", chat))
                        .put(option(ReasoningPolicy.WIRE_ANTHROPIC_EFFORT, "output_config.effort", messages)))
                .put("thinkingMode", new JSONArray()
                        .put(option(ReasoningPolicy.THINKING_PROVIDER_DEFAULT, "不发送，由服务端默认"))
                        .put(option(ReasoningPolicy.THINKING_ENABLED, "需要路线声明思考开关", chat))
                        .put(option(ReasoningPolicy.THINKING_ADAPTIVE, "", messages))
                        .put(option(ReasoningPolicy.THINKING_DISABLED, "部分模型不接受关闭思考", chat, messages))
                        .put(option(ReasoningPolicy.THINKING_MANUAL, "需要填写手动思考预算；新模型已不接受", messages)))
                .put("clientToolCalling", new JSONArray()
                        .put(option(ClientToolRuntime.NONE, "不提供客户端工具"))
                        .put(option(ClientToolRuntime.OPENAI_CHAT, "", chat))
                        .put(option(ClientToolRuntime.OPENAI_RESPONSES, "", responses))
                        .put(option(ClientToolRuntime.ANTHROPIC_TOOLS, "", messages)))
                .put("continuationMode", new JSONArray()
                        .put(option(ProviderContinuationPolicy.NONE, "只用可见历史"))
                        .put(option(ProviderContinuationPolicy.NATIVE, "回放厂商私有推理状态", responses, messages)))
                .put("serviceTier", new JSONArray()
                        .put(option("", "不发送"))
                        .put(option("auto", "", chat, responses, messages))
                        .put(option("default", "", chat, responses))
                        .put(option("flex", "更便宜、更慢", chat, responses))
                        .put(option("priority", "更快、更贵", chat, responses))
                        .put(option("standard_only", "", messages)))
                .put("harnessPreset", new JSONArray()
                        .put(option(HarnessProfile.LEAN, "强模型：少干预"))
                        .put(option(HarnessProfile.BALANCED, "默认"))
                        .put(option(HarnessProfile.REINFORCED, "弱模型：多校验")))
                .put("reasoningLevels", new JSONArray().put(ReasoningPolicy.NONE_LEVEL).put(ReasoningPolicy.MINIMAL).put(ReasoningPolicy.LOW)
                        .put(ReasoningPolicy.MEDIUM).put(ReasoningPolicy.HIGH).put(ReasoningPolicy.XHIGH).put(ReasoningPolicy.MAX))
                .put("sources", new JSONObject().put(DeploymentResolver.SRC_DEFAULT, "默认").put(DeploymentResolver.SRC_DISCOVERED, "端点")
                        .put(DeploymentResolver.SRC_CATALOG, "目录").put(DeploymentResolver.SRC_PROBE, "实测").put(DeploymentResolver.SRC_MANUAL, "手动"));
    }

    JSONArray providersView() throws Exception {
        JSONArray providers = new JSONArray();
        for (ChatDatabase.Provider account : db.listProviders()) {
            ProviderPresets.Preset preset = ProviderPresets.get().preset(account.presetId);
            boolean hasKey = secrets.has(account.id);
            JSONArray routes = new JSONArray();
            Map<String, ChatDatabase.Route> byId = new HashMap<>();
            for (ChatDatabase.Route route : db.listRoutes(account.id)) {
                byId.put(route.id, route);
                String host = ProviderEndpoint.host(route.baseUrl);
                routes.put(new JSONObject().put("routeId", route.id).put("presetRouteId", route.presetRouteId).put("label", route.label)
                        .put("protocol", route.protocol).put("baseUrl", route.baseUrl).put("authMode", route.authMode).put("host", host)
                        .put("reviewed", ProviderPresets.get().pristineSpec(route) != null)
                        .put("hostConfirmed", !hasKey || host.equals(db.confirmedHost(route.id)))
                        .put("dialect", DeploymentResolver.json(route.dialect)).put("headers", DeploymentResolver.json(route.headers)));
            }
            JSONArray models = new JSONArray();
            String firstModel = "";
            int enabled = 0;
            for (ChatDatabase.ModelProfile m : db.listModelProfiles(account.id)) {
                ChatDatabase.Route route = byId.get(m.routeId);
                if (m.enabled) { enabled++; if (firstModel.isEmpty()) firstModel = m.modelId; }
                ProviderPresets.CatalogModel catalog = route == null ? null : ProviderPresets.get().catalog(route, m.modelId);
                models.put(new JSONObject()
                        .put("modelProfileId", m.id).put("routeId", m.routeId).put("routeLabel", route == null ? "" : route.label)
                        .put("modelId", m.modelId).put("displayName", m.displayName).put("enabled", m.enabled)
                        .put("protocol", m.protocol).put("reasoningWire", m.reasoningWire)
                        .put("contextTokens", m.contextTokens).put("inputTokens", m.inputTokens).put("maxOutputTokens", m.maxOutputTokens)
                        .put("reasoningLevels", m.reasoningLevels)
                        .put("thinkingMode", m.thinkingMode).put("thinkingBudgetTokens", m.thinkingBudgetTokens)
                        .put("continuationMode", m.continuationMode).put("harnessPreset", m.harnessPreset)
                        .put("clientToolCalling", m.clientToolCalling)
                        .put("inputModalities", m.inputModalities).put("outputModalities", m.outputModalities)
                        .put("toolCapabilities", m.toolCapabilities).put("serviceTier", m.serviceTier)
                        .put("pricing", DeploymentResolver.json(m.pricing)).put("retention", m.retention)
                        .put("sources", DeploymentResolver.json(m.sources)).put("overrides", DeploymentResolver.json(m.overrides))
                        .put("catalogKey", m.catalogKey).put("catalogVerified", catalog != null && catalog.verified));
            }
            providers.put(new JSONObject().put("providerId", account.id).put("displayName", account.displayName)
                    .put("presetId", account.presetId).put("presetName", preset == null ? account.presetId : preset.name)
                    .put("presetKind", preset == null ? "custom" : preset.kind)
                    .put("hasCredential", hasKey).put("keyStatus", account.keyStatus)
                    .put("model", firstModel).put("configured", hasKey && enabled > 0)
                    .put("routes", routes).put("models", models));
        }
        return providers;
    }

    private static String bounded(String value, int max, String label) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) throw new IllegalArgumentException(label + "不能为空");
        if (v.length() > max) throw new IllegalArgumentException(label + "过长");
        return v;
    }
}
