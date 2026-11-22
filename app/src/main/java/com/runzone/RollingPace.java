package com.runzone;

import java.util.ArrayDeque;

/** Pace over the last stretch of active running, smoother than a single GPS speed reading. */
public final class RollingPace {
    static final long WINDOW_MS=30_000, MIN_SPAN_MS=10_000, STALE_MS=10_000;
    static final double MIN_METERS=10;
    private final ArrayDeque<double[]> samples=new ArrayDeque<>();
    public void reset(){samples.clear();}
    public void accept(long activeMs,double meters){
        if(!samples.isEmpty()&&activeMs<=samples.getLast()[0])return;
        samples.addLast(new double[]{activeMs,meters});
        while(samples.size()>2&&activeMs-samples.getFirst()[0]>WINDOW_MS)samples.removeFirst();
    }
    /** Seconds per km, or -1 when there is not enough recent movement to say. */
    public long secondsPerKm(long nowActiveMs){
        if(samples.size()<2)return -1;
        double[] first=samples.getFirst(),last=samples.getLast();
        if(nowActiveMs-last[0]>STALE_MS)return -1;
        double span=last[0]-first[0],meters=last[1]-first[1];
        if(span<MIN_SPAN_MS||meters<MIN_METERS)return -1;
        long pace=Math.round(span/meters);
        return pace>=60&&pace<=3600?pace:-1;
    }
}
