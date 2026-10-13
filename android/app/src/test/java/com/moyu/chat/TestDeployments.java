package com.moyu.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Test-only one-step setup. Production code saves an account, its routes and its Model Profiles
 * separately (ProviderConfigService); most tests only need "one account with one model", built from
 * the request-local deployment fields of a {@link ChatDatabase.Provider} (protocol, baseUrl, authMode,
 * limits). Each protocol of an account gets one route {@code <id>:<protocol>}, host already confirmed.
 */
final class TestDeployments {
    private TestDeployments() { }

    /** Saves the account and its route and, when {@code p.model} is set and the account has no profile yet, one profile {@code <id>:default}. */
    static ChatDatabase.ModelProfile save(ChatDatabase db, ChatDatabase.Provider p) {
        ensureRoute(db, p);
        if (p.model == null || p.model.trim().isEmpty() || !db.listModelProfiles(p.id).isEmpty()) return db.firstModelProfile(p.id);
        ChatDatabase.ModelProfile profile = profileFor(p, p.id + ":default", p.model.trim());
        db.saveModelProfile(profile);
        return profile;
    }

    static String routeId(ChatDatabase.Provider p) { return p.id + ":" + ChatDatabase.normalizeProtocol(p.protocol); }

    /** Upserts the account row and the route for {@code p.protocol} without touching its other routes. */
    static ChatDatabase.Route ensureRoute(ChatDatabase db, ChatDatabase.Provider p) {
        if (p.displayName == null || p.displayName.isEmpty()) p.displayName = p.id;
        ChatDatabase.Route route = new ChatDatabase.Route();
        route.id = routeId(p); route.providerId = p.id; route.label = p.protocol;
        route.protocol = ChatDatabase.normalizeProtocol(p.protocol);
        route.baseUrl = p.baseUrl; route.authMode = ProviderAuth.normalize(p.authMode, route.protocol);
        route.dialect = p.dialect == null ? "{}" : p.dialect.toJson(route.protocol).toString();
        route.headers = p.headers == null ? "{}" : p.headers.toString();
        List<ChatDatabase.Route> routes = new ArrayList<>();
        for (ChatDatabase.Route existing : db.listRoutes(p.id)) if (!existing.id.equals(route.id)) routes.add(existing);
        routes.add(route);
        db.saveProviderRoutes(p, routes, null);
        db.confirmRouteHost(route.id, ProviderEndpoint.host(route.baseUrl));
        return db.getRoute(route.id);
    }

    static ChatDatabase.ModelProfile profileFor(ChatDatabase.Provider p, String id, String modelId) {
        ChatDatabase.ModelProfile m = new ChatDatabase.ModelProfile();
        m.id = id; m.providerId = p.id; m.routeId = routeId(p); m.modelId = modelId; m.displayName = modelId;
        m.contextTokens = p.contextTokens > 0 ? p.contextTokens : ContextBudgetManager.DEFAULT_CONTEXT_TOKENS;
        m.inputTokens = Math.max(0, p.inputTokens);
        m.maxOutputTokens = p.maxOutputTokens > 0 ? p.maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS;
        m.reasoningLevels = "";
        m.protocol = ChatDatabase.normalizeProtocol(p.protocol);
        m.reasoningWire = ReasoningPolicy.normalizeWire(p.reasoningWire, m.protocol);
        // Limits given explicitly by a test count as declared, so the output cap reaches the wire.
        m.sources = "{\"contextTokens\":\"manual\",\"maxOutputTokens\":\"manual\"}";
        return m;
    }

    static ChatDatabase.Session session(ChatDatabase db, String providerId, String modelId, String title, String originSnapshotId) {
        return session(db, providerId, modelId, title, originSnapshotId, null);
    }

    /** Session on {@code modelId}; an unknown model gets its own profile copied from the provider's first one. */
    static ChatDatabase.Session session(ChatDatabase db, String providerId, String modelId, String title, String originSnapshotId, String parentSessionId) {
        ChatDatabase.ModelProfile profile = db.findModelProfile(providerId, modelId);
        if (profile == null) {
            ChatDatabase.ModelProfile template = db.firstModelProfile(providerId);
            if (template == null) throw new IllegalStateException("provider has no model profile: " + providerId);
            template.id = providerId + ":" + modelId; template.modelId = modelId; template.displayName = modelId;
            db.saveModelProfile(template);
            profile = template;
        }
        return db.createSession(profile, title, originSnapshotId, parentSessionId, ReasoningPolicy.DEFAULT);
    }
}
