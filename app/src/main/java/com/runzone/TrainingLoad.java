package com.runzone;

import java.util.*;

/**
 * Training load from heart rate (Banister TRIMP), with fitness (42-day) and fatigue (7-day)
 * exponentially weighted averages, form (fitness − fatigue), and a readiness verdict that also
 * uses morning resting HRV checks when there are any.
 */
public final class TrainingLoad {
    public static final long DAY=86400000L;
    static final int FITNESS_DAYS=42,FATIGUE_DAYS=7;

    /** Banister TRIMP for one run from its per-second heart rate. Male-coefficient form, as most apps use. */
    public static double trimp(double[] heart,int restingBpm,int maxBpm){
        if(maxBpm<=restingBpm+20)return 0;
        double total=0;
        for(double h:heart){
            if(Double.isNaN(h))continue;
            double reserve=Math.max(0,Math.min(1,(h-restingBpm)/(double)(maxBpm-restingBpm)));
            total+=reserve*0.64*Math.exp(1.92*reserve)/60.0;
        }
        return total;
    }

    public static final class Day {
        public final long start;
        public final double load,fitness,fatigue;
        /** Form going into this day: yesterday's fitness minus yesterday's fatigue. */
        public final double form;
        Day(long start,double load,double fitness,double fatigue,double form){this.start=start;this.load=load;this.fitness=fitness;this.fatigue=fatigue;this.form=form;}
    }

    /**
     * Daily series ending on the day containing {@code now}. Runs are (start time, load) pairs.
     * Days are local calendar days, so {@code zone} decides where midnight falls.
     */
    public static List<Day> days(List<long[]> runs,List<Double> loads,long now,int count,TimeZone zone){
        Calendar calendar=Calendar.getInstance(zone);calendar.setTimeInMillis(now);
        calendar.set(Calendar.HOUR_OF_DAY,0);calendar.set(Calendar.MINUTE,0);calendar.set(Calendar.SECOND,0);calendar.set(Calendar.MILLISECOND,0);
        // Warm the averages up over extra history so the first shown day isn't zero.
        int warm=count+FITNESS_DAYS*3;
        long[] starts=new long[warm];
        for(int i=warm-1;i>=0;i--){starts[i]=calendar.getTimeInMillis();calendar.add(Calendar.DAY_OF_MONTH,-1);}
        double[] daily=new double[warm];
        for(int r=0;r<runs.size();r++){
            long t=runs.get(r)[0];
            for(int i=warm-1;i>=0;i--)if(t>=starts[i]){if(i<warm-1||t<starts[i]+2*DAY)daily[i]+=loads.get(r);break;}
        }
        double fitness=0,fatigue=0,kf=1-Math.exp(-1.0/FITNESS_DAYS),ka=1-Math.exp(-1.0/FATIGUE_DAYS);
        ArrayList<Day> out=new ArrayList<>();
        for(int i=0;i<warm;i++){
            double form=fitness-fatigue;
            fitness+=(daily[i]-fitness)*kf;fatigue+=(daily[i]-fatigue)*ka;
            if(i>=warm-count)out.add(new Day(starts[i],daily[i],fitness,fatigue,form));
        }
        return out;
    }

    public static final class Check {
        public final long time;
        public final double rmssd;
        public final int restingBpm;
        public Check(long time,double rmssd,int restingBpm){this.time=time;this.rmssd=rmssd;this.restingBpm=restingBpm;}
    }

    public static final class Readiness {
        public final String title,advice;
        /** -1 hold back, 0 normal, 1 ready for hard training. */
        public final int level;
        Readiness(int level,String title,String advice){this.level=level;this.title=title;this.advice=advice;}
    }

    /**
     * Combines form with today's HRV against the previous 7 checks (ln RMSSD, like most HRV apps):
     * more than half a standard deviation below normal means the body is still stressed.
     */
    public static Readiness readiness(Day today,List<Check> checks,long now,double weeklyRamp){
        Check latest=null;
        for(Check c:checks)if(now-c.time<=18*3600000L&&(latest==null||c.time>latest.time))latest=c;
        int hrv=0;String hrvNote="";
        if(latest!=null){
            ArrayList<Double> baseline=new ArrayList<>();
            for(Check c:checks)if(c!=latest&&c.time<latest.time&&latest.time-c.time<=8*DAY&&c.rmssd>0)baseline.add(Math.log(c.rmssd));
            if(baseline.size()>=3){
                double mean=0;for(double v:baseline)mean+=v;mean/=baseline.size();
                double sd=0;for(double v:baseline)sd+=(v-mean)*(v-mean);sd=Math.max(0.05,Math.sqrt(sd/(baseline.size()-1)));
                double z=(Math.log(latest.rmssd)-mean)/sd;
                if(z<-0.5){hrv=-1;hrvNote=" This morning’s HRV is below your normal range.";}
                else{hrv=1;hrvNote=" This morning’s HRV is in your normal range.";}
            }else hrvNote=" Do a few more morning checks to set your HRV baseline.";
        }
        String ramp=weeklyRamp>8?" Fitness is rising fast (+"+Math.round(weeklyRamp)+" a week). Big jumps raise injury risk.":"";
        double form=today.form;
        if(today.fitness<5)return new Readiness(0,"Getting started","Record a few runs with heart rate to see your training load."+hrvNote);
        if(hrv<0||form<-30)return new Readiness(-1,form<-30?"Overreaching":"Take it easy","Keep today easy or rest."+(form<-30?" Fatigue is well above your fitness.":"")+hrvNote+ramp);
        if(form>5)return new Readiness(1,"Fresh","Good day for a hard session or a race."+hrvNote+ramp);
        if(form<-10)return new Readiness(0,"Building","You’re carrying productive fatigue. Easy or steady running fits best."+hrvNote+ramp);
        return new Readiness(hrv>0?1:0,"Balanced","Ready for normal training."+hrvNote+ramp);
    }

    /** Peak heart rate sustained for 10 s across runs, to resist strap spikes. */
    public static int observedMax(List<RunAnalysis> runs){
        double best=0;
        for(RunAnalysis a:runs){
            double sum=0;int valid=0;
            for(int i=0;i<a.n;i++){
                if(!Double.isNaN(a.heart[i])){sum+=a.heart[i];valid++;}
                if(i>=10&&!Double.isNaN(a.heart[i-10])){sum-=a.heart[i-10];valid--;}
                if(i>=9&&valid==10)best=Math.max(best,sum/10);
            }
        }
        return (int)Math.round(best);
    }

    /**
     * Heart rate where DFA α1 crosses 0.75 (first ventilatory / aerobic threshold), from a linear fit
     * of α1 against heart rate over steady windows. Needs windows on both sides of the crossing.
     */
    public static int aerobicThreshold(List<RunAnalysis> runs){
        double sx=0,sy=0,sxx=0,sxy=0;int count=0,above=0,below=0;
        for(RunAnalysis a:runs)for(RunAnalysis.HrvWindow w:a.hrvWindows){
            if(!Double.isFinite(w.alpha1)||w.alpha1<0.2||w.alpha1>1.6)continue;
            sx+=w.meanBpm;sy+=w.alpha1;sxx+=w.meanBpm*w.meanBpm;sxy+=w.meanBpm*w.alpha1;count++;
            if(w.alpha1>=0.75)above++;else below++;
        }
        if(count<6||above<2||below<2)return -1;
        double slope=(count*sxy-sx*sy)/(count*sxx-sx*sx);
        if(!(slope<0))return -1;
        double intercept=(sy-slope*sx)/count;
        double hr=(0.75-intercept)/slope;
        return Double.isFinite(hr)&&hr>=80&&hr<=210?(int)Math.round(hr):-1;
    }

    /**
     * Five-zone boundaries (tops of Zones 1–4). With an aerobic threshold, Zone 2 tops out there;
     * otherwise percentages of maximum heart rate (60/70/80/90 %) are used.
     */
    public static int[] suggestZones(int maxBpm,int aerobicThreshold){
        if(maxBpm<120||maxBpm>=ZoneRanges.MAX)return null;
        int[] ends;
        if(aerobicThreshold>0&&aerobicThreshold<maxBpm-15){
            int span=maxBpm-aerobicThreshold;
            ends=new int[]{aerobicThreshold-(int)Math.round(maxBpm*0.1),aerobicThreshold,aerobicThreshold+(int)Math.round(span*0.4),aerobicThreshold+(int)Math.round(span*0.75)};
        }else ends=new int[]{(int)Math.round(maxBpm*0.6),(int)Math.round(maxBpm*0.7),(int)Math.round(maxBpm*0.8),(int)Math.round(maxBpm*0.9)};
        return ZoneRanges.valid(ends)?ends:null;
    }
}
