package com.runzone;

/** Decides when to alert; preview readings never trigger run coaching. */
public final class ZoneCoach {
    private long outsideSince=-1,lastAlert=-1,lastMeasurement=-1;
    private int previousZone;
    public void reset(){outsideSince=-1;lastAlert=-1;lastMeasurement=-1;previousZone=0;}
    public int accept(boolean running,int bpm,int low,int high,long now){
        if(lastMeasurement>=0&&now-lastMeasurement>10000)outsideSince=-1;
        lastMeasurement=now;
        if(!running||bpm<=0){outsideSince=-1;previousZone=0;return 0;}
        int zone=HeartRate.zone(bpm,low,high);
        if(zone==0){outsideSince=-1;previousZone=0;return 0;}
        if(outsideSince<0||previousZone!=zone){outsideSince=now;previousZone=zone;}
        if(now-outsideSince>=5000&&(lastAlert<0||now-lastAlert>=20000)){lastAlert=now;return zone;}
        return 0;
    }
}
