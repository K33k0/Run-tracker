package com.runzone;

import java.io.*;
import java.time.Instant;
import java.util.*;

/** TCX v2, shared by file exports and the automatic upload queue. */
public final class TcxExporter {
    private TcxExporter(){}
    private static final class Sample {long time;RunRecord.Point point;int bpm=-1;}
    public static void write(RunRecord run,Writer out)throws IOException {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><Activities><Activity Sport=\"Running\">");
        tag(out,"Id",time(run.startTime));out.write("<Lap StartTime=\""+time(run.startTime)+"\">");
        tag(out,"TotalTimeSeconds",number(run.durationMs/1000d));tag(out,"DistanceMeters",number(run.meters));tag(out,"Calories","0");
        if(run.averageBpm()>0){heart(out,"AverageHeartRateBpm",run.averageBpm());heart(out,"MaximumHeartRateBpm",run.maxBpm());}
        tag(out,"Intensity","Active");tag(out,"TriggerMethod","Manual");
        TreeMap<Integer,TreeMap<Long,Sample>> tracks=new TreeMap<>();
        for(RunRecord.Point point:run.points){Sample sample=sample(tracks,point.track,point.time);sample.point=point;if(RunRecord.validBpm(point.bpm))sample.bpm=point.bpm;}
        for(RunRecord.Pulse pulse:run.pulses)sample(tracks,pulse.track,pulse.time).bpm=pulse.bpm;
        double cumulative=0;
        for(TreeMap<Long,Sample> track:tracks.values()){
            out.write("<Track>");
            for(Sample sample:track.values()){
                out.write("<Trackpoint>");tag(out,"Time",time(sample.time));
                if(sample.point!=null){RunRecord.Point p=sample.point;out.write("<Position>");tag(out,"LatitudeDegrees",String.format(Locale.US,"%.7f",p.lat));tag(out,"LongitudeDegrees",String.format(Locale.US,"%.7f",p.lon==180?-180:p.lon));out.write("</Position>");if(p.altitude!=null)tag(out,"AltitudeMeters",number(p.altitude));cumulative=Math.max(cumulative,p.distance);}
                tag(out,"DistanceMeters",number(cumulative));if(RunRecord.validBpm(sample.bpm))heart(out,"HeartRateBpm",sample.bpm);out.write("</Trackpoint>");
            }
            out.write("</Track>");
        }
        out.write("</Lap>");tag(out,"Notes","Recorded with RunZone. Active duration excludes pauses. Calories are not measured."+(run.legacy?" Start time is estimated for this older recording.":""));
        application(out,"Creator");out.write("</Activity></Activities>");application(out,"Author");out.write("</TrainingCenterDatabase>");
    }
    private static Sample sample(TreeMap<Integer,TreeMap<Long,Sample>> tracks,int track,long time){TreeMap<Long,Sample> samples=tracks.computeIfAbsent(track,k->new TreeMap<>());return samples.computeIfAbsent(time,k->{Sample sample=new Sample();sample.time=time;return sample;});}
    private static String time(long time){return Instant.ofEpochMilli(time).toString();}
    private static String number(double number){return String.format(Locale.US,"%.3f",number);}
    private static void tag(Writer out,String tag,String value)throws IOException{out.write("<"+tag+">"+value+"</"+tag+">");}
    private static void heart(Writer out,String name,int bpm)throws IOException{out.write("<"+name+"><Value>"+bpm+"</Value></"+name+">");}
    private static void application(Writer out,String tag)throws IOException{out.write("<"+tag+" xsi:type=\"Application_t\"><Name>RunZone</Name><Build><Version><VersionMajor>1</VersionMajor><VersionMinor>2</VersionMinor><BuildMajor>0</BuildMajor><BuildMinor>0</BuildMinor></Version></Build><LangID>en</LangID><PartNumber>RZN-00012-00</PartNumber></"+tag+">");}
}
