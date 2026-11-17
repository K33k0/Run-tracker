package com.runzone;
import org.junit.Test;
import static org.junit.Assert.*;
public class HeartRateTest {
    @Test public void decodesStandardMeasurements() {
        assertEquals(150, HeartRate.decode(new byte[]{0, (byte)150}));
        assertEquals(300, HeartRate.decode(new byte[]{1, 44, 1}));
        assertEquals(-1, HeartRate.decode(new byte[]{1, 44}));
        assertEquals(-1, HeartRate.decode(new byte[]{4, 90}));
    }
    @Test public void boundariesAreInclusive() {
        assertEquals(-1, HeartRate.zone(119,120,150));
        assertEquals(0, HeartRate.zone(120,120,150));
        assertEquals(0, HeartRate.zone(150,120,150));
        assertEquals(1, HeartRate.zone(151,120,150));
    }
}
