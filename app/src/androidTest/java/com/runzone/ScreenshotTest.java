package com.runzone;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import androidx.test.services.storage.TestStorage;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.Assert.*;

/**
 * Seeds realistic runs, opens each main screen, checks the analysis sections render, and saves
 * screenshots (scrolling down the page) through the test storage service, which Gradle copies to
 * build/outputs/connected_android_test_additional_output.
 */
@RunWith(AndroidJUnit4.class)
public class ScreenshotTest {
    @Rule public GrantPermissionRule permissions=GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS,Manifest.permission.ACTIVITY_RECOGNITION);

    private Context context;

    @Before public void seed()throws Exception{
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File runs=new File(context.getFilesDir(),"runs");deleteTree(runs);runs.mkdirs();
        new File(context.getFilesDir(),"run-draft.json").delete();
        long day=86400000L,now=System.currentTimeMillis();Random random=new Random(7);
        for(int i=20;i>=1;i--){
            long start=now-i*day*2-3600000L;
            JSONObject run=run(start,i%4==0?55:35,i%3==0?158:146,random,i%2==0);
            write(new File(runs,(start+3000000L)+".json"),run.toString());
        }
        JSONArray checks=new JSONArray();
        for(int i=8;i>=0;i--)checks.put(new JSONObject().put("time",now-i*day-2*3600000L).put("rmssd",55+random.nextGaussian()*5).put("restingBpm",50+random.nextInt(4)));
        write(new File(context.getFilesDir(),"hrv-checks.json"),checks.toString());
        context.getSharedPreferences("settings",0).edit().clear().putBoolean("zoneBeeps",true).apply();
    }

    /** A hilly out-and-back with heart rate, beat intervals, cadence and post-finish recovery. */
    static JSONObject run(long start,int minutes,int bpm,Random random,boolean hills)throws JSONException{
        int seconds=minutes*60;JSONArray route=new JSONArray(),heart=new JSONArray(),cadence=new JSONArray(),recovery=new JSONArray();
        double meters=0;
        for(int s=0;s<=seconds;s+=2){
            double grade=hills?0.04*Math.sin(s/300.0):0,speed=3.1-grade*8+random.nextGaussian()*0.05;meters+=speed*2;
            double angle=meters/(seconds*3.0)*Math.PI*2;
            route.put(new JSONObject().put("lat",51.5+0.006*Math.sin(angle)).put("lon",-0.12+0.009*(1-Math.cos(angle))).put("time",start+s*1000L).put("activeMs",s*1000L)
                .put("distanceMeters",meters).put("altitude",40+(hills?60*(1-Math.cos(s/300.0)):0)+random.nextGaussian()*0.8).put("speedMps",speed).put("track",0));
            cadence.put(new JSONObject().put("time",start+s*1000L).put("track",0).put("spm",172+random.nextInt(6)));
        }
        double alphaNoise=0;
        for(int s=0;s<=seconds;s++){
            int b=(int)Math.round(bpm-12*Math.exp(-s/240.0)+s/(double)seconds*6+random.nextGaussian());
            JSONArray ticks=new JSONArray();double ms=60000.0/b;
            for(int k=0;k<b/60+(random.nextInt(60)<b%60?1:0);k++){alphaNoise=alphaNoise*0.6+random.nextGaussian()*6;ticks.put((int)Math.round((ms+alphaNoise)*1.024));}
            heart.put(new JSONObject().put("time",start+s*1000L).put("activeMs",s*1000L).put("bpm",b).put("track",0).put("rrTicks",ticks));
        }
        long end=start+seconds*1000L;
        for(int s=0;s<=120;s+=1)recovery.put(new JSONObject().put("time",end+s*1000L).put("bpm",(int)Math.round(bpm-28*(1-Math.exp(-s/45.0)))));
        return new JSONObject().put("schemaVersion",2).put("date",start).put("startTime",start).put("endTime",end).put("duration",seconds*1000L).put("meters",meters)
            .put("route",route).put("heartRate",heart).put("cadence",cadence).put("recovery",recovery).put("timerOffsets",new JSONArray()).put("zoneLow",120).put("zoneHigh",150);
    }

    @Test public void screens()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            settle();capture(scenario,"1-run",1);
            call(scenario,"show",String.class,"History");waitForText(scenario,"FITNESS");capture(scenario,"2-history",4);
            File[] runs=new File(context.getFilesDir(),"runs").listFiles();Arrays.sort(runs);
            call(scenario,"openRun",File.class,runs[runs.length-1]);waitForText(scenario,"runs, oldest first");
            for(String section:new String[]{"EFFICIENCY","PACE & TERRAIN","TIME IN ZONE","STRIDE","HEART-RATE VARIABILITY","HEART-RATE RECOVERY","BEST EFFORTS IN THIS RUN","SAME ROUTE"})
                assertTrue("Missing section "+section,hasText(scenario,section));
            capture(scenario,"3-detail",9);
            call(scenario,"show",String.class,"Settings");settle();
            for(String section:new String[]{"YOUR HEART","DURING A RUN","YOUR DATA","SENSOR & PHONE"})assertTrue("Missing section "+section,hasText(scenario,section));
            capture(scenario,"4-settings",5);
        }
    }

    private static void call(ActivityScenario<MainActivity> scenario,String name,Class<?> type,Object arg){
        scenario.onActivity(a->{try{Method m=MainActivity.class.getDeclaredMethod(name,type);m.setAccessible(true);m.invoke(a,arg);}catch(Exception e){throw new RuntimeException(e);}});
        settle();
    }
    private static void settle(){InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(600);}
    private static void waitForText(ActivityScenario<MainActivity> scenario,String text){
        long until=SystemClock.uptimeMillis()+30000;
        while(SystemClock.uptimeMillis()<until){if(hasText(scenario,text)){settle();return;}SystemClock.sleep(250);}
        fail("Timed out waiting for "+text);
    }
    private static boolean hasText(ActivityScenario<MainActivity> scenario,String text){
        boolean[] found={false};scenario.onActivity(a->found[0]=find(a.getWindow().getDecorView(),text));return found[0];
    }
    private static boolean find(View v,String text){
        if(v instanceof TextView&&((TextView)v).getText().toString().contains(text))return true;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(find(((ViewGroup)v).getChildAt(i),text))return true;
        return false;
    }
    private static ScrollView scroller(View v){
        if(v instanceof ScrollView)return (ScrollView)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){ScrollView s=scroller(((ViewGroup)v).getChildAt(i));if(s!=null)return s;}
        return null;
    }
    /** Saves up to {@code pages} screenshots, scrolling one screen each time. */
    private void capture(ActivityScenario<MainActivity> scenario,String name,int pages)throws IOException{
        scenario.onActivity(a->{ScrollView s=scroller(a.getWindow().getDecorView());if(s!=null)s.scrollTo(0,0);});settle();
        for(int page=1;page<=pages;page++){
            Bitmap shot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull(shot);
            try(OutputStream out=new TestStorage().openOutputFile("screenshots/"+name+"-"+page+".png")){shot.compress(Bitmap.CompressFormat.PNG,100,out);}
            boolean[] moved={false};
            scenario.onActivity(a->{ScrollView s=scroller(a.getWindow().getDecorView());if(s!=null){int before=s.getScrollY();s.scrollBy(0,(int)(s.getHeight()*0.85));moved[0]=s.getScrollY()!=before;}});
            if(!moved[0])break;settle();
        }
    }
    private static void write(File f,String text)throws IOException{try(OutputStream o=new FileOutputStream(f)){o.write(text.getBytes("UTF-8"));}}
    private static void deleteTree(File f){File[] c=f.listFiles();if(c!=null)for(File x:c)deleteTree(x);f.delete();}
}
