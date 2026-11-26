package com.runzone;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;

/** Simple line or bar chart for analysis series. NaN values leave gaps. */
public class SeriesChart extends View {
    public interface Format{String label(double value);}
    private final double[] x,y;
    private double[] shade;
    private int color=0xffc2f970;
    private boolean bars,invert,zeroBased;
    private Format format=v->String.format(Locale.getDefault(),"%.0f",v);
    private String startLabel="Start",endLabel="Finish";
    private double bandLow=Double.NaN,bandHigh=Double.NaN,reference=Double.NaN;
    private int highlight=-1;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);

    public SeriesChart(Context context,double[] x,double[] y,String description){super(context);this.x=x;this.y=y;setContentDescription(description);}
    public SeriesChart color(int value){color=value;return this;}
    public SeriesChart bars(){bars=true;zeroBased=true;return this;}
    /** Puts lower values at the top, for pace. */
    public SeriesChart inverted(){invert=true;return this;}
    public SeriesChart format(Format value){format=value;return this;}
    public SeriesChart labels(String start,String end){startLabel=start;endLabel=end;return this;}
    public SeriesChart band(double low,double high){bandLow=low;bandHigh=high;return this;}
    public SeriesChart reference(double value){reference=value;return this;}
    public SeriesChart highlight(int index){highlight=index;return this;}
    /** Background area, scaled to its own range (for example elevation behind pace). */
    public SeriesChart shade(double[] values){shade=values;return this;}

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

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        double[] r=range(y,zeroBased);if(r==null||x.length==0)return;
        double min=r[0],max=r[1];
        if(Double.isFinite(reference)){min=Math.min(min,reference);max=Math.max(max,reference);}
        double x0=Double.POSITIVE_INFINITY,x1=Double.NEGATIVE_INFINITY;for(double v:x)if(Double.isFinite(v)){x0=Math.min(x0,v);x1=Math.max(x1,v);}
        if(bars){x0-=0.5;x1+=0.5;}if(x1<=x0)x1=x0+1;
        paint.setTextSize(dp(10));
        float labelWidth=0;for(int i=0;i<3;i++)labelWidth=Math.max(labelWidth,paint.measureText(format.label(max-(max-min)*i/2)));
        float left=labelWidth+dp(6),right=getWidth()-dp(6),top=dp(10),bottom=getHeight()-dp(22),width=right-left,height=bottom-top;
        final double fMin=min,fMax=max,fx0=x0,fx1=x1;
        java.util.function.DoubleUnaryOperator py=v->{double f=(v-fMin)/(fMax-fMin);if(invert)f=1-f;return bottom-height*f;};
        java.util.function.DoubleUnaryOperator px=v->left+width*(v-fx0)/(fx1-fx0);

        if(shade!=null){
            double[] sr=range(shade,false);
            if(sr!=null){
                Path area=new Path();boolean open=false;float lastX=left;int stride=Math.max(1,shade.length/600);
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
        paint.setStrokeWidth(dp(1));
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
        if(bars){
            float barWidth=Math.max(dp(2),(float)(width/(x1-x0))*0.7f);
            paint.setStyle(Paint.Style.FILL);
            for(int i=0;i<x.length;i++){
                if(!Double.isFinite(y[i]))continue;
                float cx=(float)px.applyAsDouble(x[i]),yy=(float)py.applyAsDouble(y[i]),base=(float)py.applyAsDouble(Math.max(min,0));
                paint.setColor(i==highlight?0xffffbc8a:color);
                canvas.drawRoundRect(cx-barWidth/2,Math.min(yy,base),cx+barWidth/2,Math.max(yy,base),dp(2),dp(2),paint);
            }
        }else{
            Path path=new Path();boolean open=false;int stride=Math.max(1,x.length/800);int points=0;
            for(int i=0;i<x.length;i+=stride){
                if(!Double.isFinite(y[i])||!Double.isFinite(x[i])){open=false;continue;}
                float px1=(float)px.applyAsDouble(x[i]),py1=(float)py.applyAsDouble(Math.max(min,Math.min(max,y[i])));
                if(open)path.lineTo(px1,py1);else path.moveTo(px1,py1);open=true;points++;
            }
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setColor(color);
            canvas.drawPath(path,paint);
            if(points<40){paint.setStyle(Paint.Style.FILL);for(int i=0;i<x.length;i++)if(Double.isFinite(y[i])){paint.setColor(i==highlight?0xffffbc8a:color);canvas.drawCircle((float)px.applyAsDouble(x[i]),(float)py.applyAsDouble(Math.max(min,Math.min(max,y[i]))),dp(i==highlight?4.5f:3),paint);}}
            paint.setStyle(Paint.Style.FILL);
        }
        paint.setColor(0xff9eafaa);
        canvas.drawText(startLabel,left,getHeight()-dp(4),paint);
        canvas.drawText(endLabel,right-paint.measureText(endLabel),getHeight()-dp(4),paint);
    }
}
