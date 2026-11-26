package com.runzone;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ShareCardTest {
    @Test public void statsLeadWithTimeAndPaceAndCapAtSix()throws Exception{
        RunRecord run=RunRecord.fromJson(RunAnalysisTest.run(40,s->140,s->s*0.01,false));
        List<ShareCard.Stat> stats=ShareCard.stats(run,new RunAnalysis(run));
        assertEquals("TIME",stats.get(0).label);assertEquals("40:00",stats.get(0).value);
        assertEquals("PACE",stats.get(1).label);assertEquals("5:33",stats.get(1).value);
        assertEquals("AVG HR",stats.get(2).label);assertEquals(6,stats.size());
    }

    @Test public void statsSkipMissingData()throws Exception{
        RunRecord run=RunRecord.fromJson(new org.json.JSONObject("{\"date\":1700000000000,\"startTime\":1700000000000,\"duration\":600000,\"meters\":0,\"route\":[]}"));
        List<ShareCard.Stat> stats=ShareCard.stats(run,new RunAnalysis(run));
        assertEquals(2,stats.size());assertEquals("—",stats.get(1).value);
    }

    @Test public void highlightPicksLongestBestEffortThatFits()throws Exception{
        RunRecord run=RunRecord.fromJson(RunAnalysisTest.run(30,s->140,null,false));
        assertEquals("Fastest 5 km in this run · 27:47",ShareCard.highlight(run,new RunAnalysis(run)));
    }

    @Test public void routeKeepsAspectAndFitsBox(){
        List<RunRecord.Point> pts=Arrays.asList(new RunRecord.Point(0,0,1,0,0,-1,0,null),new RunRecord.Point(0,0.01,2,1,0,-1,0,null),new RunRecord.Point(0.005,0.01,3,2,0,-1,1,null));
        float[][] r=ShareCard.fitRoute(pts,0,0,1000,1000);
        assertEquals(0,r[0][0],0.5);assertEquals(1000,r[1][0],0.5);
        assertEquals(500,r[0][1]-r[2][1],1);
        assertEquals(1,r[2][2],0);
        assertNull(ShareCard.fitRoute(pts.subList(0,1),0,0,100,100));
    }
}
