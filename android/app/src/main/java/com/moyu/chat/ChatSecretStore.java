package com.moyu.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Chat-only credential storage. Provider API keys are never projected into WebView state. */
final class ChatSecretStore {
    private static final String ALIAS="moyu-chat-provider-secrets-v1";
    private final SharedPreferences prefs;
    ChatSecretStore(Context c){prefs=c.getSharedPreferences("moyu_chat_secure",Context.MODE_PRIVATE);}
    synchronized void put(String providerId,String value){if(value==null||value.isEmpty()){prefs.edit().remove(providerId).apply();return;}try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());byte[] data=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));byte[] iv=cipher.getIV();ByteBuffer b=ByteBuffer.allocate(4+iv.length+data.length);b.putInt(iv.length).put(iv).put(data);prefs.edit().putString(providerId,Base64.encodeToString(b.array(),Base64.NO_WRAP)).apply();}catch(Exception e){throw new IllegalStateException("cannot store provider secret",e);}}
    synchronized String get(String providerId){String encoded=prefs.getString(providerId,null);if(encoded==null)return null;try{ByteBuffer b=ByteBuffer.wrap(Base64.decode(encoded,Base64.NO_WRAP));int n=b.getInt();if(n<12||n>32||b.remaining()<=n)return null;byte[] iv=new byte[n];b.get(iv);byte[] data=new byte[b.remaining()];b.get(data);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));return new String(cipher.doFinal(data),StandardCharsets.UTF_8);}catch(Exception e){return null;}}
    boolean has(String providerId){return get(providerId)!=null;}
    private SecretKey key()throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(ALIAS))return(SecretKey)store.getKey(ALIAS,null);KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());return g.generateKey();}
}
