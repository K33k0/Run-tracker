package com.runzone;

import static org.junit.Assert.*;
import org.junit.Test;

public class KmSplitsTest {
    @Test public void reportsEachKilometreOnce(){
        KmSplits k=new KmSplits();
        assertEquals(-1,k.accept(999,290_000));
        assertEquals(300_000,k.accept(1000.5,300_000));
        assertEquals(-1,k.accept(1500,450_000));
        assertEquals(310_000,k.accept(2001,610_000));
        assertEquals(2,k.completed());
    }
    @Test public void announcementText(){
        assertEquals("Kilometre 3. 5 minutes 42 seconds.",KmSplits.announcement(3,342_000));
    }
}
