package com.runzone;
import org.junit.Test;
import static org.junit.Assert.*;
public class RouteDistanceTest {
    @Test public void slowOneMetreUpdatesAccumulateInsteadOfBeingDiscarded(){RouteDistance d=new RouteDistance();double total=0;for(int i=0;i<=20;i++)total+=d.accept(i/111195d,0,i*1000L);assertEquals(18,total,.1);}
    @Test public void pauseResetDoesNotConnectTwoLocations(){RouteDistance d=new RouteDistance();d.accept(51,0,0);d.reset();assertEquals(0,d.accept(52,0,10000),0);}
    @Test public void staleFixesAndImplausibleJumpsDoNotAddDistance(){RouteDistance d=new RouteDistance();d.accept(51,0,0);assertEquals(0,d.accept(52,0,1000),0);assertEquals(0,d.accept(52.001,0,20000),0);}
}
