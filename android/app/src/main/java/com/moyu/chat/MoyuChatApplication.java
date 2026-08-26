package com.moyu.chat;

import android.app.Application;

public final class MoyuChatApplication extends Application {
    private ChatDatabase database;
    private ChatSecretStore secrets;
    private ChatMediaStore mediaStore;
    private ChatRuntime chatRuntime;
    @Override public void onCreate(){super.onCreate();database=new ChatDatabase(this);secrets=new ChatSecretStore(this);mediaStore=new ChatMediaStore(this);}
    ChatDatabase database(){return database;}
    ChatSecretStore secrets(){return secrets;}
    ChatMediaStore mediaStore(){return mediaStore;}
    synchronized ChatRuntime chatRuntime(){ if(chatRuntime==null) chatRuntime=new ChatRuntime(this); return chatRuntime; }
}
