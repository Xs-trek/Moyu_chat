package com.moyu.chat;

import org.json.JSONObject;

/**
 * Wire knobs of one provider route: how this endpoint spells optional request fields that the base
 * protocol leaves open. They describe the endpoint (a preset fills them; the user may edit them),
 * never a model capability. Unknown keys are rejected so a typo cannot silently do nothing.
 */
final class RouteDialect {
    static final String CAP_MAX_TOKENS = "max_tokens";
    static final String CAP_MAX_COMPLETION_TOKENS = "max_completion_tokens";
    static final String CAP_MAX_OUTPUT_TOKENS = "max_output_tokens";
    static final String CAP_OMIT = "omit";

    static final String THINKING_NONE = "none";
    /** {@code thinking: {type: enabled|disabled}} on Chat Completions (DeepSeek, GLM, Kimi and gateways serving them). */
    static final String THINKING_TYPE = "thinking-type";

    static final String REPLAY_NEVER = "never";
    /** Echo {@code reasoning_content} on assistant messages once the request carries tools (DeepSeek). */
    static final String REPLAY_WITH_TOOLS = "with-tools";
    /** Echo {@code reasoning_content} on every assistant message (Kimi thinking models). */
    static final String REPLAY_ALWAYS = "always";

    /** Chat: max_tokens / max_completion_tokens / omit. Responses: max_output_tokens / omit. Messages always sends max_tokens. */
    final String outputCap;
    final boolean streamUsage;
    final String thinkingParam;
    final String reasoningReplay;
    final boolean store;
    final boolean autoCache;

    private RouteDialect(String outputCap, boolean streamUsage, String thinkingParam, String reasoningReplay, boolean store, boolean autoCache) {
        this.outputCap = outputCap;
        this.streamUsage = streamUsage;
        this.thinkingParam = thinkingParam;
        this.reasoningReplay = reasoningReplay;
        this.store = store;
        this.autoCache = autoCache;
    }

    /** Conservative defaults for an endpoint nobody has described yet. */
    static RouteDialect defaults(String protocol) {
        if (OpenAiCompatibleTransport.RESPONSES.equals(protocol)) return new RouteDialect(CAP_MAX_OUTPUT_TOKENS, false, THINKING_NONE, REPLAY_NEVER, false, false);
        if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) return new RouteDialect(CAP_MAX_TOKENS, false, THINKING_NONE, REPLAY_NEVER, false, false);
        return new RouteDialect(CAP_MAX_TOKENS, true, THINKING_NONE, REPLAY_NEVER, false, false);
    }

    /** Parses and validates a stored/submitted dialect for {@code protocol}; missing keys take the protocol default. */
    static RouteDialect parse(String protocol, JSONObject raw) {
        RouteDialect base = defaults(protocol);
        JSONObject in = raw == null ? new JSONObject() : raw;
        java.util.Iterator<String> keys = in.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowedKey(protocol, key)) throw new IllegalArgumentException("协议 " + protocol + " 的路线不支持参数 " + key);
        }
        String cap = in.optString("outputCap", base.outputCap).trim();
        boolean chat = OpenAiCompatibleTransport.CHAT_COMPLETIONS.equals(protocol);
        boolean responses = OpenAiCompatibleTransport.RESPONSES.equals(protocol);
        if (chat && !(CAP_MAX_TOKENS.equals(cap) || CAP_MAX_COMPLETION_TOKENS.equals(cap) || CAP_OMIT.equals(cap))) {
            throw new IllegalArgumentException("输出上限字段无效：" + cap + "。可选：max_tokens / max_completion_tokens / omit");
        }
        if (responses && !(CAP_MAX_OUTPUT_TOKENS.equals(cap) || CAP_OMIT.equals(cap))) {
            throw new IllegalArgumentException("输出上限字段无效：" + cap + "。可选：max_output_tokens / omit");
        }
        if (!chat && !responses) cap = CAP_MAX_TOKENS;
        String thinking = in.optString("thinkingParam", base.thinkingParam).trim();
        if (!(THINKING_NONE.equals(thinking) || THINKING_TYPE.equals(thinking))) throw new IllegalArgumentException("思考开关写法无效：" + thinking);
        String replay = in.optString("reasoningReplay", base.reasoningReplay).trim();
        if (!(REPLAY_NEVER.equals(replay) || REPLAY_WITH_TOOLS.equals(replay) || REPLAY_ALWAYS.equals(replay))) {
            throw new IllegalArgumentException("推理回传方式无效：" + replay);
        }
        return new RouteDialect(cap, in.optBoolean("streamUsage", base.streamUsage), thinking, replay,
                in.optBoolean("store", base.store), in.optBoolean("autoCache", base.autoCache));
    }

    static RouteDialect parse(String protocol, String json) {
        try { return parse(protocol, json == null || json.trim().isEmpty() ? new JSONObject() : new JSONObject(json)); }
        catch (org.json.JSONException error) { throw new IllegalArgumentException("路线参数不是有效的 JSON"); }
    }

    private static boolean allowedKey(String protocol, String key) {
        if (OpenAiCompatibleTransport.CHAT_COMPLETIONS.equals(protocol)) {
            return "outputCap".equals(key) || "streamUsage".equals(key) || "thinkingParam".equals(key) || "reasoningReplay".equals(key);
        }
        if (OpenAiCompatibleTransport.RESPONSES.equals(protocol)) return "outputCap".equals(key) || "store".equals(key);
        if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) return "autoCache".equals(key);
        return false;
    }

    /** Only the keys that apply to {@code protocol}, so the stored form round-trips exactly. */
    JSONObject toJson(String protocol) {
        try {
            JSONObject out = new JSONObject();
            if (OpenAiCompatibleTransport.CHAT_COMPLETIONS.equals(protocol)) {
                out.put("outputCap", outputCap).put("streamUsage", streamUsage).put("thinkingParam", thinkingParam).put("reasoningReplay", reasoningReplay);
            } else if (OpenAiCompatibleTransport.RESPONSES.equals(protocol)) {
                out.put("outputCap", outputCap).put("store", store);
            } else if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) {
                out.put("autoCache", autoCache);
            }
            return out;
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    /** Thinking modes this route can carry on the wire. Messages carries Anthropic's own field. */
    boolean carriesThinking(String protocol, String thinkingMode) {
        String mode = ReasoningPolicy.normalizeThinkingMode(thinkingMode);
        if (ReasoningPolicy.THINKING_PROVIDER_DEFAULT.equals(mode)) return true;
        if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) {
            return ReasoningPolicy.THINKING_ADAPTIVE.equals(mode) || ReasoningPolicy.THINKING_DISABLED.equals(mode) || ReasoningPolicy.THINKING_MANUAL.equals(mode);
        }
        if (OpenAiCompatibleTransport.CHAT_COMPLETIONS.equals(protocol) && THINKING_TYPE.equals(thinkingParam)) {
            return ReasoningPolicy.THINKING_ENABLED.equals(mode) || ReasoningPolicy.THINKING_DISABLED.equals(mode);
        }
        return false;
    }

    /** Whether assistant reasoning must ride along on Chat Completions history for this request. */
    boolean replaysReasoning(boolean requestHasTools) {
        return REPLAY_ALWAYS.equals(reasoningReplay) || (REPLAY_WITH_TOOLS.equals(reasoningReplay) && requestHasTools);
    }
}
