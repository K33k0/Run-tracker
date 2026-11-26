package com.runzone;

import java.util.*;

/**
 * Derived metrics for one saved run. Everything is resampled onto a one-second grid of active
 * (unpaused) time, so pauses never count as slow running or as recovery.
 * Values that cannot be computed from the recorded data are NaN (or -1 for durations).
 */
public final class RunAnalysis {
    public static final int[] BEST_EFFORT_METERS={400,1000,1609,5000,10000,21097};
    public static final String[] BEST_EFFORT_NAMES={"400 m","1 km","1 mile","5 km","10 km","Half marathon"};
    static final double FLAT_COST=3.6;

    public static final class HrvWindow {
        public final int startSecond;
        public final double rmssd,alpha1,meanBpm;
        HrvWindow(int startSecond,double rmssd,double alpha1,double meanBpm){this.startSecond=startSecond;this.rmssd=rmssd;this.alpha1=alpha1;this.meanBpm=meanBpm;}
    }

    public final RunRecord run;
    /** Number of one-second samples. */
    public final int n;
    public final boolean hasRoute,hasHeart,hasAltitude,hasCadence;
    /** Per-second series. distance is cumulative metres; speed m/s; grade is rise over run. */
    public final double[] distance,altitude,grade,speed,heart,cadence;

    public final double metresPerBeat;
    /** Grade-adjusted pace for the whole run, seconds per km. */
    public final double gapSecondsPerKm;
    /** Grade-adjusted pace for each split of {@link RunRecord#splits()}, seconds per km. */
    public final List<Double> gapSplits;
    /** Grade-adjusted pace smoothed over 30 s, seconds per km; NaN when walking or stopped. */
    public final double[] gapPace;
    /** Metres per heartbeat over the trailing 60 s of active time. */
    public final double[] efficiency60;
    /** Pa:HR decoupling, percent. Positive means efficiency fell in the second half. */
    public final double decouplingPercent;
    public final int decouplingWarmupSeconds;
    public final double[] stepLength;
    public final double averageStepLength,averageCadence;
    public final int secondsBelow,secondsIn,secondsAbove;
    public final double elevationGain,elevationLoss;
    /** Fastest active-time seconds for each {@link #BEST_EFFORT_METERS}, or -1. */
    public final long[] bestEfforts;
    public final List<HrvWindow> hrvWindows;
    public final double rmssd,alpha1;
    /** Seconds after the finish and heart rate at that moment. */
    public final double[] recoverySeconds,recoveryBpm;
    public final int finishBpm,recovery60,recovery120;

    public RunAnalysis(RunRecord run){
        this.run=run;
        n=(int)Math.max(0,run.durationMs/1000)+1;
        List<RunRecord.Point> points=new ArrayList<>();
        for(RunRecord.Point p:run.points)if(p.activeMs>=0)points.add(p);
        points.sort(Comparator.comparingLong(p->p.activeMs));
        hasRoute=!run.legacy&&points.size()>=2;
        distance=new double[n];altitude=new double[n];grade=new double[n];speed=new double[n];heart=new double[n];cadence=new double[n];
        resampleRoute(points);
        boolean altitudes=false;for(double a:altitude)if(!Double.isNaN(a)){altitudes=true;break;}
        hasAltitude=hasRoute&&altitudes;
        resampleHeart();
        hasHeart=countFinite(heart)>=30;
        resampleCadence(points);
        hasCadence=countFinite(cadence)>=30;
        computeGrade();

        double[] flat=new double[n];double flatTotal=0,metresWithHeart=0,beats=0;
        for(int i=0;i<n-1;i++){
            double step=Math.max(0,distance[i+1]-distance[i]);
            flat[i]=step*cost(grade[i])/FLAT_COST;flatTotal+=flat[i];
            if(!Double.isNaN(heart[i])){metresWithHeart+=step;beats+=heart[i]/60;}
        }
        metresPerBeat=hasRoute&&beats>=300&&metresWithHeart>0?metresWithHeart/beats:Double.NaN;
        gapSecondsPerKm=hasRoute&&flatTotal>=200?run.durationMs/flatTotal:Double.NaN;

        gapPace=new double[n];
        for(int i=0;i<n;i++){
            int a=Math.max(0,i-15),b=Math.min(n-1,i+15);double sum=0;for(int j=a;j<b;j++)sum+=flat[j];
            double mps=b>a?sum/(b-a):0;
            gapPace[i]=hasRoute&&mps>=1?1000/mps:Double.NaN;
        }

        gapSplits=new ArrayList<>();
        if(hasRoute){
            double next=1000,flatSoFar=0;int splitStart=0;double splitFlat=0;
            for(int i=0;i<n-1;i++){
                splitFlat+=flat[i];flatSoFar+=flat[i];
                if(distance[i+1]>=next){gapSplits.add(splitFlat>0?(i+1-splitStart)*1000.0/splitFlat:Double.NaN);splitStart=i+1;splitFlat=0;next+=1000;}
            }
            if(distance[n-1]-(next-1000)>=1)gapSplits.add(splitFlat>0?(n-1-splitStart)*1000.0/splitFlat:Double.NaN);
        }

        efficiency60=new double[n];Arrays.fill(efficiency60,Double.NaN);
        if(hasRoute&&hasHeart){
            double windowBeats=0;int windowValid=0;
            for(int i=0;i<n;i++){
                if(i>0&&!Double.isNaN(heart[i-1])){windowBeats+=heart[i-1]/60;windowValid++;}
                if(i>60&&!Double.isNaN(heart[i-61])){windowBeats-=heart[i-61]/60;windowValid--;}
                if(i>=60&&windowValid>=45&&windowBeats>0){
                    double meters=distance[i]-distance[i-60];
                    efficiency60[i]=meters/(windowBeats*60.0/windowValid);
                }
            }
        }

        decouplingWarmupSeconds=n>=1800?300:0;
        decouplingPercent=hasRoute&&hasHeart&&n-decouplingWarmupSeconds>=1200?decoupling(flat,decouplingWarmupSeconds):Double.NaN;

        stepLength=new double[n];Arrays.fill(stepLength,Double.NaN);
        double strideMeters=0,steps=0,cadenceSum=0;int cadenceCount=0;
        for(int i=0;i<n;i++){
            if(Double.isNaN(cadence[i]))continue;
            cadenceSum+=cadence[i];cadenceCount++;
            if(hasRoute&&speed[i]>=1.5&&cadence[i]>=100){
                stepLength[i]=speed[i]/(cadence[i]/60);
                if(i<n-1){strideMeters+=Math.max(0,distance[i+1]-distance[i]);steps+=cadence[i]/60;}
            }
        }
        averageStepLength=steps>=60?strideMeters/steps:Double.NaN;
        averageCadence=cadenceCount>=30?cadenceSum/cadenceCount:Double.NaN;

        int below=0,in=0,above=0;
        if(run.low>0&&run.high>=run.low)for(double h:heart){if(Double.isNaN(h))continue;if(h<run.low)below++;else if(h>run.high)above++;else in++;}
        secondsBelow=below;secondsIn=in;secondsAbove=above;

        double[] climb=elevationChange();elevationGain=climb[0];elevationLoss=climb[1];
        bestEfforts=new long[BEST_EFFORT_METERS.length];
        for(int k=0;k<bestEfforts.length;k++)bestEfforts[k]=hasRoute?fastest(BEST_EFFORT_METERS[k]):-1;

        hrvWindows=hasHeart?hrv():Collections.emptyList();
        rmssd=median(hrvWindows,true);alpha1=median(hrvWindows,false);

        ArrayList<double[]> after=new ArrayList<>();
        for(RunRecord.Pulse p:run.recovery){double s=(p.time-run.endTime)/1000.0;if(s>=0&&s<=180)after.add(new double[]{s,p.bpm});}
        recoverySeconds=new double[after.size()];recoveryBpm=new double[after.size()];
        for(int i=0;i<after.size();i++){recoverySeconds[i]=after.get(i)[0];recoveryBpm[i]=after.get(i)[1];}
        int last=-1;
        if(!run.pulses.isEmpty()){RunRecord.Pulse p=run.pulses.get(run.pulses.size()-1);if(run.endTime-p.time<=15000)last=p.bpm;}
        if(last<0&&recoveryBpm.length>0&&recoverySeconds[0]<=5)last=(int)recoveryBpm[0];
        finishBpm=last;
        recovery60=recoveryDrop(60);recovery120=recoveryDrop(120);
    }

    /** Metabolic cost of running on a slope, J/kg/m (Minetti et al. 2002). */
    static double cost(double g){
        g=Math.max(-0.45,Math.min(0.45,g));
        return 155.4*Math.pow(g,5)-30.4*Math.pow(g,4)-43.3*Math.pow(g,3)+46.3*g*g+19.5*g+3.6;
    }

    private void resampleRoute(List<RunRecord.Point> points){
        Arrays.fill(altitude,Double.NaN);
        if(!hasRoute){Arrays.fill(speed,Double.NaN);return;}
        int j=0;
        for(int i=0;i<n;i++){
            long t=i*1000L;
            while(j<points.size()-2&&points.get(j+1).activeMs<=t)j++;
            RunRecord.Point a=points.get(j),b=points.get(j+1);
            double f=b.activeMs>a.activeMs?Math.max(0,Math.min(1,(t-a.activeMs)/(double)(b.activeMs-a.activeMs))):1;
            if(t<a.activeMs)f=0;
            distance[i]=a.distance+(b.distance-a.distance)*f;
            if(i>0)distance[i]=Math.max(distance[i],distance[i-1]);
        }
        // Altitude: nearest-neighbour interpolation over points that carry one, within 30 s.
        ArrayList<RunRecord.Point> withAltitude=new ArrayList<>();
        for(RunRecord.Point p:points)if(p.altitude!=null)withAltitude.add(p);
        if(withAltitude.size()>=2){
            int k=0;
            for(int i=0;i<n;i++){
                long t=i*1000L;
                while(k<withAltitude.size()-2&&withAltitude.get(k+1).activeMs<=t)k++;
                RunRecord.Point a=withAltitude.get(k),b=withAltitude.get(k+1);
                if(t<a.activeMs-30000||t>b.activeMs+30000)continue;
                double f=b.activeMs>a.activeMs?Math.max(0,Math.min(1,(t-a.activeMs)/(double)(b.activeMs-a.activeMs))):0;
                altitude[i]=a.altitude+(b.altitude-a.altitude)*f;
            }
        }
        for(int i=0;i<n;i++){
            int a=Math.max(0,i-5),b=Math.min(n-1,i+5);
            speed[i]=b>a?(distance[b]-distance[a])/(b-a):0;
        }
    }

    private void resampleHeart(){
        Arrays.fill(heart,Double.NaN);
        List<RunRecord.Pulse> pulses=new ArrayList<>();
        for(RunRecord.Pulse p:run.pulses)if(p.activeMs>=0)pulses.add(p);
        pulses.sort(Comparator.comparingLong(p->p.activeMs));
        if(pulses.isEmpty())return;
        int j=0;
        for(int i=0;i<n;i++){
            long t=i*1000L;
            while(j<pulses.size()-1&&pulses.get(j+1).activeMs<=t)j++;
            RunRecord.Pulse a=pulses.get(j),b=j+1<pulses.size()?pulses.get(j+1):a;
            if(a.activeMs<=t&&b.activeMs>=t&&b.activeMs-a.activeMs<=10000&&b!=a){
                double f=(t-a.activeMs)/(double)(b.activeMs-a.activeMs);heart[i]=a.bpm+(b.bpm-a.bpm)*f;
            }else{
                RunRecord.Pulse near=Math.abs(a.activeMs-t)<=Math.abs(b.activeMs-t)?a:b;
                if(Math.abs(near.activeMs-t)<=3000)heart[i]=near.bpm;
            }
        }
    }

    /** Cadence samples carry wall-clock time only; map them to active time through the route. */
    private void resampleCadence(List<RunRecord.Point> points){
        Arrays.fill(cadence,Double.NaN);
        if(run.cadence.isEmpty()||points.size()<2)return;
        List<RunRecord.Point> byTime=new ArrayList<>(points);byTime.sort(Comparator.comparingLong(p->p.time));
        ArrayList<double[]> mapped=new ArrayList<>();int j=0;
        for(RunRecord.Cadence c:run.cadence){
            while(j<byTime.size()-2&&byTime.get(j+1).time<=c.time)j++;
            RunRecord.Point a=byTime.get(j),b=byTime.get(j+1);
            if(c.time<a.time-5000||c.time>b.time+5000||b.time<=a.time)continue;
            double f=Math.max(0,Math.min(1,(c.time-a.time)/(double)(b.time-a.time)));
            mapped.add(new double[]{a.activeMs+(b.activeMs-a.activeMs)*f,c.stepsPerMinute});
        }
        mapped.sort(Comparator.comparingDouble(v->v[0]));
        int k=0;
        for(int i=0;i<n&&!mapped.isEmpty();i++){
            double t=i*1000.0;
            while(k<mapped.size()-1&&Math.abs(mapped.get(k+1)[0]-t)<=Math.abs(mapped.get(k)[0]-t))k++;
            if(Math.abs(mapped.get(k)[0]-t)<=3000)cadence[i]=mapped.get(k)[1];
        }
    }

    private void computeGrade(){
        if(!hasAltitude)return;
        double[] smooth=new double[n];
        for(int i=0;i<n;i++){
            double sum=0;int count=0;
            for(int j=Math.max(0,i-15);j<=Math.min(n-1,i+15);j++)if(!Double.isNaN(altitude[j])){sum+=altitude[j];count++;}
            smooth[i]=count>0?sum/count:Double.NaN;
        }
        for(int i=0;i<n;i++){
            int a=Math.max(0,i-15),b=Math.min(n-1,i+15);
            double run=distance[b]-distance[a];
            grade[i]=run>=10&&!Double.isNaN(smooth[a])&&!Double.isNaN(smooth[b])?Math.max(-0.45,Math.min(0.45,(smooth[b]-smooth[a])/run)):0;
        }
    }

    private double decoupling(double[] flat,int warmup){
        int middle=warmup+(n-warmup)/2;
        double[] first=halfEfficiency(flat,warmup,middle),second=halfEfficiency(flat,middle,n-1);
        if(first==null||second==null)return Double.NaN;
        double a=first[0]/first[1],b=second[0]/second[1];
        return (a-b)/a*100;
    }
    /** Flat-equivalent metres and heartbeats over [from,to), or null if heart coverage is poor. */
    private double[] halfEfficiency(double[] flat,int from,int to){
        double meters=0,beats=0;int valid=0;
        for(int i=from;i<to;i++){if(Double.isNaN(heart[i]))continue;meters+=flat[i];beats+=heart[i]/60;valid++;}
        return valid>=(to-from)*0.7&&beats>0&&meters>0?new double[]{meters,beats}:null;
    }

    /** Gain and loss from smoothed altitude, ignoring changes smaller than 3 m (GPS noise). */
    private double[] elevationChange(){
        double gain=0,loss=0,anchor=Double.NaN;
        if(!hasAltitude)return new double[]{Double.NaN,Double.NaN};
        for(int i=0;i<n;i+=5){
            double sum=0;int count=0;
            for(int j=Math.max(0,i-15);j<=Math.min(n-1,i+15);j++)if(!Double.isNaN(altitude[j])){sum+=altitude[j];count++;}
            if(count==0)continue;double a=sum/count;
            if(Double.isNaN(anchor)){anchor=a;continue;}
            if(a-anchor>=3){gain+=a-anchor;anchor=a;}else if(anchor-a>=3){loss+=anchor-a;anchor=a;}
        }
        return new double[]{gain,loss};
    }

    private long fastest(double meters){
        if(distance[n-1]<meters)return -1;
        long best=-1;int start=0;
        for(int end=1;end<n;end++){
            while(start+1<end&&distance[end]-distance[start+1]>=meters)start++;
            if(distance[end]-distance[start]>=meters){long seconds=end-start;if(best<0||seconds<best)best=seconds;}
        }
        return best;
    }

    /**
     * Beat-to-beat variability in two-minute windows of steady effort: heart rate within ±4 bpm
     * (standard deviation) and, with GPS, a steady running speed. Artefacts more than 20 % away from
     * the previous accepted interval are dropped.
     */
    private List<HrvWindow> hrv(){
        ArrayList<double[]> intervals=new ArrayList<>();
        for(RunRecord.Pulse p:run.pulses){
            if(p.activeMs<0)continue;
            for(int ticks:p.rrTicks){double ms=ticks*1000.0/1024;if(ms>=270&&ms<=2000)intervals.add(new double[]{p.activeMs,ms});}
        }
        if(intervals.size()<200)return Collections.emptyList();
        ArrayList<HrvWindow> windows=new ArrayList<>();int cursor=0;
        for(int start=0;start+120<=n;start+=120){
            double sum=0,sq=0;int valid=0;
            for(int i=start;i<start+120;i++)if(!Double.isNaN(heart[i])){sum+=heart[i];sq+=heart[i]*heart[i];valid++;}
            if(valid<108)continue;
            double mean=sum/valid,sd=Math.sqrt(Math.max(0,sq/valid-mean*mean));
            if(sd>4)continue;
            if(hasRoute){
                double s=0,s2=0;for(int i=start;i<start+120;i++){s+=speed[i];s2+=speed[i]*speed[i];}
                double m=s/120,ssd=Math.sqrt(Math.max(0,s2/120-m*m));
                if(m<1.5||ssd/m>0.12)continue;
            }
            while(cursor<intervals.size()&&intervals.get(cursor)[0]<start*1000.0)cursor++;
            ArrayList<Double> raw=new ArrayList<>();
            for(int i=cursor;i<intervals.size()&&intervals.get(i)[0]<(start+120)*1000.0;i++)raw.add(intervals.get(i)[1]);
            List<Double> clean=cleanIntervals(raw);
            double expected=mean*2;
            if(clean.size()<expected*0.8||clean.size()<60)continue;
            windows.add(new HrvWindow(start,rmssd(clean),dfaAlpha1(clean),mean));
        }
        return windows;
    }

    /** Drops intervals outside 270–2000 ms or more than 20 % away from the previous interval. */
    static List<Double> cleanIntervals(List<Double> rr){
        ArrayList<Double> clean=new ArrayList<>();double previous=Double.NaN;
        for(double ms:rr){
            if(ms<270||ms>2000)continue;
            if(Double.isNaN(previous)||Math.abs(ms-previous)<=previous*0.2)clean.add(ms);
            previous=ms;
        }
        return clean;
    }
    static double rmssd(List<Double> clean){
        if(clean.size()<2)return Double.NaN;
        double diff=0;for(int i=1;i<clean.size();i++){double d=clean.get(i)-clean.get(i-1);diff+=d*d;}
        return Math.sqrt(diff/(clean.size()-1));
    }
    /** Short-term detrended fluctuation analysis exponent over box sizes 4–16 beats. */
    static double dfaAlpha1(List<Double> rr){
        int size=rr.size();if(size<50)return Double.NaN;
        double mean=0;for(double v:rr)mean+=v;mean/=size;
        double[] y=new double[size];double total=0;for(int i=0;i<size;i++){total+=rr.get(i)-mean;y[i]=total;}
        double sx=0,sy=0,sxx=0,sxy=0;int points=0;
        for(int box=4;box<=16;box++){
            int boxes=size/box;if(boxes<2)continue;double residual=0;
            for(int b=0;b<boxes;b++){
                double xm=(box-1)/2.0,ym=0;for(int i=0;i<box;i++)ym+=y[b*box+i];ym/=box;
                double num=0,den=0;for(int i=0;i<box;i++){num+=(i-xm)*(y[b*box+i]-ym);den+=(i-xm)*(i-xm);}
                double slope=num/den;
                for(int i=0;i<box;i++){double r=y[b*box+i]-(ym+slope*(i-xm));residual+=r*r;}
            }
            double f=Math.sqrt(residual/(boxes*box));if(f<=0)continue;
            double lx=Math.log(box),ly=Math.log(f);sx+=lx;sy+=ly;sxx+=lx*lx;sxy+=lx*ly;points++;
        }
        if(points<3)return Double.NaN;
        return (points*sxy-sx*sy)/(points*sxx-sx*sx);
    }

    private static double median(List<HrvWindow> windows,boolean rmssd){
        ArrayList<Double> values=new ArrayList<>();
        for(HrvWindow w:windows){double v=rmssd?w.rmssd:w.alpha1;if(Double.isFinite(v))values.add(v);}
        if(values.isEmpty())return Double.NaN;
        Collections.sort(values);int m=values.size()/2;
        return values.size()%2==1?values.get(m):(values.get(m-1)+values.get(m))/2;
    }

    private int recoveryDrop(double at){
        if(finishBpm<0)return -1;
        int best=-1;double gap=5.01;
        for(int i=0;i<recoverySeconds.length;i++){double d=Math.abs(recoverySeconds[i]-at);if(d<gap){gap=d;best=i;}}
        return best<0?-1:finishBpm-(int)Math.round(recoveryBpm[best]);
    }

    private static int countFinite(double[] values){int c=0;for(double v:values)if(!Double.isNaN(v))c++;return c;}

    public static String pace(double secondsPerKm){
        if(!Double.isFinite(secondsPerKm)||secondsPerKm<=0)return "—";
        long s=Math.round(secondsPerKm);return String.format(Locale.getDefault(),"%d:%02d",s/60,s%60);
    }

    /**
     * Whether two runs follow the same course: similar distance, starts close together, and each
     * route passes within 75 m of at least 85 % of evenly spaced positions along the other.
     */
    public static boolean sameRoute(RunRecord a,RunRecord b){
        if(a.points.size()<10||b.points.size()<10||a.meters<500||b.meters<500)return false;
        if(Math.abs(a.meters-b.meters)>Math.max(a.meters,b.meters)*0.1)return false;
        RunRecord.Point sa=a.points.get(0),sb=b.points.get(0);
        if(RunRecord.distance(sa.lat,sa.lon,sb.lat,sb.lon)>200)return false;
        return covers(a,b)&&covers(b,a);
    }
    private static boolean covers(RunRecord route,RunRecord other){
        int samples=30,hits=0;
        for(int s=0;s<samples;s++){
            RunRecord.Point p=route.points.get(s*(route.points.size()-1)/(samples-1));
            for(RunRecord.Point q:other.points)if(RunRecord.distance(p.lat,p.lon,q.lat,q.lon)<=75){hits++;break;}
        }
        return hits>=samples*0.85;
    }
}
