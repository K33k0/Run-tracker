package com.runzone;

import java.util.*;

/**
 * Plain-English read of one run, built from {@link RunAnalysis}. Each sentence says what a metric
 * means for the runner rather than its raw value, and only metrics with enough data are mentioned.
 */
public final class RunSummary {
    private RunSummary(){}

    public static List<String> sentences(RunAnalysis a){
        ArrayList<String> out=new ArrayList<>();
        RunRecord run=a.run;
        String pacing=pacing(run.splits());
        if(pacing!=null)out.add(pacing);

        if(Double.isFinite(a.gapSecondsPerKm)&&run.meters>0&&Double.isFinite(a.elevationGain)&&a.elevationGain>=20){
            double actual=run.durationMs/run.meters,diff=actual-a.gapSecondsPerKm;
            if(diff>=5)out.add(String.format(Locale.getDefault(),"The hills (%.0f m of climbing) cost you about %d s per km — on the flat that effort is worth %s /km.",a.elevationGain,Math.round(diff),RunAnalysis.pace(a.gapSecondsPerKm)));
            else out.add(String.format(Locale.getDefault(),"There was %.0f m of climbing, but it barely slowed you down.",a.elevationGain));
        }

        int zoned=a.secondsBelow+a.secondsIn+a.secondsAbove;
        if(zoned>=300){
            double in=a.secondsIn*100.0/zoned,above=a.secondsAbove*100.0/zoned,below=a.secondsBelow*100.0/zoned;
            if(in>=70)out.add(String.format(Locale.getDefault(),"You held your target heart-rate zone well — %.0f%% of the run was in it.",in));
            else if(above>=below)out.add(String.format(Locale.getDefault(),"You spent %.0f%% of the run above your target zone, so this was harder than planned.",above));
            else out.add(String.format(Locale.getDefault(),"You spent %.0f%% of the run below your target zone — an easier effort than planned.",below));
        }

        if(Double.isFinite(a.decouplingPercent)){
            double d=a.decouplingPercent;
            if(d<5)out.add("Your heart rate stayed in step with your pace from start to finish, a sign of good aerobic endurance at this effort.");
            else if(d<10)out.add(String.format(Locale.getDefault(),"Your heart rate drifted up %.0f%% relative to pace in the second half — some fatigue, heat or dehydration crept in.",d));
            else out.add(String.format(Locale.getDefault(),"Your heart rate climbed a lot (%.0f%%) relative to pace in the second half. This effort is beyond your current endurance for this distance.",d));
        }

        if(Double.isFinite(a.alpha1)){
            if(a.alpha1>=0.75)out.add("Heart-rate variability suggests the steady parts were below your aerobic threshold — truly easy running.");
            else if(a.alpha1>=0.5)out.add("Heart-rate variability suggests the steady parts were around your aerobic threshold — moderate, not easy.");
            else out.add("Heart-rate variability suggests the steady parts were well above your aerobic threshold — hard running.");
        }

        if(a.recovery60>=0){
            if(a.recovery60>=25)out.add(String.format(Locale.getDefault(),"Your heart rate dropped %d bpm in the first minute after stopping, which is a strong recovery.",a.recovery60));
            else if(a.recovery60>=12)out.add(String.format(Locale.getDefault(),"Your heart rate dropped %d bpm in the first minute after stopping, a normal recovery.",a.recovery60));
            else out.add(String.format(Locale.getDefault(),"Your heart rate dropped only %d bpm in the first minute after stopping — you may be tired or not fully recovered.",a.recovery60));
        }

        if(Double.isFinite(a.averageCadence)&&a.averageCadence>=100){
            if(a.averageCadence<160)out.add(String.format(Locale.getDefault(),"Your cadence averaged %.0f steps per minute; slightly quicker, shorter steps can ease the load on your legs.",a.averageCadence));
        }
        if(out.isEmpty())out.add("Not enough data was recorded for a detailed read of this run.");
        return out;
    }

    /** Compares the first and second half of the full kilometres; null with fewer than 3. */
    static String pacing(List<RunRecord.Split> splits){
        ArrayList<Double> paces=new ArrayList<>();
        for(RunRecord.Split s:splits)if(s.meters>=999.5)paces.add(s.durationMs/s.meters);
        if(paces.size()<3)return null;
        int half=paces.size()/2;double first=0,second=0;
        for(int i=0;i<half;i++)first+=paces.get(i);
        for(int i=paces.size()-half;i<paces.size();i++)second+=paces.get(i);
        first/=half;second/=half;
        double diff=second-first;
        int fastest=0,slowest=0;for(int i=1;i<paces.size();i++){if(paces.get(i)<paces.get(fastest))fastest=i;if(paces.get(i)>paces.get(slowest))slowest=i;}
        String range=String.format(Locale.getDefault(),"Fastest was kilometre %d at %s /km.",fastest+1,RunAnalysis.pace(paces.get(fastest)));
        if(Math.abs(diff)<5)return "You paced this evenly, keeping a steady speed throughout. "+range;
        if(diff<0)return String.format(Locale.getDefault(),"You ran a negative split, finishing about %d s per km faster than you started. ",Math.round(-diff))+range;
        return String.format(Locale.getDefault(),"You slowed by about %d s per km in the second half — next time try starting a little easier. ",Math.round(diff))+range;
    }
}
