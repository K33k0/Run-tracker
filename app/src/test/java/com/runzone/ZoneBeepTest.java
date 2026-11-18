package com.runzone;
import org.junit.Test;
import static org.junit.Assert.*;
public class ZoneBeepTest {
    @Test public void followsBpmAndChangesDirection(){
        ZoneBeep b=new ZoneBeep();
        assertEquals(1,b.accept(true,180,120,150,0));
        assertEquals(0,b.accept(true,180,120,150,332));
        assertEquals(1,b.accept(true,180,120,150,333));
        assertEquals(-1,b.accept(true,100,120,150,400));
        assertEquals(0,b.accept(true,100,120,150,999));
        assertEquals(-1,b.accept(true,100,120,150,1000));
    }
    @Test public void stopsAtInclusiveLimitsAndWhenDisabledOrMissing(){
        ZoneBeep b=new ZoneBeep();
        assertEquals(1,b.accept(true,180,120,150,0));
        assertEquals(0,b.accept(true,150,120,150,100));
        assertEquals(0,b.accept(true,120,120,150,200));
        assertEquals(0,b.accept(false,180,120,150,400));
        assertEquals(0,b.accept(true,-1,120,150,500));
        assertEquals(1,b.accept(true,180,120,150,600));
    }
}
