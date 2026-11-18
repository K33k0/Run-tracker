package com.runzone;

/** BPM-paced warnings; sensor measurements provide cadence, not actual beat timing. */
public final class ZoneBeep {
    private long next=-1;
    private int previousZone;
    public void reset(){next=-1;previousZone=0;}
    public int accept(boolean enabled,int bpm,int low,int high,long now){
        int zone=enabled&&RunRecord.validBpm(bpm)?HeartRate.zone(bpm,low,high):0;
        if(zone==0){reset();return 0;}
        if(zone!=previousZone){next=-1;previousZone=zone;}
        if(next<0||now>=next){next=now+Math.max(1,Math.round(60000.0/bpm));return zone;}
        return 0;
    }
}
