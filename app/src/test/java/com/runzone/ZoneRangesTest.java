package com.runzone;

import org.junit.Test;
import static org.junit.Assert.*;

public class ZoneRangesTest {
    @Test public void everySupportedBpmBelongsToExactlyOneZone(){
        verifyPartition(ZoneRanges.defaults());
        verifyPartition(new int[]{30,31,32,239});
    }
    private void verifyPartition(int[] ends){
        assertTrue(ZoneRanges.valid(ends));
        for(int bpm=ZoneRanges.MIN;bpm<=ZoneRanges.MAX;bpm++){
            int matches=0;
            for(int zone=0;zone<5;zone++)if(bpm>=ZoneRanges.low(ends,zone)&&bpm<=ZoneRanges.high(ends,zone))matches++;
            assertEquals("BPM "+bpm,1,matches);
        }
    }
    @Test public void rejectsEmptyReversedAndOutOfBoundsZones(){
        assertFalse(ZoneRanges.valid(new int[]{99,99,150,170}));
        assertFalse(ZoneRanges.valid(new int[]{120,100,150,170}));
        assertFalse(ZoneRanges.valid(new int[]{29,119,150,170}));
        assertFalse(ZoneRanges.valid(new int[]{99,119,150,240}));
    }
    @Test public void migrationPreservesFeasibleActiveTargetsAndAlwaysPartitions(){
        for(int zone=0;zone<5;zone++)for(int low=30;low<=240;low+=7)for(int high=low;high<=240;high+=11){
            int[] ends=ZoneRanges.migrate(zone,low,high);verifyPartition(ends);
            if(low>=30+zone&&high<=240-(4-zone)&&(zone!=0||low==30)&&(zone!=4||high==240)){
                assertEquals(low,ZoneRanges.low(ends,zone));assertEquals(high,ZoneRanges.high(ends,zone));
            }
        }
    }
}
