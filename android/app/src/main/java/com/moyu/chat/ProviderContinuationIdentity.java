package com.moyu.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Stable identities for deployment-scoped calibration and session-chain-scoped native replay. */
final class ProviderContinuationIdentity {
    private static final String CREDENTIAL_EPOCH_PREFIX = "providerCredentialEpoch:";
    private static final String HISTORY_EPOCH_PREFIX = "sessionContinuationEpoch:";

    private ProviderContinuationIdentity() { }

    /** Concrete deployment identity; intentionally stable across ordinary conversation edits. */
    static String fingerprint(ChatDatabase db, ChatDatabase.Provider provider, ChatDatabase.Session session) {
        if (db == null || provider == null || session == null) return "";
        String endpoint;
        try { endpoint = ProviderEndpoint.normalize(provider.baseUrl); }
        catch (Exception ignored) { endpoint = provider.baseUrl == null ? "" : provider.baseUrl.trim(); }
        String credentialEpoch = db.setting(CREDENTIAL_EPOCH_PREFIX + provider.id, "0");
        String profile = session.modelProfileId == null ? "" : session.modelProfileId;
        String authMode = provider.authMode == null ? "" : provider.authMode.trim();
        return digest(provider.protocol + "\n" + endpoint + "\n" + authMode + "\n" + provider.id + "\n"
                + profile + "\n" + session.model + "\n" + credentialEpoch);
    }

    /** Native replay additionally requires an unbroken local conversation-history chain. */
    static String continuationFingerprint(ChatDatabase db, ChatDatabase.Provider provider, ChatDatabase.Session session) {
        if (session == null) return "";
        String historyEpoch = db == null ? "0" : db.setting(HISTORY_EPOCH_PREFIX + session.id, "0");
        return digest(fingerprint(db, provider, session) + "\n" + historyEpoch);
    }

    static void bumpCredentialEpoch(ChatDatabase db, String providerId) {
        if (db == null || providerId == null || providerId.trim().isEmpty()) return;
        bump(db, CREDENTIAL_EPOCH_PREFIX + providerId);
    }

    static void bumpHistoryEpoch(ChatDatabase db, String sessionId) {
        if (db == null || sessionId == null || sessionId.trim().isEmpty()) return;
        bump(db, HISTORY_EPOCH_PREFIX + sessionId);
    }

    private static void bump(ChatDatabase db, String key) {
        long current = 0L;
        try { current = Long.parseLong(db.setting(key, "0")); } catch (Exception ignored) { }
        db.putSetting(key, Long.toString(current + 1L));
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
