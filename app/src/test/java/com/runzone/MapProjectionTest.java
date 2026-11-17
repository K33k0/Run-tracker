package com.runzone;
import org.junit.Test;
import static org.junit.Assert.*;
public class MapProjectionTest {
    @Test public void positionRoundTripsAtStreetZoom(){
        for(int zoom:new int[]{3,16,19}){
            assertEquals(-0.1276,MapProjection.longitude(MapProjection.x(-0.1276,zoom),zoom),1e-8);
            assertEquals(51.5072,MapProjection.latitude(MapProjection.y(51.5072,zoom),zoom),1e-8);
        }
    }
    @Test public void polesStayFinite(){ assertTrue(Double.isFinite(MapProjection.y(90,19)));assertTrue(Double.isFinite(MapProjection.y(-90,19))); }
}
