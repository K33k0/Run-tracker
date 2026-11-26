package com.runzone;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TrainingLoadTest {
    static final TimeZone UTC=TimeZone.getTimeZone("UTC");
    static final long NOW=1700000000000L/TrainingLoad.DAY*TrainingLoad.DAY+12*3600000L;

    @Test public void trimpGrowsWithIntensityAndDuration(){
        double[] easy=new double[3600],hard=new double[3600];Arrays.fill(easy,130);Arrays.fill(hard,170);
        double e=TrainingLoad.trimp(easy,60,190),h=TrainingLoad.trimp(hard,60,190);
        // An hour at 130 bpm with 60 rest / 190 max is roughly 58 TRIMP: 60 min x 0.54 x 0.64e^(1.92 x 0.54).
        assertEquals(58.1,e,0.5);assertTrue(h>e*1.8);
        assertEquals(e/2,TrainingLoad.trimp(Arrays.copyOf(easy,1800),60,190),0.5);
        assertEquals(0,TrainingLoad.trimp(easy,60,70),0);
    }

    @Test public void fitnessAndFatigueFollowLoad(){
        ArrayList<long[]> runs=new ArrayList<>();ArrayList<Double> loads=new ArrayList<>();
        for(int d=60;d>=1;d--){runs.add(new long[]{NOW-d*TrainingLoad.DAY});loads.add(100.0);}
        List<TrainingLoad.Day> days=TrainingLoad.days(runs,loads,NOW,30,UTC);
        assertEquals(30,days.size());TrainingLoad.Day yesterday=days.get(28),today=days.get(29);
        assertEquals(100,yesterday.load,0);assertEquals(0,today.load,0);
        assertTrue(yesterday.fatigue>yesterday.fitness);assertTrue(yesterday.fitness>60);
        // Form going into today is yesterday's fitness minus fatigue.
        assertEquals(yesterday.fitness-yesterday.fatigue,today.form,1e-9);
        assertTrue(today.form<0);
    }

    @Test public void restDaysMakeYouFresh(){
        ArrayList<long[]> runs=new ArrayList<>();ArrayList<Double> loads=new ArrayList<>();
        for(int d=60;d>=14;d--){runs.add(new long[]{NOW-d*TrainingLoad.DAY});loads.add(100.0);}
        TrainingLoad.Day today=TrainingLoad.days(runs,loads,NOW,30,UTC).get(29);
        assertTrue(today.form>5);
        assertEquals("Fresh",TrainingLoad.readiness(today,Collections.emptyList(),NOW,0).title);
    }

    @Test public void lowMorningHrvOverridesForm(){
        ArrayList<long[]> runs=new ArrayList<>();ArrayList<Double> loads=new ArrayList<>();
        for(int d=60;d>=14;d--){runs.add(new long[]{NOW-d*TrainingLoad.DAY});loads.add(100.0);}
        TrainingLoad.Day today=TrainingLoad.days(runs,loads,NOW,30,UTC).get(29);
        ArrayList<TrainingLoad.Check> checks=new ArrayList<>();
        for(int d=7;d>=1;d--)checks.add(new TrainingLoad.Check(NOW-d*TrainingLoad.DAY,60+d%3,50));
        checks.add(new TrainingLoad.Check(NOW-3600000L,35,58));
        TrainingLoad.Readiness r=TrainingLoad.readiness(today,checks,NOW,0);
        assertEquals(-1,r.level);assertTrue(r.advice.contains("below your normal"));
        checks.set(checks.size()-1,new TrainingLoad.Check(NOW-3600000L,61,50));
        assertEquals(1,TrainingLoad.readiness(today,checks,NOW,0).level);
    }

    @Test public void suggestedZonesFromMaxOrThreshold(){
        assertArrayEquals(new int[]{114,133,152,171},TrainingLoad.suggestZones(190,-1));
        int[] z=TrainingLoad.suggestZones(190,150);
        assertEquals(150,z[1]);assertTrue(ZoneRanges.valid(z));assertTrue(z[3]<190);
        assertNull(TrainingLoad.suggestZones(100,-1));
    }

    @Test public void aerobicThresholdFromAlphaCrossing()throws Exception{
        // Build a run whose steady windows step up in heart rate; alpha falls linearly with HR.
        RunAnalysis a=new RunAnalysis(RunRecord.fromJson(RunAnalysisTest.run(30,s->140+(s/120)*2,null,true)));
        assertTrue(a.hrvWindows.size()>=6);
        // Synthetic RR is white noise (alpha ~0.5), so no crossing: threshold unavailable.
        assertEquals(-1,TrainingLoad.aerobicThreshold(Collections.singletonList(a)));
    }

    @Test public void observedMaxIgnoresSingleSpikes()throws Exception{
        RunAnalysis a=new RunAnalysis(RunRecord.fromJson(RunAnalysisTest.run(10,s->s==300?230:(s>=400&&s<420?182:150),null,false)));
        assertEquals(182,TrainingLoad.observedMax(Collections.singletonList(a)));
    }

    @Test public void morningChecksParseRejectsJunk()throws Exception{
        JSONArray a=new JSONArray("[{\"time\":5,\"rmssd\":40,\"restingBpm\":52},{\"time\":4,\"rmssd\":-1,\"restingBpm\":50},{\"time\":3,\"rmssd\":30,\"restingBpm\":999},{\"time\":2,\"rmssd\":35,\"restingBpm\":55}]");
        List<TrainingLoad.Check> checks=MorningChecks.parse(a);
        assertEquals(2,checks.size());assertEquals(2,checks.get(0).time);
        assertEquals(52,MorningChecks.restingBpm(checks,10));
    }
}
