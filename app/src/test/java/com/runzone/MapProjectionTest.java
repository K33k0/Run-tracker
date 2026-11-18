package com.runzone;
import org.junit.Test;
import static org.junit.Assert.*;
public class MapProjectionTest {
    @Test public void startingPointAndShortRoutesStayClose(){
        assertEquals(18,MapProjection.routeZoom(0,0,320,240));
        assertEquals(18,MapProjection.routeZoom(600,400,320,240));
    }
    @Test public void routeFitsBothDimensionsAtClosestZoom(){
        for(double[] span:new double[][]{{5000,100},{100,5000},{5000,3000}}){
            int zoom=MapProjection.routeZoom(span[0],span[1],320,240);
            double scale=Math.pow(2,19-zoom);
            assertTrue(span[0]/scale<=320&&span[1]/scale<=240);
            assertTrue(span[0]/(scale/2)>320||span[1]/(scale/2)>240);
        }
    }
    @Test public void positionRoundTripsAtStreetZoom(){
        for(int zoom:new int[]{3,16,19}){
            assertEquals(-0.1276,MapProjection.longitude(MapProjection.x(-0.1276,zoom),zoom),1e-8);
            assertEquals(51.5072,MapProjection.latitude(MapProjection.y(51.5072,zoom),zoom),1e-8);
        }
    }
    @Test public void polesStayFinite(){ assertTrue(Double.isFinite(MapProjection.y(90,19)));assertTrue(Double.isFinite(MapProjection.y(-90,19))); }
}
