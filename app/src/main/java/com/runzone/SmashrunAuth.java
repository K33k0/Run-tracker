package com.runzone;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** Direct mobile OAuth. No app secret or broker is used. */
final class SmashrunAuth {
    static final String CALLBACK="runzone://accounts/smashrun";
    static final class Failure extends IllegalArgumentException {
        Failure(String message){super(message);}
    }
    static String authorize(String clientId,String state){
        if(clientId.trim().isEmpty()||state.isEmpty())throw new IllegalArgumentException();
        // The public client has no registered redirect URI. Bind its callback to this attempt
        // even when the provider omits the OAuth state from the response.
        String redirect="client".equals(clientId)?CALLBACK+"?attempt="+encode(state):CALLBACK;
        return "https://secure.smashrun.com/oauth2/authenticate?client_id="+encode(clientId)
                +"&redirect_uri="+encode(redirect)+"&response_type=token&scope=write_activity&state="+encode(state);
    }
    private static String encode(String value){try{return URLEncoder.encode(value,"UTF-8");}catch(java.io.UnsupportedEncodingException e){throw new AssertionError(e);}}
    static JSONObject complete(String callback,String expectedState,long pendingExpiry,long now)throws Exception{
        URI uri=new URI(callback);
        if(!"runzone".equals(uri.getScheme())||!"accounts".equals(uri.getRawAuthority())
                ||!"/smashrun".equals(uri.getRawPath()))throw new IllegalArgumentException();
        if(pendingExpiry<=now)throw new Failure("Smashrun sign-in expired. Start again from Settings.");
        Map<String,String> fields=parse(uri.getRawFragment());
        Map<String,String> query=parse(uri.getRawQuery());
        String attempt=query.remove("attempt");
        for(String key:query.keySet()){
            if(!"state".equals(key)&&!"error".equals(key)&&!"error_description".equals(key))
                throw new IllegalArgumentException();
            if(fields.putIfAbsent(key,query.get(key))!=null)throw new IllegalArgumentException();
        }
        String state=fields.getOrDefault("state",attempt);
        if(expectedState==null||expectedState.isEmpty()
                ||!matches(expectedState,state)||(attempt!=null&&!matches(expectedState,attempt)))
            throw new Failure("Smashrun’s response didn’t match this sign-in attempt. Start again from Settings.");
        if("access_denied".equals(fields.get("error")))
            throw new Failure("Smashrun sign-in was declined. Allow activity uploads when prompted.");
        if(fields.containsKey("error"))
            throw new Failure("Smashrun rejected the sign-in request. Check the Smashrun connection setup.");
        if(fields.getOrDefault("access_token","").trim().isEmpty()
                ||!"bearer".equalsIgnoreCase(fields.getOrDefault("token_type","")))throw new IllegalArgumentException();
        if(fields.containsKey("scope")&&!java.util.Arrays.asList(fields.get("scope").split("\\s+")).contains("write_activity"))
            throw new Failure("Smashrun didn’t grant activity upload permission. Reconnect and allow uploads.");
        long seconds=Long.parseLong(fields.getOrDefault("expires_in","0"));
        if(seconds<=0||seconds>(Long.MAX_VALUE-now)/1000)throw new IllegalArgumentException();
        return new JSONObject().put("access_token",fields.get("access_token")).put("expires_at",(now+seconds*1000)/1000).put("direct",true);
    }
    private static boolean matches(String expected,String actual){
        return actual!=null&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8));
    }
    private static Map<String,String> parse(String component)throws Exception{
        Map<String,String> fields=new HashMap<>();
        if(component==null)return fields;
        if(component.length()>16384)throw new IllegalArgumentException();
        for(String pair:component.split("&",-1)){
            String[] parts=pair.split("=",2);
            if(parts.length!=2)throw new IllegalArgumentException();
            String key=URLDecoder.decode(parts[0],"UTF-8"),value=URLDecoder.decode(parts[1],"UTF-8");
            if(fields.put(key,value)!=null)throw new IllegalArgumentException();
        }
        return fields;
    }
}
