package com.runzone;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class SmashrunAuthTest {
    private static final String CALLBACK=SmashrunAuth.CALLBACK+"#access_token=abc%2B123&token_type=bearer&expires_in=3600&state=expected";
    private void rejects(String callback,String state,long expiry){
        assertThrows(Exception.class,()->SmashrunAuth.complete(callback,state,expiry,1000));
    }
    @Test public void buildsDirectUserLevelLoginWithoutSecret(){
        String url=SmashrunAuth.authorize("client","random+state");
        assertTrue(url.startsWith("https://secure.smashrun.com/oauth2/authenticate?"));
        assertTrue(url.contains("client_id=client"));
        assertTrue(url.contains("response_type=token"));
        assertTrue(url.contains("scope=write_activity"));
        assertTrue(url.contains("state=random%2Bstate"));
        assertTrue(url.contains("redirect_uri=runzone%3A%2F%2Faccounts%2Fsmashrun"));
        assertFalse(url.contains("secret"));
    }
    @Test public void acceptsFragmentAndConvertsExpiryToEpochSeconds()throws Exception{
        JSONObject tokens=SmashrunAuth.complete(CALLBACK,"expected",2000,1000);
        assertEquals("abc+123",tokens.getString("access_token"));
        assertEquals(3601,tokens.getLong("expires_at"));
        assertFalse(tokens.has("refresh_token"));
    }
    @Test public void rejectsForgedMissingAndExpiredState(){
        rejects(CALLBACK,"forged",2000);
        rejects(CALLBACK.replace("&state=expected",""),"expected",2000);
        rejects(CALLBACK,"expected",1000);
        rejects(CALLBACK,"",2000);
    }
    @Test public void rejectsWrongCallbackAndQueryTokens(){
        rejects(CALLBACK.replace("accounts/","other/"),"expected",2000);
        rejects(CALLBACK.replace("accounts/","accounts:80/"),"expected",2000);
        rejects(CALLBACK.replace("/smashrun#","/strava#"),"expected",2000);
        rejects(CALLBACK.replace("#","?"),"expected",2000);
        rejects(CALLBACK.replace("#","?unexpected=1#"),"expected",2000);
    }
    @Test public void rejectsErrorsDuplicateParametersAndMissingPermission(){
        rejects(CALLBACK+"&error=access_denied","expected",2000);
        rejects(CALLBACK+"&state=expected","expected",2000);
        rejects(CALLBACK+"&access_token=other","expected",2000);
        rejects(CALLBACK+"&scope=read_activity","expected",2000);
        rejects(CALLBACK.replace("bearer","other"),"expected",2000);
        rejects(CALLBACK.replace("abc%2B123",""),"expected",2000);
    }
    @Test public void rejectsInvalidOrOverflowingExpiry(){
        for(String seconds:new String[]{"0","-1","nope","9223372036854775807"})
            rejects(CALLBACK.replace("3600",seconds),"expected",2000);
        rejects(CALLBACK.replace("&expires_in=3600",""),"expected",2000);
    }
}
