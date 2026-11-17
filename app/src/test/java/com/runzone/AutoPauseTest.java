package com.runzone;
import org.junit.Test;
import static org.junit.Assert.*;
public class AutoPauseTest {
    @Test public void optionalAndManualPausesAreRespected(){AutoPause p=new AutoPause();assertEquals(0,p.accept(false,false,false,720,0,0));assertEquals(0,p.accept(false,false,false,720,0,20000));assertEquals(0,p.accept(true,true,false,720,4,30000));assertEquals(0,p.accept(true,true,false,720,4,50000));}
    @Test public void waitsForSustainedSlowAndFastPace(){AutoPause p=new AutoPause();assertEquals(0,p.accept(true,false,false,720,1,0));assertEquals(-1,p.accept(true,false,false,720,1,10000));assertEquals(0,p.accept(true,false,true,720,1.5,11000));assertEquals(0,p.accept(true,false,true,720,2,12000));assertEquals(1,p.accept(true,false,true,720,2,15000));}
    @Test public void lostGpsDoesNotBecomeAStationaryReading(){AutoPause p=new AutoPause();p.accept(true,false,false,720,0,0);assertEquals(0,p.accept(true,false,false,720,Double.NaN,10000));assertEquals(0,p.accept(true,false,false,720,0,11000));}
    @Test public void disablingResumesOnlyAnAutomaticPause(){AutoPause p=new AutoPause();assertEquals(1,p.accept(false,false,true,720,0,0));assertEquals(0,p.accept(false,true,false,720,0,0));}
}
