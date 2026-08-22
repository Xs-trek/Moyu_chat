package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

final class UnavailableRemoteDataBridge implements RemoteDataBridge {
    @Override public Status status(){return new Status(false,0,"Moyu Remote bridge not installed or not configured");}
    @Override public JSONArray listSources(){return new JSONArray();}
    @Override public JSONObject createSnapshot(String sourceId,Integer throughSeq){throw new IllegalStateException("remote bridge unavailable");}
    @Override public void submitHandoff(JSONObject handoff){throw new IllegalStateException("remote bridge unavailable");}
}
