package com.runzone;

/** Detects each completed kilometre and its split time. */
public final class KmSplits {
    private int completed;
    private long lapStartMs;
    public void reset(){completed=0;lapStartMs=0;}
    /** Returns the split in ms when a new kilometre was just completed, otherwise -1. */
    public long accept(double meters,long activeMs){
        int km=(int)Math.floor(meters/1000);
        if(km<=completed)return -1;
        long split=activeMs-lapStartMs;
        completed=km;lapStartMs=activeMs;
        return split;
    }
    public int completed(){return completed;}
    static String announcement(int km,long splitMs){
        long s=Math.round(splitMs/1000.0);
        return "Kilometre "+km+". "+(s/60)+" minutes "+(s%60)+" seconds.";
    }
}
