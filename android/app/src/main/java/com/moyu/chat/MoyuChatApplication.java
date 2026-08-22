package com.moyu.chat;

import android.app.Application;

public final class MoyuChatApplication extends Application {
    private ChatDatabase database;
    private ChatSecretStore secrets;
    @Override public void onCreate(){super.onCreate();database=new ChatDatabase(this);secrets=new ChatSecretStore(this);}
    ChatDatabase database(){return database;}
    ChatSecretStore secrets(){return secrets;}
}
