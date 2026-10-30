package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reviewed service presets and the model catalog, loaded from {@code provider-presets.json} (a Java
 * resource next to this class). A preset describes an endpoint: its routes, auth and wire dialect. A
 * catalog entry describes one model on one preset route; it only applies while the user's route still
 * points at that reviewed endpoint, so editing a Base URL drops the catalog instead of carrying claims
 * to an unknown host.
 */
final class ProviderPresets {
    static final String CUSTOM_OPENAI = "custom-openai";
    static final String CUSTOM_ANTHROPIC = "custom-anthropic";

    static final class RouteSpec {
        final String id, label, protocol, baseUrl, authMode, balancePath;
        final JSONObject dialect;
        final boolean listModels;
        RouteSpec(JSONObject o) {
            id = o.optString("id"); label = o.optString("label", id); protocol = o.optString("protocol");
            baseUrl = o.optString("baseUrl", ""); authMode = o.optString("authMode", ProviderAuth.defaultForProtocol(protocol));
            dialect = o.optJSONObject("dialect") == null ? new JSONObject() : o.optJSONObject("dialect");
            listModels = o.optBoolean("listModels", false); balancePath = o.optString("balancePath", "");
        }
    }

    static final class Preset {
        final String id, name, kind, note, console, pricingUrl;
        final List<RouteSpec> routes;
        final List<String> suggestedHeaders;
        Preset(JSONObject o) {
            id = o.optString("id"); name = o.optString("name", id); kind = o.optString("kind", "custom");
            note = o.optString("note", ""); console = o.optString("console", ""); pricingUrl = o.optString("pricingUrl", "");
            ArrayList<RouteSpec> r = new ArrayList<>();
            JSONArray routes = o.optJSONArray("routes");
            if (routes != null) for (int i = 0; i < routes.length(); i++) r.add(new RouteSpec(routes.optJSONObject(i)));
            this.routes = Collections.unmodifiableList(r);
            ArrayList<String> h = new ArrayList<>();
            JSONArray headers = o.optJSONArray("suggestedHeaders");
            if (headers != null) for (int i = 0; i < headers.length(); i++) h.add(headers.optString(i));
            suggestedHeaders = Collections.unmodifiableList(h);
        }
        RouteSpec route(String routeId) {
            for (RouteSpec r : routes) if (r.id.equals(routeId)) return r;
            return null;
        }
        boolean custom() { return "custom".equals(kind); }
    }

    /** One model on one preset route. Absent fields mean "not declared", never "false". */
    static final class CatalogModel {
        final String preset, route, id, name, harness, retention;
        final boolean verified;
        final JSONObject raw;
        CatalogModel(JSONObject o) {
            raw = o; preset = o.optString("preset"); route = o.optString("route"); id = o.optString("id"); name = o.optString("name", id);
            harness = o.optString("harness", ""); retention = o.optString("retention", ""); verified = o.optBoolean("verified", false);
        }
    }

    private static volatile ProviderPresets instance;

    final String version;
    final List<Preset> presets;
    final List<CatalogModel> models;

    private ProviderPresets(JSONObject root) {
        version = root.optString("version", "");
        ArrayList<Preset> p = new ArrayList<>();
        JSONArray presets = root.optJSONArray("presets");
        if (presets != null) for (int i = 0; i < presets.length(); i++) p.add(new Preset(presets.optJSONObject(i)));
        this.presets = Collections.unmodifiableList(p);
        ArrayList<CatalogModel> m = new ArrayList<>();
        JSONArray models = root.optJSONArray("models");
        if (models != null) for (int i = 0; i < models.length(); i++) m.add(new CatalogModel(models.optJSONObject(i)));
        this.models = Collections.unmodifiableList(m);
    }

    static ProviderPresets get() {
        ProviderPresets local = instance;
        if (local != null) return local;
        synchronized (ProviderPresets.class) {
            if (instance == null) instance = load();
            return instance;
        }
    }

    private static ProviderPresets load() {
        // Absolute: R8 moves this class to another package in the release build, and a relative name would follow it.
        try (InputStream in = ProviderPresets.class.getResourceAsStream("/com/moyu/chat/provider-presets.json")) {
            if (in == null) throw new IllegalStateException("provider-presets.json missing");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return new ProviderPresets(new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("cannot load provider presets", error); }
    }

    Preset preset(String id) {
        for (Preset p : presets) if (p.id.equals(id)) return p;
        return null;
    }

    /** The reviewed entry for this model on this route, or null when the route is not a pristine preset route. */
    CatalogModel catalog(ChatDatabase.Route route, String modelId) {
        RouteSpec spec = pristineSpec(route);
        if (spec == null || modelId == null) return null;
        String model = modelId.trim();
        for (CatalogModel m : models) if (m.preset.equals(route.presetId) && m.route.equals(spec.id) && m.id.equals(model)) return m;
        return null;
    }

    /** Catalog entries offered for a route (empty when the route was edited away from its preset endpoint). */
    List<CatalogModel> catalogFor(ChatDatabase.Route route) {
        ArrayList<CatalogModel> out = new ArrayList<>();
        RouteSpec spec = pristineSpec(route);
        if (spec == null) return out;
        for (CatalogModel m : models) if (m.preset.equals(route.presetId) && m.route.equals(spec.id)) out.add(m);
        return out;
    }

    /**
     * The preset route this route still is: same preset, same preset route id, same protocol and the same
     * normalized Base URL. Endpoint identity is part of deployment authority.
     */
    RouteSpec pristineSpec(ChatDatabase.Route route) {
        if (route == null || route.presetId == null || route.presetRouteId == null || route.presetRouteId.isEmpty()) return null;
        Preset preset = preset(route.presetId);
        RouteSpec spec = preset == null ? null : preset.route(route.presetRouteId);
        if (spec == null || spec.baseUrl.isEmpty() || !spec.protocol.equals(route.protocol)) return null;
        try {
            String expected = ProviderEndpoint.normalize(spec.baseUrl, spec.protocol);
            String actual = ProviderEndpoint.normalize(route.baseUrl, route.protocol);
            return expected.equals(actual) ? spec : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }
}
