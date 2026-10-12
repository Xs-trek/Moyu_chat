package com.moyu.chat;

import static org.junit.Assert.*;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Instant;

/** Durable provider-private continuation must remain deployment-scoped and mode-gated. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ProviderContinuationReplayTest {
    private ChatDatabase db;
    private ContextBuilder contexts;
    private ChatDatabase.Provider provider;
    private ChatDatabase.Session session;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("moyu-chat.db");
        db = new ChatDatabase(context);
        db.getWritableDatabase();

        provider = new ChatDatabase.Provider();
        provider.id = "continuation-provider";
        provider.displayName = "Continuation Provider";
        provider.protocol = OpenAiCompatibleTransport.RESPONSES;
        provider.baseUrl = "https://one.example/v1";
        provider.authMode = ProviderAuth.BEARER;
        provider.model = "";
        provider.contextTokens = 32_000;
        provider.maxOutputTokens = 4_096;
        db.saveProvider(provider);

        ChatDatabase.ModelProfile profile = new ChatDatabase.ModelProfile();
        profile.id = "continuation-profile";
        profile.providerId = provider.id;
        profile.modelId = "model-a";
        profile.displayName = "Model A";
        profile.contextTokens = 32_000;
        profile.maxOutputTokens = 4_096;
        profile.reasoningLevels = "";
        profile.protocol = OpenAiCompatibleTransport.RESPONSES;
        profile.authMode = ProviderAuth.BEARER;
        profile.reasoningWire = ReasoningPolicy.WIRE_NONE;
        profile.inputModalities = "text";
        profile.outputModalities = "text";
        profile.continuationMode = ProviderContinuationPolicy.NATIVE;
        db.saveModelProfile(profile);

        session = db.createSession(profile, "continuation", null, null, ReasoningPolicy.DEFAULT,
                ContextMode.STANDARD, ChatTurnOptions.WEB_OFF);
        contexts = new ContextBuilder(db);
    }

    @Test public void persistedContinuationIsProjectedIntoTheNextTurn() throws Exception {
        seedPersistedContinuation();

        CanonicalPrompt prompt = contexts.buildCommitted(db.getSession(session.id), 32_000, 0, 4_096, "");
        JSONObject assistant = assistant(prompt);

        assertTrue(prompt.allowProviderContinuation);
        assertNotNull("DB-persisted native state must survive into next-turn transport projection",
                assistant.optJSONObject("_providerContinuation"));
        assertEquals(3, assistant.getJSONObject("_providerContinuation").getJSONArray("items").length());
        assertEquals("reasoning", assistant.getJSONObject("_providerContinuation").getJSONArray("items")
                .getJSONObject(0).optString("type"));
    }

    @Test public void continuationModeNoneNeverProjectsPrivateState() throws Exception {
        seedPersistedContinuation();
        ChatDatabase.Session current = db.getSession(session.id);
        current.continuationMode = ProviderContinuationPolicy.NONE;
        db.saveSession(current);

        CanonicalPrompt prompt = contexts.buildCommitted(db.getSession(session.id), 32_000, 0, 4_096, "");
        assertFalse(prompt.allowProviderContinuation);
        assertNull(assistant(prompt).optJSONObject("_providerContinuation"));
    }

    @Test public void endpointOrCredentialEpochChangeInvalidatesPersistedContinuation() throws Exception {
        seedPersistedContinuation();
        assertNotNull(assistant(contexts.buildCommitted(db.getSession(session.id), 32_000, 0, 4_096, ""))
                .optJSONObject("_providerContinuation"));

        provider.baseUrl = "https://two.example/v1";
        db.saveProvider(provider);
        assertNull("endpoint change must invalidate old private replay state",
                assistant(contexts.buildCommitted(db.getSession(session.id), 32_000, 0, 4_096, ""))
                        .optJSONObject("_providerContinuation"));

        provider.baseUrl = "https://one.example/v1";
        db.saveProvider(provider);
        assertNotNull("restoring deployment identity before credential rotation should match the stored epoch",
                assistant(contexts.buildCommitted(db.getSession(session.id), 32_000, 0, 4_096, ""))
                        .optJSONObject("_providerContinuation"));

        ProviderContinuationIdentity.bumpCredentialEpoch(db, provider.id);
        assertNull("credential/account epoch change must invalidate old private replay state",
                assistant(contexts.buildCommitted(db.getSession(session.id), 32_000, 0, 4_096, ""))
                        .optJSONObject("_providerContinuation"));
    }

    @Test public void snapshotNeverCarriesProviderPrivateContinuation() throws Exception {
        seedPersistedContinuation();
        ChatDatabase.Snapshot snapshot = new SnapshotService(db).createLocal(session.id, null);
        JSONArray items = db.snapshotItems(snapshot.id);
        for (int i = 0; i < items.length(); i++) {
            assertFalse("provider-private continuation must not cross Snapshot/branch boundaries",
                    items.getJSONObject(i).has("providerContinuation"));
        }
    }

    private void seedPersistedContinuation() throws Exception {
        db.appendTimeline(session.id, "u1", message("user", "first"));
        ChatDatabase.Session current = db.getSession(session.id);
        ChatDatabase.Provider deployment = ChatDatabase.deploymentProvider(db.getProvider(provider.id), current);
        JSONArray nativeItems = new JSONArray()
                .put(new JSONObject().put("id", "rs_1").put("type", "reasoning").put("encrypted_content", "cipher"))
                .put(new JSONObject().put("id", "ws_1").put("type", "web_search_call"))
                .put(new JSONObject().put("id", "msg_1").put("type", "message").put("role", "assistant")
                        .put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", "answer"))));
        JSONObject state = new JSONObject()
                .put("protocol", deployment.protocol)
                .put("providerId", current.providerId)
                .put("modelProfileId", current.modelProfileId)
                .put("model", current.model)
                .put("deploymentFingerprint", ProviderContinuationIdentity.fingerprint(db, deployment, current))
                .put("items", nativeItems);
        JSONObject assistant = message("assistant", "answer").put("providerContinuation", state);
        db.appendTimeline(session.id, "a1", assistant);
        db.appendTimeline(session.id, "u2", message("user", "next"));
    }

    private static JSONObject assistant(CanonicalPrompt prompt) {
        for (int i = 0; i < prompt.messages.length(); i++) {
            JSONObject item = prompt.messages.optJSONObject(i);
            if (item != null && "assistant".equals(item.optString("role"))) return item;
        }
        fail("assistant projection missing");
        return new JSONObject();
    }

    private static JSONObject message(String role, String text) throws Exception {
        return new JSONObject().put("kind", "message").put("role", role).put("text", text)
                .put("createdAt", Instant.now().toString());
    }
}
