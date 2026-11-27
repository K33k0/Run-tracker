package com.runzone;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RunSummaryTest {
    static List<RunRecord.Split> km(long... seconds){ArrayList<RunRecord.Split> s=new ArrayList<>();for(long v:seconds)s.add(new RunRecord.Split(1000,v*1000));return s;}

    @Test public void describesPacing(){
        assertTrue(RunSummary.pacing(km(300,302,299,301)).startsWith("You paced this evenly"));
        assertTrue(RunSummary.pacing(km(320,315,300,295)).startsWith("You ran a negative split"));
        assertTrue(RunSummary.pacing(km(290,300,320,330)).startsWith("You slowed"));
        assertNull(RunSummary.pacing(km(300,300)));
    }

    @Test public void steadyRunMentionsDriftAndZone()throws Exception{
        RunAnalysis a=new RunAnalysis(RunRecord.fromJson(RunAnalysisTest.run(40,s->138,null,false)));
        List<String> lines=RunSummary.sentences(a);
        assertTrue(lines.stream().anyMatch(l->l.contains("held your target")));
        assertTrue(lines.stream().anyMatch(l->l.contains("stayed in step")));
    }
}
