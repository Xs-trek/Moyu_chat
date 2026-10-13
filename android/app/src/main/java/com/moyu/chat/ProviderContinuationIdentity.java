package com.moyu.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Stable deployment identity for verifier calibration and provider-continuation matching. */
final class ProviderContinuationIdentity {
    private ProviderContinuationIdentity() { }

    /** Concrete deployment identity; intentionally stable across ordinary conversation edits. */
    static String fingerprint(ChatDatabase db, ChatDatabase.Provider provider, ChatDatabase.Session session) {
        if (db == null || provider == null || session == null) return "";
        String endpoint;
        try { endpoint = ProviderEndpoint.normalize(provider.baseUrl); }
        catch (Exception ignored) { endpoint = provider.baseUrl == null ? "" : provider.baseUrl.trim(); }
        String credentialEpoch = Long.toString(db.credentialEpoch(provider.id));
        String profile = session.modelProfileId == null ? "" : session.modelProfileId;
        // Protocol is frozen on the session; endpoint and auth come from the live route in the request view,
        // so editing a route's Base URL or auth retires replay state and calibration made against the old one.
        String protocol = session.protocol == null ? "" : session.protocol.trim();
        String authMode = provider.authMode == null ? "" : provider.authMode.trim();
        String route = session.routeId == null ? "" : session.routeId;
        return digest(protocol + "\n" + endpoint + "\n" + authMode + "\n" + provider.id + "\n" + route + "\n"
                + profile + "\n" + session.model + "\n" + credentialEpoch);
    }

    static void bumpCredentialEpoch(ChatDatabase db, String providerId) {
        if (db == null || providerId == null || providerId.trim().isEmpty()) return;
        db.bumpCredentialEpoch(providerId);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception ignored) { return Integer.toHexString(value.hashCode()); }
    }
}
