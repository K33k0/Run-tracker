package com.runzone;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Saved-run reader, including recordings made before the version 2 schema. */
public final class RunRecord {
    public static final class Point {
        public final double lat,lon,distance;
        public final long time,activeMs;
        public final int bpm,track;
        public final Double altitude;
        public Point(double lat,double lon,long time,long activeMs,double distance,int bpm,int track,Double altitude){this.lat=lat;this.lon=lon;this.time=time;this.activeMs=activeMs;this.distance=distance;this.bpm=bpm;this.track=track;this.altitude=altitude;}
    }
    public static final class Pulse {
        public final long time,activeMs;
        public final int bpm,track;
        public Pulse(long time,long activeMs,int bpm,int track){this.time=time;this.activeMs=activeMs;this.bpm=bpm;this.track=track;}
    }
    public static final class Split {
        public final double meters;
        public final long durationMs;
        public Split(double meters,long durationMs){this.meters=meters;this.durationMs=durationMs;}
    }
    public final long startTime,endTime,durationMs,elapsedMs;
    public final double meters;
    public final int low,high;
    public final boolean legacy;
    public final List<Point> points;
    public final List<Pulse> pulses;
    public RunRecord(long start,long end,long duration,double distance,int low,int high,boolean legacy,List<Point> points,List<Pulse> pulses){
        startTime=start;endTime=Math.max(start,end);durationMs=Math.max(0,duration);elapsedMs=Math.max(durationMs,endTime-startTime);meters=Math.max(0,distance);this.low=low;this.high=high;this.legacy=legacy;
        this.points=Collections.unmodifiableList(new ArrayList<>(points));this.pulses=Collections.unmodifiableList(new ArrayList<>(pulses));
    }
    public static RunRecord read(File file)throws IOException,JSONException {return fromJson(new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)));}
    public static RunRecord fromJson(JSONObject json)throws JSONException {
        long duration=Math.max(0,json.getLong("duration")),date=json.getLong("date");double meters=json.getDouble("meters");
        if(!Double.isFinite(meters)||meters<0)throw new JSONException("Invalid distance");
        boolean legacy=!json.has("startTime");JSONArray route=json.optJSONArray("route");
        long first=route!=null&&route.length()>0?route.getJSONObject(0).optLong("time",date-duration):date-duration;
        long start=json.optLong("startTime",Math.min(first,date)),end=json.optLong("endTime",date);
        ArrayList<Point> points=new ArrayList<>();int track=0;double path=0;Point previous=null;
        if(route!=null)for(int i=0;i<route.length();i++){
            JSONObject p=route.getJSONObject(i);double lat=p.optDouble("lat",Double.NaN),lon=p.optDouble("lon",Double.NaN);long time=p.optLong("time",0);
            if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180||time<=0)continue;
            if(p.optBoolean("segment",false)||(legacy&&previous!=null&&time-previous.time>15000))track++;
            track=p.optInt("track",track);
            if(previous!=null&&previous.track==track)path+=distance(previous.lat,previous.lon,lat,lon);
            double cumulative=p.has("distanceMeters")?Math.max(0,Math.min(meters,p.optDouble("distanceMeters",0))):path;
            Double altitude=p.has("altitude")?p.optDouble("altitude",Double.NaN):null;if(altitude!=null&&!Double.isFinite(altitude))altitude=null;
            Point point=new Point(lat,lon,time,p.optLong("activeMs",-1),cumulative,p.optInt("bpm",-1),track,altitude);points.add(point);previous=point;
        }
        if(legacy){ArrayList<Point> scaled=new ArrayList<>();for(Point p:points)scaled.add(new Point(p.lat,p.lon,p.time,p.activeMs,path>0?p.distance/path*meters:0,p.bpm,p.track,p.altitude));points=scaled;}
        points.sort(Comparator.comparingLong(p->p.time));
        ArrayList<Pulse> pulses=new ArrayList<>();JSONArray heart=json.optJSONArray("heartRate");
        if(heart!=null){for(int i=0;i<heart.length();i++){JSONObject p=heart.getJSONObject(i);int bpm=p.optInt("bpm",-1);long time=p.optLong("time",0);if(validBpm(bpm)&&time>0)pulses.add(new Pulse(time,p.optLong("activeMs",-1),bpm,p.optInt("track",0)));}}
        else for(Point p:points)if(validBpm(p.bpm))pulses.add(new Pulse(p.time,p.activeMs,p.bpm,p.track));
        pulses.sort(Comparator.comparingLong(p->p.time));
        return new RunRecord(start,end,duration,meters,json.optInt("zoneLow",0),json.optInt("zoneHigh",0),legacy,points,pulses);
    }
    public static boolean validBpm(int value){return value>0&&value<=255;}
    public int averageBpm(){long sum=0;for(Pulse p:pulses)sum+=p.bpm;return pulses.isEmpty()?0:(int)Math.round(sum/(double)pulses.size());}
    public int maxBpm(){int max=0;for(Pulse p:pulses)max=Math.max(max,p.bpm);return max;}
    public List<Split> splits(){
        ArrayList<Split> splits=new ArrayList<>();if(legacy||points.isEmpty()||meters<=0)return splits;
        double previousDistance=0,next=1000;long previousTime=0,lastSplitTime=0;
        for(Point p:points){if(p.activeMs<0)return Collections.emptyList();
            if(p.distance>previousDistance){while(next<=p.distance&&next<=meters){long at=previousTime+Math.round((p.activeMs-previousTime)*(next-previousDistance)/(p.distance-previousDistance));splits.add(new Split(1000,Math.max(0,at-lastSplitTime)));lastSplitTime=at;next+=1000;}}
            previousDistance=Math.max(previousDistance,p.distance);previousTime=Math.max(previousTime,p.activeMs);
        }
        double remaining=meters-(next-1000);if(remaining>=1)splits.add(new Split(remaining,Math.max(0,durationMs-lastSplitTime)));return splits;
    }
    static double distance(double a,double b,double c,double d){double lat=Math.toRadians(c-a),lon=Math.toRadians(d-b);double h=Math.sin(lat/2)*Math.sin(lat/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.sin(lon/2)*Math.sin(lon/2);return 6371000*2*Math.asin(Math.min(1,Math.sqrt(h)));}
}
