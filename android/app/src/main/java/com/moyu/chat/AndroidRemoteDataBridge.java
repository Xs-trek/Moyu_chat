package com.moyu.chat;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

/** Optional ContentProvider client. Absence/incompatibility is a normal standalone-Chat state. */
final class AndroidRemoteDataBridge implements RemoteDataBridge {
    static final String AUTHORITY = "com.moyu.remote.chatbridge";
    private static final String REMOTE_PACKAGE = "com.moyu.remote";
    private static final Uri URI = Uri.parse("content://" + AUTHORITY);
    private static final String RESULT_JSON = "json";
    private static final int MAX_RESULT_CHARS = 900_000;
    private static final int MAX_REQUEST_CHARS = 64_000;
    private static final int MAX_SOURCES = 500;
    private final Context context;
    private final ContentResolver resolver;

    AndroidRemoteDataBridge(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
    }

    @Override public Status status() {
        try {
            verifyRemoteProvider();
            JSONObject result = callVerified("status", null);
            int version = result.optInt("contractVersion", 0);
            boolean available = result.optBoolean("available", false) && version == CONTRACT_VERSION;
            String reason = available ? null : version == 0 ? "Remote bridge unavailable" : "Unsupported Remote bridge contract " + version;
            return new Status(available, version, reason);
        } catch (Exception error) {
            return new Status(false, 0, "Remote bridge unavailable or signature mismatch");
        }
    }

    @Override public JSONArray listSources() throws Exception {
        verifyRemoteProvider();
        JSONObject result = callVerified("listSources", null);
        JSONArray items = result.optJSONArray("items");
        if (items == null) return new JSONArray();
        if (items.length() > MAX_SOURCES) throw new IllegalStateException("Remote bridge returned too many sources");
        return items;
    }

    @Override public JSONObject createSnapshot(String sourceId, Integer throughSeq) throws Exception {
        verifyRemoteProvider();
        if (sourceId == null || sourceId.trim().isEmpty() || sourceId.length() > 512) throw new IllegalArgumentException("invalid Remote source id");
        if (throughSeq != null && throughSeq < 1) throw new IllegalArgumentException("invalid Remote branch point");
        JSONObject request = new JSONObject().put("sourceId", sourceId.trim());
        if (throughSeq != null) request.put("throughSeq", throughSeq);
        return callVerified("createSnapshot", request);
    }

    @Override public void submitHandoff(JSONObject handoff) throws Exception {
        verifyRemoteProvider();
        if (handoff == null) throw new IllegalArgumentException("handoff required");
        callVerified("submitHandoff", handoff);
    }

    private void verifyRemoteProvider() {
        PackageManager pm = context.getPackageManager();
        ProviderInfo provider = pm.resolveContentProvider(AUTHORITY, 0);
        if (provider == null || !REMOTE_PACKAGE.equals(provider.packageName)) throw new IllegalStateException("official Moyu Remote bridge not installed");
        if (pm.checkSignatures(context.getPackageName(), REMOTE_PACKAGE) != PackageManager.SIGNATURE_MATCH) throw new SecurityException("Moyu Remote signature mismatch");
    }

    private JSONObject callVerified(String method, JSONObject request) throws Exception {
        Bundle extras = new Bundle();
        if (request != null) {
            String encoded = request.toString();
            if (encoded.length() > MAX_REQUEST_CHARS) throw new IllegalArgumentException("Remote bridge request too large");
            extras.putString("json", encoded);
        }
        Bundle response = resolver.call(URI, method, null, extras);
        if (response == null) throw new IllegalStateException("Remote bridge returned no response");
        String json = response.getString(RESULT_JSON);
        if (json == null || json.isEmpty() || json.length() > MAX_RESULT_CHARS) throw new IllegalStateException("Remote bridge returned invalid or oversized response");
        JSONObject result = new JSONObject(json);
        if (!result.optBoolean("ok", true)) throw new IllegalStateException(result.optString("error", "Remote bridge request failed"));
        JSONObject data = result.optJSONObject("data");
        return data == null ? result : data;
    }
}
