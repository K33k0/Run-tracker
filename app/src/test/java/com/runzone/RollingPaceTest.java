package com.runzone;

import static org.junit.Assert.*;
import org.junit.Test;

public class RollingPaceTest {
    @Test public void steadyFiveMinutePace(){
        RollingPace p=new RollingPace();
        for(int s=0;s<=60;s++)p.accept(s*1000L,s*(1000/300.0));
        assertEquals(300,p.secondsPerKm(60_000));
    }
    @Test public void usesOnlyRecentWindow(){
        RollingPace p=new RollingPace();
        for(int s=0;s<=60;s++)p.accept(s*1000L,s*(1000/600.0)); // 10:00/km
        double m=60*(1000/600.0);
        for(int s=61;s<=120;s++){m+=1000/240.0;p.accept(s*1000L,m);} // 4:00/km
        assertEquals(240,p.secondsPerKm(120_000));
    }
    @Test public void unknownWhenStaleShortOrStill(){
        RollingPace p=new RollingPace();
        assertEquals(-1,p.secondsPerKm(0));
        p.accept(0,0);p.accept(5000,20);
        assertEquals(-1,p.secondsPerKm(5000)); // span too short
        p.accept(20000,8);
        assertEquals(-1,p.secondsPerKm(20000)); // barely moving
        p.reset();p.accept(0,0);p.accept(20000,80);
        assertEquals(-1,p.secondsPerKm(40000)); // stale
    }
}
