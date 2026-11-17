package com.runzone;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class SyncHttp {
    static final class Response {final int code;final String text;Response(int code,String text){this.code=code;this.text=text;}JSONObject json()throws JSONException{return new JSONObject(text);}boolean ok(){return code>=200&&code<300;}}
    static Response request(String url,String method,Map<String,String> headers,byte[] body)throws IOException{
        URL target=new URL(url);if(!"https".equals(target.getProtocol()))throw new IOException("HTTPS required");
        HttpURLConnection connection=(HttpURLConnection)target.openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setRequestMethod(method);connection.setRequestProperty("User-Agent","RunZone/1.2 Android");connection.setRequestProperty("Accept","application/json");for(Map.Entry<String,String> h:headers.entrySet())connection.setRequestProperty(h.getKey(),h.getValue());
        try{if(body!=null){connection.setDoOutput(true);connection.setFixedLengthStreamingMode(body.length);try(OutputStream output=connection.getOutputStream()){output.write(body);}}
            int code=connection.getResponseCode();InputStream stream=code<400?connection.getInputStream():connection.getErrorStream();ByteArrayOutputStream output=new ByteArrayOutputStream();if(stream!=null)try(InputStream input=stream){byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){if(output.size()+count>1024*1024)throw new IOException("Response too large");output.write(buffer,0,count);}}return new Response(code,output.toString(StandardCharsets.UTF_8.name()));
        }finally{connection.disconnect();}
    }
    static Response json(String url,JSONObject input)throws IOException{return request(url,"POST",Collections.singletonMap("Content-Type","application/json"),input.toString().getBytes(StandardCharsets.UTF_8));}
    static Response upload(String url,String token,byte[] tcx,String filename,boolean strava)throws IOException{
        String boundary="RunZone"+UUID.randomUUID().toString().replace("-","");ByteArrayOutputStream body=new ByteArrayOutputStream();
        if(strava){field(body,boundary,"data_type","tcx");field(body,boundary,"sport_type","Run");field(body,boundary,"external_id",filename);}
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: application/vnd.garmin.tcx+xml\r\n\r\n").getBytes(StandardCharsets.UTF_8));body.write(tcx);body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        Map<String,String> headers=new HashMap<>();headers.put("Content-Type","multipart/form-data; boundary="+boundary);headers.put("Authorization","Bearer "+token);return request(url,"POST",headers,body.toByteArray());
    }
    private static void field(OutputStream out,String boundary,String name,String value)throws IOException{out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n").getBytes(StandardCharsets.UTF_8));}
}
