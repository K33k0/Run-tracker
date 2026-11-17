package com.runzone;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.*;

/** Each installation has its own non-exportable Android Keystore encryption key. */
final class SecureStore {
    private static final String ALIAS="runzone-account-key";
    private static SecretKey key()throws Exception{
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
        if(!keys.containsAlias(ALIAS)){KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();}
        return (SecretKey)keys.getKey(ALIAS,null);
    }
    static synchronized void put(Context context,String name,String value)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String data=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!context.getSharedPreferences("account-secrets",0).edit().putString(name,data).commit())throw new java.io.IOException("Couldn't store account");
    }
    static synchronized String get(Context context,String name)throws Exception{
        String data=context.getSharedPreferences("account-secrets",0).getString(name,null);if(data==null)return null;String[] parts=data.split(":",2);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
    }
    static boolean contains(Context context,String name){return context.getSharedPreferences("account-secrets",0).contains(name);}
    static void remove(Context context,String name){context.getSharedPreferences("account-secrets",0).edit().remove(name).commit();}
}
