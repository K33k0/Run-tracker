package com.runzone;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import org.json.*;
import java.security.*;
import java.util.*;

final class CloudAccounts {
    static final class Reconnect extends java.io.IOException {Reconnect(){super("Reconnect account");}}
    static final String[] PROVIDERS={"smashrun","runalyze","strava"};
    static String name(String provider){return "runalyze".equals(provider)?"Runalyze":"smashrun".equals(provider)?"Smashrun":"Strava";}
    static boolean valid(String provider){return Arrays.asList(PROVIDERS).contains(provider);}
    static boolean configured(){try{java.net.URI url=new java.net.URI(BuildConfig.OAUTH_BASE_URL);return "https".equals(url.getScheme())&&url.getHost()!=null&&url.getUserInfo()==null&&url.getQuery()==null&&url.getFragment()==null;}catch(Exception e){return false;}}
    static boolean connected(Context c,String provider){return SecureStore.contains(c,"tokens_"+provider);}
    static boolean enabled(Context c,String provider){return c.getSharedPreferences("sync",0).getBoolean("enabled_"+provider,false);}
    static String summary(Context c,String provider){
        if(connected(c,provider))return enabled(c,provider)?"Connected · new runs sync automatically":"Connected · automatic sync off";
        android.content.SharedPreferences prefs=c.getSharedPreferences("sync",0);
        if(prefs.contains("ready_"+provider)&&!prefs.getBoolean("ready_"+provider,false))return "Connection setup pending";
        return "Connect your account";
    }
    static boolean setupPending(Context c,String provider){android.content.SharedPreferences p=c.getSharedPreferences("sync",0);return p.contains("ready_"+provider)&&!p.getBoolean("ready_"+provider,false);}
    private static boolean checkingReadiness;
    static synchronized void refreshReadiness(Context context){
        Context c=context.getApplicationContext();
        if(checkingReadiness||!configured()||System.currentTimeMillis()-c.getSharedPreferences("sync",0).getLong("readinessChecked",0)<30000)return;
        checkingReadiness=true;
        new Thread(()->{try{SyncHttp.Response response=SyncHttp.request(endpoint("/health"),"GET",Collections.emptyMap(),null);if(response.ok()){
            JSONObject providers=response.json().getJSONObject("providers");android.content.SharedPreferences.Editor edit=c.getSharedPreferences("sync",0).edit();for(String provider:PROVIDERS)edit.putBoolean("ready_"+provider,providers.getBoolean(provider));edit.putLong("readinessChecked",System.currentTimeMillis()).apply();
        }}catch(Exception ignored){}finally{synchronized(CloudAccounts.class){checkingReadiness=false;}}},"account-readiness").start();
    }
    static JSONObject tokens(Context c,String provider)throws Exception{String data=SecureStore.get(c,"tokens_"+provider);if(data==null)throw new java.io.IOException("Reconnect account");return new JSONObject(data);}
    static String session(Context c,String provider){return c.getSharedPreferences("sync",0).getString("session_"+provider,"");}
    private static String endpoint(String path){return BuildConfig.OAUTH_BASE_URL.replaceAll("/+$","")+path;}
    static Uri begin(Context c,String provider)throws Exception{
        if(!configured()||!valid(provider))throw new java.io.IOException();byte[] random=new byte[32];new SecureRandom().nextBytes(random);String verifier=Base64.encodeToString(random,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        String challenge=Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        JSONObject pending=new JSONObject().put("verifier",verifier).put("expires",System.currentTimeMillis()+600000);SecureStore.put(c,"oauth_"+provider,pending.toString());
        SyncHttp.Response response=SyncHttp.json(endpoint("/v1/oauth/start"),new JSONObject().put("provider",provider).put("challenge",challenge));if(!response.ok())throw new java.io.IOException();Uri uri=Uri.parse(response.json().getString("authorize_url"));
        String host="strava".equals(provider)?"www.strava.com":"smashrun".equals(provider)?"secure.smashrun.com":"runalyze.com";if(!"https".equals(uri.getScheme())||!host.equals(uri.getHost()))throw new java.io.IOException();return uri;
    }
    static String complete(Context c,Uri uri)throws Exception{
        String provider=uri.getLastPathSegment();if(!valid(provider)||uri.getQueryParameter("error")!=null)throw new java.io.IOException();String stored=SecureStore.get(c,"oauth_"+provider);if(stored==null)throw new java.io.IOException();JSONObject pending=new JSONObject(stored);if(pending.getLong("expires")<System.currentTimeMillis())throw new java.io.IOException();
        SyncHttp.Response response=SyncHttp.json(endpoint("/v1/oauth/finish"),new JSONObject().put("provider",provider).put("ticket",uri.getQueryParameter("ticket")).put("verifier",pending.getString("verifier")));
        if(!response.ok())throw new java.io.IOException();JSONObject tokens=response.json();if(tokens.optString("access_token").isEmpty())throw new java.io.IOException();
        synchronized(CloudAccounts.class){SecureStore.put(c,"tokens_"+provider,tokens.toString());SecureStore.remove(c,"oauth_"+provider);
        c.getSharedPreferences("sync",0).edit().putString("session_"+provider,UUID.randomUUID().toString()).putBoolean("enabled_"+provider,true).commit();}return provider;
    }
    static String accessToken(Context c,String provider,boolean forceRefresh)throws Exception{
        String connection=session(c,provider);JSONObject tokens=tokens(c,provider);
        if(forceRefresh||tokens.optLong("expires_at",0)*1000<System.currentTimeMillis()+60000){
            SyncHttp.Response response=SyncHttp.json(endpoint("/v1/oauth/refresh"),new JSONObject().put("provider",provider).put("refresh_token",tokens.optString("refresh_token")));
            if(response.code==401||response.code==400)throw new Reconnect();if(!response.ok())throw new java.io.IOException("Account service unavailable");tokens=response.json();synchronized(CloudAccounts.class){if(!connected(c,provider)||!connection.equals(session(c,provider)))throw new Reconnect();SecureStore.put(c,"tokens_"+provider,tokens.toString());}
        }
        return tokens.getString("access_token");
    }
    static void disconnect(Context c,String provider){
        Context app=c.getApplicationContext();String stored=null;try{stored=SecureStore.get(c,"tokens_"+provider);}catch(Exception ignored){}
        synchronized(CloudAccounts.class){SecureStore.remove(c,"tokens_"+provider);SecureStore.remove(c,"oauth_"+provider);app.getSharedPreferences("sync",0).edit().remove("session_"+provider).putBoolean("enabled_"+provider,false).commit();}
        final String data=stored;if(data!=null&&configured())new Thread(()->{try{JSONObject tokens=new JSONObject(data);String token=tokens.optString("refresh_token",tokens.optString("access_token"));SyncHttp.json(endpoint("/v1/oauth/revoke"),new JSONObject().put("provider",provider).put("token",token));}catch(Exception ignored){}},"account-disconnect").start();
    }
}
