package com.runzone;
import org.junit.Test;
import static org.junit.Assert.*;
public class ZoneCoachTest {
    @Test public void previewNeverWarns(){ZoneCoach c=new ZoneCoach();for(int t=0;t<60000;t+=1000)assertEquals(0,c.accept(false,180,120,150,t));}
    @Test public void runWaitsFiveSecondsThenRepeatsEveryTwenty(){ZoneCoach c=new ZoneCoach();assertEquals(0,c.accept(true,160,120,150,0));assertEquals(0,c.accept(true,160,120,150,4999));assertEquals(1,c.accept(true,160,120,150,5000));for(int t=6000;t<25000;t+=1000)assertEquals(0,c.accept(true,160,120,150,t));assertEquals(1,c.accept(true,160,120,150,25000));}
    @Test public void pauseAndStaleDataRestartTheDelay(){ZoneCoach c=new ZoneCoach();c.accept(true,100,120,150,0);c.accept(false,100,120,150,4000);assertEquals(0,c.accept(true,100,120,150,5000));assertEquals(-1,c.accept(true,100,120,150,10000));assertEquals(0,c.accept(true,100,120,150,30000));assertEquals(0,c.accept(true,100,120,150,34000));assertEquals(-1,c.accept(true,100,120,150,35000));}
    @Test public void returningInsideTargetCancelsPendingAlert(){ZoneCoach c=new ZoneCoach();c.accept(true,160,120,150,0);c.accept(true,150,120,150,4000);assertEquals(0,c.accept(true,160,120,150,5000));assertEquals(1,c.accept(true,160,120,150,10000));}
}
