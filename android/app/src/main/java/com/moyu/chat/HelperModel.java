package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Spec D15: helper calls may run on another model (the "helper model", one global setting). Naming, the Router and Web
 * planning use it by default; compaction, verification / repair and subtasks stay on the session's model unless the
 * user moves them. The choice is frozen with the turn (T2); a helper model that cannot be used falls back to the
 * session's model and the turn records why.
 */
final class HelperModel {
    static final String SETTING_KEY = "helperModel";
    static final String TITLE = "title", ROUTER = "router", WEB_PLAN = "web_plan", COMPACTION = "compaction",
            VERIFY = "verify", SUBAGENT = "subagent";
    /** Every helper that can use the helper model, in display order. */
    static final List<String> ALL = Collections.unmodifiableList(java.util.Arrays.asList(TITLE, ROUTER, WEB_PLAN, COMPACTION, VERIFY, SUBAGENT));
    /** Light helpers whose quality does not shape the answer. */
    static final Set<String> DEFAULT_USE = Collections.unmodifiableSet(new LinkedHashSet<>(java.util.Arrays.asList(TITLE, ROUTER, WEB_PLAN)));

    /** Why a chosen helper model is not used this turn. */
    static final String MISSING = "helper_model_missing", NO_KEY = "helper_model_no_key", HOST_UNCONFIRMED = "helper_model_host_unconfirmed",
            OVER_BUDGET = "helper_model_over_budget", UNSUPPORTED = "helper_model_unsupported";

    private HelperModel() { }

    /** The stored setting: which profile ("" = the session's model) and which helpers use it. */
    static final class Choice {
        final String profileId;
        final Set<String> use;

        Choice(String profileId, Set<String> use) {
            this.profileId = profileId == null ? "" : profileId.trim();
            this.use = canonical(use);
        }

        static Choice parse(String raw) {
            try {
                JSONObject o = new JSONObject(raw == null || raw.trim().isEmpty() ? "{}" : raw);
                JSONArray use = o.optJSONArray("use");
                Set<String> ids = new LinkedHashSet<>();
                if (use == null) ids.addAll(DEFAULT_USE);
                else for (int i = 0; i < use.length(); i++) ids.add(use.optString(i, ""));
                return new Choice(o.optString("profileId", ""), ids);
            } catch (Exception unreadable) {
                return new Choice("", DEFAULT_USE);
            }
        }

        Choice withProfile(String id) { return new Choice(id, use); }

        Choice withUse(String helper, boolean on) {
            if (!ALL.contains(helper)) throw new IllegalArgumentException("unknown helper: " + helper);
            Set<String> next = new LinkedHashSet<>(use);
            if (on) next.add(helper); else next.remove(helper);
            return new Choice(profileId, next);
        }

        JSONObject toJson() throws org.json.JSONException {
            JSONArray ids = new JSONArray();
            for (String id : use) ids.put(id);
            return new JSONObject().put("profileId", profileId).put("use", ids);
        }
    }

    static Set<String> canonical(Set<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        if (ids != null) for (String id : ALL) if (ids.contains(id)) out.add(id);
        return Collections.unmodifiableSet(out);
    }

    /** The helper model of one turn: a frozen deployment ({@link #config}) or none, and the helpers that use it. */
    static final class Resolved {
        /** The helper model's own turn configuration; null = every helper uses the session's model. */
        final TurnConfig config;
        final Set<String> use;
        /** Why the chosen helper model is not used ("" when it is, or none was chosen). */
        final String fallback;
        final String profileId;

        Resolved(String profileId, TurnConfig config, Set<String> use, String fallback) {
            this.profileId = profileId == null ? "" : profileId;
            this.config = config;
            this.use = canonical(use);
            this.fallback = fallback == null ? "" : fallback;
        }

        boolean uses(String helper) { return config != null && use.contains(helper); }

        /** The helper model's account and prices, so its calls are billed there. */
        TurnUsage.Billing billing() {
            if (config == null) return null;
            JSONObject prices;
            try { prices = ModelPricing.normalize(new JSONObject(config.pricing)); } catch (Exception unpriced) { prices = null; }
            return new TurnUsage.Billing(config.providerId, prices);
        }

        /** Stored with the turn (turn config) and shown in its trace; null when no helper model was chosen. */
        JSONObject toJson() throws org.json.JSONException {
            if (profileId.isEmpty()) return null;
            JSONObject out = new JSONObject().put("profileId", profileId);
            if (config != null) {
                JSONArray ids = new JSONArray();
                for (String id : use) ids.put(id);
                out.put("providerId", config.providerId).put("model", config.model).put("modelName", config.modelName).put("use", ids);
            }
            if (!fallback.isEmpty()) out.put("fallback", fallback);
            return out;
        }
    }

    static final Resolved NONE = new Resolved("", null, Collections.emptySet(), "");

    static Resolved unavailable(String profileId, String reason) { return new Resolved(profileId, null, Collections.emptySet(), reason); }

    /** One helper stage's deployment: the helper model when it is used for this helper, else the session's model. */
    static final class Call {
        final ChatTransport transport;
        final ChatDatabase.Provider provider;
        final String key, model;
        final TurnConfig config;

        Call(ChatTransport transport, ChatDatabase.Provider provider, String key, String model, TurnConfig config) {
            this.transport = transport; this.provider = provider; this.key = key; this.model = model; this.config = config;
        }
    }
}
