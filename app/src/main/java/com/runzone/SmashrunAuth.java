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
    static String authorize(String clientId,String state){
        if(clientId.trim().isEmpty()||state.isEmpty())throw new IllegalArgumentException();
        return "https://secure.smashrun.com/oauth2/authenticate?client_id="+encode(clientId)
                +"&redirect_uri="+encode(CALLBACK)+"&response_type=token&scope=write_activity&state="+encode(state);
    }
    private static String encode(String value){try{return URLEncoder.encode(value,"UTF-8");}catch(java.io.UnsupportedEncodingException e){throw new AssertionError(e);}}
    static JSONObject complete(String callback,String expectedState,long pendingExpiry,long now)throws Exception{
        URI uri=new URI(callback);
        if(!"runzone".equals(uri.getScheme())||!"accounts".equals(uri.getRawAuthority())
                ||!"/smashrun".equals(uri.getRawPath())||uri.getRawQuery()!=null
                ||pendingExpiry<=now||expectedState==null||expectedState.isEmpty())throw new IllegalArgumentException();
        Map<String,String> fields=new HashMap<>();String fragment=uri.getRawFragment();
        if(fragment==null||fragment.length()>16384)throw new IllegalArgumentException();
        for(String pair:fragment.split("&")){
            String[] parts=pair.split("=",2);if(parts.length!=2)throw new IllegalArgumentException();
            String key=URLDecoder.decode(parts[0],"UTF-8"),value=URLDecoder.decode(parts[1],"UTF-8");
            if(fields.put(key,value)!=null)throw new IllegalArgumentException();
        }
        String state=fields.getOrDefault("state","");
        if(!MessageDigest.isEqual(expectedState.getBytes(StandardCharsets.UTF_8),state.getBytes(StandardCharsets.UTF_8))
                ||fields.containsKey("error")||fields.getOrDefault("access_token","").trim().isEmpty()
                ||!"bearer".equalsIgnoreCase(fields.getOrDefault("token_type","")))throw new IllegalArgumentException();
        if(fields.containsKey("scope")&&!java.util.Arrays.asList(fields.get("scope").split("\\s+")).contains("write_activity"))throw new IllegalArgumentException();
        long seconds=Long.parseLong(fields.getOrDefault("expires_in","0"));
        if(seconds<=0||seconds>(Long.MAX_VALUE-now)/1000)throw new IllegalArgumentException();
        return new JSONObject().put("access_token",fields.get("access_token")).put("expires_at",(now+seconds*1000)/1000).put("direct",true);
    }
}
