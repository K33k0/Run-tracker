package com.runzone;

import android.app.job.*;
import android.content.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Durable, account-bound upload queue. Provider tokens never appear in queue metadata. */
final class CloudSync {
    static final int JOB=1207;
    private static final Object UPLOAD_LOCK=new Object();
    private static android.content.SharedPreferences queue(Context c){return c.getSharedPreferences("sync-queue",0);}
    static void enqueueNew(Context c,File file){for(String provider:CloudAccounts.PROVIDERS)if(CloudAccounts.connected(c,provider)&&CloudAccounts.enabled(c,provider))enqueue(c,file,provider,true);}
    static void enqueue(Context c,File file,String provider){enqueue(c,file,provider,false);}
    private static void enqueue(Context c,File file,String provider,boolean automatic){
        if(!CloudAccounts.valid(provider)||!file.getName().matches("[A-Za-z0-9_-]+\\.json")||!CloudAccounts.connected(c,provider))return;
        try{String key=file.getName()+"|"+provider,session=CloudAccounts.session(c,provider);JSONObject existing=entry(c,key);String status=existing.optString("status");if(session.equals(existing.optString("session"))&&Arrays.asList("sent","synced","existing","processing").contains(status)){if(status.equals("processing"))schedule(c);return;}
            JSONObject entry=new JSONObject().put("run",file.getName()).put("provider",provider).put("session",session).put("automatic",automatic).put("status","pending").put("attempts",0);save(c,key,entry);schedule(c);
        }catch(JSONException ignored){}
    }
    static void schedule(Context c){try{JobScheduler jobs=c.getSystemService(JobScheduler.class);jobs.schedule(new JobInfo.Builder(JOB,new ComponentName(c,SyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setMinimumLatency(1000).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());}catch(RuntimeException ignored){}}
    private static JSONObject entry(Context c,String key){try{return new JSONObject(queue(c).getString(key,"{}"));}catch(JSONException e){return new JSONObject();}}
    private static void save(Context c,String key,JSONObject entry){queue(c).edit().putString(key,entry.toString()).commit();}
    static String status(Context c,String run,String provider){
        JSONObject entry=entry(c,run+"|"+provider);String status=entry.optString("status","");
        if(entry.optBoolean("automatic")&&!CloudAccounts.enabled(c,provider)&&Arrays.asList("pending","retry").contains(status))return "Automatic sync off · run is queued";
        if(!status.isEmpty()&&CloudAccounts.connected(c,provider)&&!CloudAccounts.session(c,provider).equals(entry.optString("session")))return "Previous account connection";
        switch(status){case "pending":case "retry":return "Queued · waiting for background sync";case "processing":return "Uploaded · Strava is processing";case "synced":return "Synced successfully";case "sent":return "Sent · processing on the service";case "existing":return "Already exists on the service";case "auth":return "Reconnect account to sync";case "error":return "Upload rejected · tap to retry";case "cancelled":return "Not synced · account disconnected";default:return CloudAccounts.connected(c,provider)?"Not synced · tap to send this run":CloudAccounts.setupPending(c,provider)?"Connection setup pending":"Connect account to sync";}
    }
    static boolean process(Context c,BooleanSupplier stopped){synchronized(UPLOAD_LOCK){
        boolean retry=false;int processed=0;
        for(String key:new TreeSet<>(queue(c).getAll().keySet())){
            if(stopped.getAsBoolean())return true;JSONObject item=entry(c,key);String status=item.optString("status");
            if(!Arrays.asList("pending","retry","processing").contains(status))continue;
            if(++processed>10)return true;
            String provider=item.optString("provider"),name=item.optString("run");
            try{
                if(!CloudAccounts.valid(provider)||!CloudAccounts.connected(c,provider)||!CloudAccounts.session(c,provider).equals(item.optString("session"))){item.put("status","cancelled");save(c,key,item);continue;}
                // Turning auto sync off pauses queued uploads as well. Explicit per-run sync still works.
                if(item.optBoolean("automatic")&&!CloudAccounts.enabled(c,provider)&&!item.has("uploadId"))continue;
                if(item.optLong("retryAt",0)>System.currentTimeMillis()){retry=true;continue;}
                File file=new File(new File(c.getFilesDir(),"runs"),name);if(!name.matches("[A-Za-z0-9_-]+\\.json")||!file.isFile()){item.put("status","error");save(c,key,item);continue;}
                String token;
                try{token=CloudAccounts.accessToken(c,provider,false);}catch(CloudAccounts.Reconnect e){item.put("status","auth");save(c,key,item);continue;}
                SyncHttp.Response response;
                if("strava".equals(provider)&&item.has("uploadId"))response=SyncHttp.request("https://www.strava.com/api/v3/uploads/"+item.getString("uploadId"),"GET",Collections.singletonMap("Authorization","Bearer "+token),null);
                else{
                    RunRecord run=RunRecord.read(file);StringWriter xml=new StringWriter();TcxExporter.write(run,xml);byte[] bytes=xml.toString().getBytes(StandardCharsets.UTF_8);String filename="runzone-"+name.replace(".json","")+".tcx";
                    if("smashrun".equals(provider)){Map<String,String> headers=new HashMap<>();headers.put("Authorization","Bearer "+token);headers.put("Content-Type","application/xml");response=SyncHttp.request("https://api.smashrun.com/v1/my/activities/","POST",headers,bytes);}
                    else response=SyncHttp.upload("strava".equals(provider)?"https://www.strava.com/api/v3/uploads":"https://runalyze.com/api/v1/activities/uploads",token,bytes,filename,"strava".equals(provider));
                }
                if(response.code==401){try{CloudAccounts.accessToken(c,provider,true);item.put("status","retry");retry=true;}catch(CloudAccounts.Reconnect e){item.put("status","auth");}}
                else if(response.code==429||response.code>=500){item.put("status","retry").put("retryAt",System.currentTimeMillis()+(response.code==429?900000:60000));retry=true;}
                else if(response.ok()){
                    if("strava".equals(provider)){
                        JSONObject data=response.json();String error=data.isNull("error")?"":data.optString("error","");
                        if(!error.isEmpty())item.put("status",error.toLowerCase(Locale.ROOT).contains("duplicate")?"existing":"error");
                        else if(data.optLong("activity_id",0)>0)item.put("status","synced").put("remoteId",data.optString("activity_id"));
                        else {String id=data.optString("id_str",data.optString("id"));if(!id.matches("[0-9]+"))throw new IOException("Missing upload ID");item.put("uploadId",id).put("status","processing");retry=true;}
                    }else item.put("status","sent");
                }else if(response.text.toLowerCase(Locale.ROOT).contains("duplicate"))item.put("status","existing");
                else item.put("status","error");
                item.put("attempts",item.optInt("attempts",0)+1);save(c,key,item);
            }catch(JSONException e){try{item.put("status","error");save(c,key,item);}catch(JSONException ignored){}}
            catch(Exception e){try{item.put("status","retry").put("retryAt",System.currentTimeMillis()+60000);save(c,key,item);retry=true;}catch(JSONException ignored){}}
        }
        return retry;
    }}
}
