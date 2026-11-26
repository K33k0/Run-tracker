package com.runzone;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RunAnalysisTest {
    static final long START=1700000000000L;

    /** Steady 3 m/s for the given minutes, heart rate from the function, altitude optional. */
    static JSONObject run(int minutes,java.util.function.IntUnaryOperator bpm,java.util.function.IntToDoubleFunction altitude,boolean rr)throws JSONException{
        int seconds=minutes*60;JSONArray route=new JSONArray(),heart=new JSONArray(),cadence=new JSONArray();
        for(int s=0;s<=seconds;s+=2){
            double meters=3.0*s;JSONObject p=new JSONObject().put("lat",51+meters/111195).put("lon",0).put("time",START+s*1000L).put("activeMs",s*1000L).put("distanceMeters",meters);
            if(altitude!=null)p.put("altitude",altitude.applyAsDouble(s));route.put(p);
            cadence.put(new JSONObject().put("time",START+s*1000L).put("track",0).put("spm",180));
        }
        Random random=new Random(1);
        for(int s=0;s<=seconds;s++){
            int b=bpm.applyAsInt(s);JSONObject p=new JSONObject().put("time",START+s*1000L).put("activeMs",s*1000L).put("bpm",b);
            if(rr){JSONArray ticks=new JSONArray();double ms=60000.0/b;int beats=b/60;for(int k=0;k<beats;k++)ticks.put((int)Math.round((ms+random.nextGaussian()*8)*1.024));p.put("rrTicks",ticks);}
            heart.put(p);
        }
        return new JSONObject().put("schemaVersion",2).put("date",START).put("startTime",START).put("endTime",START+seconds*1000L).put("duration",seconds*1000L)
            .put("meters",3.0*seconds).put("route",route).put("heartRate",heart).put("cadence",cadence).put("zoneLow",130).put("zoneHigh",145);
    }

    @Test public void metresPerBeatAndDecouplingFromSteadyRun()throws Exception{
        RunAnalysis a=new RunAnalysis(RunRecord.fromJson(run(40,s->s<1200?140:150,null,false)));
        // 180 m per minute at 140-150 bpm.
        assertEquals(180.0/145,a.metresPerBeat,0.03);
        // Warm-up skipped: first half 300-1350 s is mostly 140 bpm, second half 150 bpm.
        assertTrue(a.decouplingPercent>3&&a.decouplingPercent<7);
        assertEquals(180.0/150,a.efficiency60[2000],0.01);
        assertEquals(1000/3.0,a.gapSecondsPerKm,1);
        assertEquals(1200-0,a.secondsIn,2);assertEquals(1201,a.secondsAbove,2);
    }

    @Test public void uphillGradeAdjustsPaceFaster()throws Exception{
        RunAnalysis a=new RunAnalysis(RunRecord.fromJson(run(10,s->150,s->s*3.0*0.05,false)));
        assertEquals(0.05,a.grade[300],0.005);
        assertTrue(a.gapSecondsPerKm<1000/3.0-30);
        assertEquals(RunAnalysis.cost(0.05)/RunAnalysis.FLAT_COST,(1000/3.0)/a.gapSecondsPerKm,0.03);
        assertEquals(90,a.elevationGain,6);
    }

    @Test public void stepLengthCadenceAndBestEfforts()throws Exception{
        RunAnalysis a=new RunAnalysis(RunRecord.fromJson(run(30,s->150,null,false)));
        assertEquals(1.0,a.averageStepLength,0.02);assertEquals(180,a.averageCadence,0.5);
        assertEquals(333,a.bestEfforts[1],2);assertEquals(1667,a.bestEfforts[3],2);assertEquals(-1,a.bestEfforts[4]);
    }

    @Test public void hrvOnlyFromSteadyWindowsWithIntervals()throws Exception{
        RunAnalysis a=new RunAnalysis(RunRecord.fromJson(run(20,s->150,null,true)));
        assertFalse(a.hrvWindows.isEmpty());assertTrue(a.rmssd>5&&a.rmssd<25);
        assertTrue(Double.isFinite(a.alpha1));
        RunAnalysis noRr=new RunAnalysis(RunRecord.fromJson(run(20,s->150,null,false)));
        assertTrue(noRr.hrvWindows.isEmpty());assertTrue(Double.isNaN(noRr.rmssd));
    }

    @Test public void heartRateRecoveryAfterFinish()throws Exception{
        JSONObject json=run(10,s->160,null,false);JSONArray recovery=new JSONArray();
        long end=json.getLong("endTime");
        for(int s=0;s<=120;s+=2)recovery.put(new JSONObject().put("time",end+s*1000L).put("bpm",160-s/4));
        json.put("recovery",recovery);
        RunAnalysis a=new RunAnalysis(RunRecord.fromJson(json));
        assertEquals(160,a.finishBpm);assertEquals(15,a.recovery60);assertEquals(30,a.recovery120);
        assertEquals(-1,new RunAnalysis(RunRecord.fromJson(run(10,s->160,null,false))).recovery60);
    }

    @Test public void sameRouteMatchesRepeatButNotDifferentCourse()throws Exception{
        RunRecord a=RunRecord.fromJson(run(10,s->150,null,false)),b=RunRecord.fromJson(run(10,s->140,null,false));
        JSONObject other=run(10,s->150,null,false);JSONArray route=other.getJSONArray("route");
        for(int i=0;i<route.length();i++){JSONObject p=route.getJSONObject(i);p.put("lon",p.getDouble("lat")-51);p.put("lat",51);}
        assertTrue(RunAnalysis.sameRoute(a,b));assertFalse(RunAnalysis.sameRoute(a,RunRecord.fromJson(other)));
    }

    @Test public void dfaOfWhiteNoiseIsNearHalf(){
        Random r=new Random(3);ArrayList<Double> rr=new ArrayList<>();for(int i=0;i<2000;i++)rr.add(400+r.nextGaussian()*10);
        assertEquals(0.5,RunAnalysis.dfaAlpha1(rr),0.15);
    }

    @Test public void legacyRunHasNoDerivedRouteMetrics()throws Exception{
        RunAnalysis a=new RunAnalysis(RunRecord.fromJson(new JSONObject("{\"date\":1700000100000,\"duration\":60000,\"meters\":100,\"route\":[{\"lat\":51,\"lon\":0,\"time\":1700000040000,\"bpm\":-1},{\"lat\":51.001,\"lon\":0,\"time\":1700000045000,\"bpm\":140}]}")));
        assertFalse(a.hasRoute);assertTrue(Double.isNaN(a.metresPerBeat));assertTrue(Double.isNaN(a.gapSecondsPerKm));
    }
}
