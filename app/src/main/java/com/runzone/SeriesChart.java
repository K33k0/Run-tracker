package com.runzone;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

/**
 * Line or bar chart for analysis series. NaN values leave gaps.
 * Touch and drag sideways to read values; pinch to zoom and pan with two fingers; double-tap to reset.
 */
public class SeriesChart extends View {
    public interface Format{String label(double value);}
    private final double[] x,y;
    private double[] shade;
    private int color=0xffc2f970;
    private boolean bars,invert,zeroBased;
    private Format format=v->String.format(Locale.getDefault(),"%.0f",v);
    private Format xFormat=SeriesChart::clock;
    private String startLabel="Start",endLabel="Finish";
    private double bandLow=Double.NaN,bandHigh=Double.NaN,reference=Double.NaN;
    private int highlight=-1;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Visible x range when zoomed; NaN shows everything. */
    private double viewStart=Double.NaN,viewEnd=Double.NaN;
    private int cursor=-1;
    private float downX,downY,lastFocus;
    private boolean scrubbing,scaling;
    private final int slop;
    private final ScaleGestureDetector scaler;
    private final GestureDetector taps;
    // Plot geometry from the last draw, for touch mapping.
    private float plotLeft,plotWidth;
    private double drawX0,drawX1;

    public SeriesChart(Context context,double[] x,double[] y,String description){
        super(context);this.x=x;this.y=y;setContentDescription(description+". Drag to read values, pinch to zoom, double-tap to reset.");
        slop=ViewConfiguration.get(context).getScaledTouchSlop();
        scaler=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScaleBegin(ScaleGestureDetector d){if(bars||plotWidth<=0)return false;scaling=true;lastFocus=d.getFocusX();cursor=-1;return true;}
            @Override public boolean onScale(ScaleGestureDetector d){zoom(d.getScaleFactor(),d.getFocusX(),d.getFocusX()-lastFocus);lastFocus=d.getFocusX();return true;}
        });
        taps=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDoubleTap(MotionEvent e){viewStart=viewEnd=Double.NaN;cursor=-1;invalidate();return true;}
            @Override public boolean onSingleTapConfirmed(MotionEvent e){cursor=cursor>=0?-1:nearest(e.getX());invalidate();return true;}
        });
    }
    public SeriesChart color(int value){color=value;return this;}
    public SeriesChart bars(){bars=true;zeroBased=true;return this;}
    /** Puts lower values at the top, for pace. */
    public SeriesChart inverted(){invert=true;return this;}
    public SeriesChart format(Format value){format=value;return this;}
    /** How x values read in the touch readout; defaults to elapsed seconds as a clock. */
    public SeriesChart xFormat(Format value){xFormat=value;return this;}
    public SeriesChart labels(String start,String end){startLabel=start;endLabel=end;return this;}
    public SeriesChart band(double low,double high){bandLow=low;bandHigh=high;return this;}
    public SeriesChart reference(double value){reference=value;return this;}
    public SeriesChart highlight(int index){highlight=index;return this;}
    /** Background area, scaled to its own range (for example elevation behind pace). */
    public SeriesChart shade(double[] values){shade=values;return this;}

    static String clock(double seconds){long s=Math.round(seconds);return s>=3600?String.format(Locale.getDefault(),"%d:%02d:%02d",s/3600,(s/60)%60,s%60):String.format(Locale.getDefault(),"%d:%02d",s/60,s%60);}
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}

    /** Range from the 2nd to 98th percentile so GPS spikes don't flatten the chart. */
    static double[] range(double[] values,boolean zeroBased){
        ArrayList<Double> finite=new ArrayList<>();for(double v:values)if(Double.isFinite(v))finite.add(v);
        if(finite.isEmpty())return null;Collections.sort(finite);
        double min=zeroBased?Math.min(0,finite.get(0)):finite.get((int)(finite.size()*0.02)),max=finite.get(Math.min(finite.size()-1,(int)(finite.size()*0.98)));
        if(zeroBased)max=finite.get(finite.size()-1);
        if(max-min<1e-9){min-=1;max+=1;}
        double pad=zeroBased?0:(max-min)*0.08;return new double[]{min-pad,max+(max-min)*0.08};
    }
    private double[] fullX(){
        double x0=Double.POSITIVE_INFINITY,x1=Double.NEGATIVE_INFINITY;for(double v:x)if(Double.isFinite(v)){x0=Math.min(x0,v);x1=Math.max(x1,v);}
        if(bars){x0-=0.5;x1+=0.5;}if(!(x1>x0))x1=x0+1;return new double[]{x0,x1};
    }

    private void zoom(float factor,float focusPx,float panPx){
        double[] full=fullX();double start=Double.isNaN(viewStart)?full[0]:viewStart,end=Double.isNaN(viewEnd)?full[1]:viewEnd;
        double span=end-start,focus=start+span*(focusPx-plotLeft)/plotWidth;
        double newSpan=Math.max((full[1]-full[0])/40,Math.min(full[1]-full[0],span/factor));
        double f=(focus-start)/span,newStart=focus-newSpan*f-panPx/plotWidth*newSpan;
        newStart=Math.max(full[0],Math.min(full[1]-newSpan,newStart));
        if(newSpan>=full[1]-full[0]-1e-9){viewStart=viewEnd=Double.NaN;}else{viewStart=newStart;viewEnd=newStart+newSpan;}
        invalidate();
    }
    private int nearest(float px){
        if(plotWidth<=0)return -1;double target=drawX0+(drawX1-drawX0)*(px-plotLeft)/plotWidth;
        int best=-1;double gap=Double.POSITIVE_INFINITY;
        for(int i=0;i<x.length;i++)if(Double.isFinite(x[i])&&Double.isFinite(y[i])){double d=Math.abs(x[i]-target);if(d<gap){gap=d;best=i;}}
        return best;
    }

    @Override public boolean onTouchEvent(MotionEvent e){
        scaler.onTouchEvent(e);taps.onTouchEvent(e);
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:downX=e.getX();downY=e.getY();scrubbing=false;scaling=false;return true;
            case MotionEvent.ACTION_POINTER_DOWN:getParent().requestDisallowInterceptTouchEvent(true);return true;
            case MotionEvent.ACTION_MOVE:
                if(scaling||e.getPointerCount()>1)return true;
                if(!scrubbing&&Math.abs(e.getX()-downX)>slop&&Math.abs(e.getX()-downX)>Math.abs(e.getY()-downY)){scrubbing=true;getParent().requestDisallowInterceptTouchEvent(true);}
                if(scrubbing){cursor=nearest(e.getX());invalidate();}
                return scrubbing||Math.abs(e.getY()-downY)<=slop;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:getParent().requestDisallowInterceptTouchEvent(false);scrubbing=false;return true;
        }
        return true;
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        double[] r=range(y,zeroBased);if(r==null||x.length==0)return;
        double min=r[0],max=r[1];
        if(Double.isFinite(reference)){min=Math.min(min,reference);max=Math.max(max,reference);}
        double[] full=fullX();double x0=Double.isNaN(viewStart)?full[0]:viewStart,x1=Double.isNaN(viewEnd)?full[1]:viewEnd;
        paint.setTextSize(dp(10));
        float labelWidth=0;for(int i=0;i<3;i++)labelWidth=Math.max(labelWidth,paint.measureText(format.label(max-(max-min)*i/2)));
        float left=labelWidth+dp(6),right=getWidth()-dp(6),top=dp(10),bottom=getHeight()-dp(22),width=right-left,height=bottom-top;
        plotLeft=left;plotWidth=width;drawX0=x0;drawX1=x1;
        final double fMin=min,fMax=max,fx0=x0,fx1=x1;
        java.util.function.DoubleUnaryOperator py=v->{double f=(v-fMin)/(fMax-fMin);if(invert)f=1-f;return bottom-height*f;};
        java.util.function.DoubleUnaryOperator px=v->left+width*(v-fx0)/(fx1-fx0);
        int visible=0;for(double v:x)if(v>=x0&&v<=x1)visible++;
        int stride=Math.max(1,visible/Math.max(200,(int)width));

        canvas.save();canvas.clipRect(left-dp(5),0,right+dp(5),getHeight());
        if(shade!=null){
            double[] sr=range(shade,false);
            if(sr!=null){
                Path area=new Path();boolean open=false;float lastX=left;
                for(int i=0;i<shade.length&&i<x.length;i+=stride){
                    float sx=(float)px.applyAsDouble(x[i]);
                    if(!Double.isFinite(shade[i])){if(open){area.lineTo(lastX,bottom);area.close();open=false;}continue;}
                    float sy=(float)(bottom-height*0.45*(shade[i]-sr[0])/(sr[1]-sr[0]));
                    if(!open){area.moveTo(sx,bottom);open=true;}
                    area.lineTo(sx,sy);lastX=sx;
                }
                if(open){area.lineTo(lastX,bottom);area.close();}
                paint.setStyle(Paint.Style.FILL);paint.setColor(0x2a9eafaa);canvas.drawPath(area,paint);
            }
        }
        canvas.restore();
        paint.setStrokeWidth(dp(1));paint.setStyle(Paint.Style.FILL);
        for(int i=0;i<3;i++){
            double v=max-(max-min)*i/2;float yy=(float)py.applyAsDouble(v);
            paint.setColor(0xff30403e);canvas.drawLine(left,yy,right,yy,paint);
            paint.setColor(0xff9eafaa);canvas.drawText(format.label(v),0,yy+dp(3),paint);
        }
        if(Double.isFinite(bandLow)&&Double.isFinite(bandHigh)&&bandHigh>bandLow){
            float a=(float)py.applyAsDouble(Math.max(min,Math.min(max,bandLow))),b=(float)py.applyAsDouble(Math.max(min,Math.min(max,bandHigh)));
            paint.setColor(0x183ac770);canvas.drawRect(left,Math.min(a,b),right,Math.max(a,b),paint);
        }
        if(Double.isFinite(reference)){
            float yy=(float)py.applyAsDouble(reference);paint.setColor(0x809eafaa);paint.setPathEffect(new DashPathEffect(new float[]{dp(4),dp(4)},0));
            canvas.drawLine(left,yy,right,yy,paint);paint.setPathEffect(null);
        }
        canvas.save();canvas.clipRect(left-dp(5),0,right+dp(5),getHeight());
        if(bars){
            float barWidth=Math.max(dp(2),(float)(width/(x1-x0))*0.7f);
            for(int i=0;i<x.length;i++){
                if(!Double.isFinite(y[i]))continue;
                float cx=(float)px.applyAsDouble(x[i]),yy=(float)py.applyAsDouble(y[i]),base=(float)py.applyAsDouble(Math.max(min,0));
                paint.setColor(i==highlight||i==cursor?0xffffbc8a:color);
                canvas.drawRoundRect(cx-barWidth/2,Math.min(yy,base),cx+barWidth/2,Math.max(yy,base),dp(2),dp(2),paint);
            }
        }else{
            Path path=new Path();boolean open=false;int points=0;
            for(int i=0;i<x.length;i+=stride){
                if(!Double.isFinite(y[i])||!Double.isFinite(x[i])){open=false;continue;}
                if(x[i]<x0-(x1-x0)*0.05||x[i]>x1+(x1-x0)*0.05){open=false;continue;}
                float px1=(float)px.applyAsDouble(x[i]),py1=(float)py.applyAsDouble(Math.max(min,Math.min(max,y[i])));
                if(open)path.lineTo(px1,py1);else path.moveTo(px1,py1);open=true;points++;
            }
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setColor(color);
            canvas.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);
            if(points<40)for(int i=0;i<x.length;i++)if(Double.isFinite(y[i])){paint.setColor(i==highlight?0xffffbc8a:color);canvas.drawCircle((float)px.applyAsDouble(x[i]),(float)py.applyAsDouble(Math.max(min,Math.min(max,y[i]))),dp(i==highlight?4.5f:3),paint);}
        }
        canvas.restore();
        paint.setColor(0xff9eafaa);
        boolean zoomed=!Double.isNaN(viewStart);
        String startText=zoomed?xFormat.label(x0):startLabel,endText=zoomed?xFormat.label(x1)+"  · double-tap to reset":endLabel;
        canvas.drawText(startText,left,getHeight()-dp(4),paint);
        canvas.drawText(endText,right-paint.measureText(endText),getHeight()-dp(4),paint);

        if(cursor>=0&&cursor<x.length&&Double.isFinite(y[cursor])&&x[cursor]>=x0&&x[cursor]<=x1){
            float cx=(float)px.applyAsDouble(x[cursor]),cy=(float)py.applyAsDouble(Math.max(min,Math.min(max,y[cursor])));
            paint.setColor(0x99f2f5ee);paint.setStrokeWidth(dp(1));canvas.drawLine(cx,top,cx,bottom,paint);
            paint.setColor(0xfff2f5ee);canvas.drawCircle(cx,cy,dp(4),paint);
            String label=xFormat.label(x[cursor])+"  ·  "+format.label(y[cursor]);
            paint.setTextSize(dp(12));float tw=paint.measureText(label)+dp(16),th=dp(24);
            float bx=Math.max(left,Math.min(right-tw,cx-tw/2)),by=top;
            paint.setColor(0xf0263c2c);canvas.drawRoundRect(bx,by,bx+tw,by+th,dp(8),dp(8),paint);
            paint.setColor(0xfff2f5ee);canvas.drawText(label,bx+dp(8),by+th/2+dp(4),paint);
        }
    }
}
