package com.runzone;

import android.graphics.*;
import java.util.*;

/** Renders a run as an image for social posts and stories. */
public final class ShareCard {
    public enum Format {
        POST("Post",1080,1350),STORY("Story",1080,1920),SQUARE("Square",1080,1080);
        public final String label;public final int width,height;
        Format(String label,int width,int height){this.label=label;this.width=width;this.height=height;}
    }
    public enum Theme {
        NIGHT("Night",0xff101819,0xff1b2627,0xfff2f5ee,0xff9eafaa,0xffc2f970),
        LIME("Lime",0xffc2f970,0xffb1e85f,0xff101819,0xff35502a,0xff101819),
        DAWN("Dawn",0xfff4f1ea,0xffe8e4da,0xff1a2422,0xff6c7a75,0xffe0603c);
        public final String label;public final int background,panel,text,muted,accent;
        Theme(String label,int background,int panel,int text,int muted,int accent){this.label=label;this.background=background;this.panel=panel;this.text=text;this.muted=muted;this.accent=accent;}
    }
    public static final class Stat {
        public final String label,value,unit;
        Stat(String label,String value,String unit){this.label=label;this.value=value;this.unit=unit;}
    }
    private ShareCard(){}

    /** The headline numbers, then the most interesting analysis figures this run has data for. */
    public static List<Stat> stats(RunRecord run,RunAnalysis a){
        ArrayList<Stat> stats=new ArrayList<>();
        long seconds=run.durationMs/1000;
        stats.add(new Stat("TIME",SeriesChart.clock(seconds),seconds>=3600?"h:mm:ss":"min:sec"));
        stats.add(new Stat("PACE",run.meters>=20?RunAnalysis.pace(run.durationMs/run.meters):"—","/km"));
        if(run.averageBpm()>0)stats.add(new Stat("AVG HR",Integer.toString(run.averageBpm()),"bpm"));
        if(a.hasAltitude&&Double.isFinite(a.gapSecondsPerKm))stats.add(new Stat("GRADE-ADJ.",RunAnalysis.pace(a.gapSecondsPerKm),"/km"));
        if(Double.isFinite(a.metresPerBeat))stats.add(new Stat("PER BEAT",String.format(Locale.getDefault(),"%.2f",a.metresPerBeat),"metres"));
        if(a.hasAltitude&&Double.isFinite(a.elevationGain)&&a.elevationGain>=5)stats.add(new Stat("CLIMB",String.format(Locale.getDefault(),"%.0f",a.elevationGain),"m"));
        long zone=a.secondsBelow+a.secondsIn+a.secondsAbove;
        if(zone>=60)stats.add(new Stat("IN ZONE",Math.round(100.0*a.secondsIn/zone)+"%",run.low+"–"+run.high));
        if(Double.isFinite(a.averageCadence))stats.add(new Stat("CADENCE",String.format(Locale.getDefault(),"%.0f",a.averageCadence),"spm"));
        if(Double.isFinite(a.decouplingPercent))stats.add(new Stat("DRIFT",String.format(Locale.getDefault(),"%.1f%%",a.decouplingPercent),"Pa:HR"));
        return stats.subList(0,Math.min(6,stats.size()));
    }

    /** One line worth bragging about, or null. */
    public static String highlight(RunRecord run,RunAnalysis a){
        for(int i=a.bestEfforts.length-1;i>=0;i--)if(a.bestEfforts[i]>0&&RunAnalysis.BEST_EFFORT_METERS[i]>=1000&&run.meters<RunAnalysis.BEST_EFFORT_METERS[i]*1.6)
            return "Fastest "+RunAnalysis.BEST_EFFORT_NAMES[i]+" · "+SeriesChart.clock(a.bestEfforts[i]);
        if(Double.isFinite(a.decouplingPercent)&&a.decouplingPercent<5&&run.durationMs>=45*60000L)return "Rock-steady aerobic run · "+String.format(Locale.getDefault(),"%.1f%%",a.decouplingPercent)+" drift";
        if(a.recovery60>=20)return "Heart rate dropped "+a.recovery60+" bpm in 1 minute after finishing";
        long zone=a.secondsBelow+a.secondsIn+a.secondsAbove;
        if(zone>=600&&a.secondsIn>=zone*0.8)return Math.round(100.0*a.secondsIn/zone)+"% of the run in the target zone";
        return null;
    }

    /** Route positions scaled to fit a box, keeping its shape. Null entries mark breaks. Returns null without a route. */
    static float[][] fitRoute(List<RunRecord.Point> points,float left,float top,float width,float height){
        if(points.size()<2)return null;
        double lat0=points.get(0).lat,cos=Math.cos(Math.toRadians(lat0));
        double minX=Double.MAX_VALUE,maxX=-Double.MAX_VALUE,minY=Double.MAX_VALUE,maxY=-Double.MAX_VALUE;
        for(RunRecord.Point p:points){double x=p.lon*cos,y=-p.lat;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);}
        double spanX=Math.max(1e-6,maxX-minX),spanY=Math.max(1e-6,maxY-minY);
        if(spanX<5e-5&&spanY<5e-5)return null;
        double scale=Math.min(width/spanX,height/spanY);
        double offsetX=left+(width-spanX*scale)/2,offsetY=top+(height-spanY*scale)/2;
        float[][] out=new float[points.size()][];int track=points.get(0).track;
        for(int i=0;i<points.size();i++){RunRecord.Point p=points.get(i);out[i]=new float[]{(float)(offsetX+(p.lon*cos-minX)*scale),(float)(offsetY+(-p.lat-minY)*scale),p.track!=track?1:0};track=p.track;}
        return out;
    }

    public static Bitmap render(RunRecord run,RunAnalysis a,Format format,Theme theme){
        int w=format.width,h=format.height;
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(bitmap);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);Typeface medium=Typeface.create("sans-serif-medium",Typeface.NORMAL),regular=Typeface.create("sans-serif",Typeface.NORMAL),black=Typeface.create("sans-serif-black",Typeface.NORMAL);
        float m=72;
        c.drawColor(theme.background);
        // Soft accent glow in the corner.
        p.setShader(new RadialGradient(w*0.9f,h*0.08f,w*0.9f,(theme.accent&0x00ffffff)|0x30000000,theme.background&0x00ffffff,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,p);p.setShader(null);

        // Header.
        p.setTypeface(black);p.setTextSize(44);p.setColor(theme.accent);c.drawText("r/",m,m+40,p);
        float rx=m+p.measureText("r/ ");p.setTypeface(medium);p.setColor(theme.text);c.drawText("runzone",rx,m+40,p);
        p.setTypeface(regular);p.setTextSize(32);p.setColor(theme.muted);
        String date=new java.text.SimpleDateFormat("EEE d MMM yyyy",Locale.getDefault()).format(new Date(run.startTime)).toUpperCase(Locale.getDefault());
        c.drawText(date,w-m-p.measureText(date),m+38,p);

        // Distance hero.
        float y=m+40+(format==Format.SQUARE?110:150);
        p.setTypeface(black);p.setColor(theme.text);p.setTextSize(format==Format.SQUARE?170:210);
        String km=String.format(Locale.getDefault(),"%.2f",run.meters/1000);c.drawText(km,m-8,y+(format==Format.SQUARE?30:60),p);
        float kmw=p.measureText(km);p.setTypeface(medium);p.setTextSize(56);p.setColor(theme.accent);c.drawText("km",m+kmw+12,y+(format==Format.SQUARE?30:60),p);
        y+=format==Format.SQUARE?70:110;

        // Stats grid at the bottom, highlight above it.
        List<Stat> stats=stats(run,a);int columns=3,rows=(stats.size()+columns-1)/columns;
        float cell=format==Format.SQUARE?140:170,gridTop=h-m-rows*cell;
        String line=highlight(run,a);
        float routeBottom=gridTop-(line!=null?140:40);
        if(line!=null){
            // Pill: 32 px dot inset, 22 px gap, text, 32 px right padding.
            p.setTypeface(medium);p.setTextSize(34);float tw=Math.min(w-2*m,p.measureText(line)+86);
            p.setColor(theme.panel);c.drawRoundRect(m,routeBottom+18,m+tw,routeBottom+86,34,34,p);
            p.setColor(theme.accent);c.drawCircle(m+32,routeBottom+52,9,p);
            p.setColor(theme.text);c.drawText(ellipsize(line,p,tw-86),m+54,routeBottom+64,p);
        }

        // Route, or the heart-rate trace when there's no GPS.
        float boxTop=y+30,boxH=routeBottom-boxTop-20;
        float[][] route=boxH>120?fitRoute(run.points,m+20,boxTop,w-2*m-40,boxH):null;
        if(route!=null){
            Path path=new Path();boolean first=true;
            for(float[] pt:route){if(first||pt[2]>0){path.moveTo(pt[0],pt[1]);first=false;}else path.lineTo(pt[0],pt[1]);}
            p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor((theme.accent&0x00ffffff)|0x33000000);p.setStrokeWidth(30);c.drawPath(path,p);
            p.setColor(theme.accent);p.setStrokeWidth(10);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
            float[] s=route[0],e=route[route.length-1];
            p.setColor(theme.text);c.drawCircle(s[0],s[1],16,p);p.setColor(theme.background);c.drawCircle(s[0],s[1],8,p);
            p.setColor(theme.accent);c.drawCircle(e[0],e[1],18,p);p.setColor(theme.text);c.drawCircle(e[0],e[1],7,p);
        }else if(a.hasHeart&&boxH>120){
            double min=255,max=0;for(double v:a.heart)if(!Double.isNaN(v)){min=Math.min(min,v);max=Math.max(max,v);}
            if(max>min){
                Path path=new Path();boolean open=false;int stride=Math.max(1,a.n/600);
                for(int i=0;i<a.n;i+=stride){double v=a.heart[i];if(Double.isNaN(v)){open=false;continue;}
                    float px=m+(w-2*m)*i/(float)Math.max(1,a.n-1),py=(float)(boxTop+boxH-(v-min)/(max-min)*boxH*0.8f-boxH*0.1f);
                    if(open)path.lineTo(px,py);else path.moveTo(px,py);open=true;}
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(8);p.setStrokeJoin(Paint.Join.ROUND);p.setColor(theme.accent);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
                p.setTypeface(regular);p.setTextSize(30);p.setColor(theme.muted);c.drawText("♥ heart rate",m,boxTop+boxH+10,p);
            }
        }

        // Stats grid.
        p.setColor(theme.panel);c.drawRoundRect(m-24,gridTop-30,w-m+24,h-m+24,40,40,p);
        float colW=(w-2*m)/columns;
        for(int i=0;i<stats.size();i++){
            Stat s=stats.get(i);float x=m+(i%columns)*colW,top=gridTop+(i/columns)*cell;
            p.setTypeface(medium);p.setTextSize(26);p.setColor(theme.muted);p.setLetterSpacing(0.1f);c.drawText(s.label,x,top+28,p);p.setLetterSpacing(0);
            p.setTypeface(medium);p.setTextSize(format==Format.SQUARE?54:62);p.setColor(theme.text);c.drawText(s.value,x,top+(format==Format.SQUARE?88:100),p);
            p.setTypeface(regular);p.setTextSize(26);p.setColor(theme.muted);c.drawText(s.unit,x,top+(format==Format.SQUARE?122:140),p);
        }
        return bitmap;
    }

    private static String ellipsize(String text,Paint p,float width){
        if(p.measureText(text)<=width)return text;
        while(text.length()>1&&p.measureText(text+"…")>width)text=text.substring(0,text.length()-1);
        return text+"…";
    }
}
