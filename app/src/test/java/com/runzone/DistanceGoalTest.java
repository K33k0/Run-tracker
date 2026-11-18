package com.runzone;

import org.junit.Test;
import static org.junit.Assert.*;

public class DistanceGoalTest {
    @Test public void alertsOnceAtOrBeyondTargetAndContinuesAcceptingDistance(){
        DistanceGoal goal=new DistanceGoal();goal.reset(5000);
        assertFalse(goal.accept(4999.9));assertTrue(goal.accept(5000));
        assertTrue(goal.reached());assertFalse(goal.accept(5100));assertFalse(goal.accept(10000));
        assertEquals(5000,goal.targetMeters());
    }
    @Test public void handlesCrossingBetweenFixesAndResetsForNextRun(){
        DistanceGoal goal=new DistanceGoal();goal.reset(5000);assertTrue(goal.accept(5008));
        goal.reset(10000);assertFalse(goal.reached());assertFalse(goal.accept(5008));assertTrue(goal.accept(10010));
        goal.reset(5000);assertTrue(goal.accept(5001));
    }
    @Test public void disabledOrInvalidTargetsAndMeasurementsDoNotAlert(){
        DistanceGoal goal=new DistanceGoal();
        for(int target:new int[]{0,-1,1_000_001}){goal.reset(target);assertFalse(goal.accept(5000));}
        goal.reset(5000);assertFalse(goal.accept(Double.NaN));assertFalse(goal.accept(Double.POSITIVE_INFINITY));assertFalse(goal.accept(-1));assertFalse(goal.reached());
    }
}
