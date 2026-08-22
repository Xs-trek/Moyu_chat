package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

/** Optional cross-APK bridge. Chat remains fully usable when no implementation is available. */
interface RemoteDataBridge {
    int CONTRACT_VERSION = 1;
    final class Status {
        final boolean available; final int contractVersion; final String reason;
        Status(boolean available,int contractVersion,String reason){this.available=available;this.contractVersion=contractVersion;this.reason=reason;}
    }
    Status status();
    JSONArray listSources() throws Exception;
    JSONObject createSnapshot(String sourceId,Integer throughSeq) throws Exception;
    void submitHandoff(JSONObject handoff) throws Exception;
}
