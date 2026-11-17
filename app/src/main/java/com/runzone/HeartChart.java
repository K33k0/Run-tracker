package com.runzone;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;

public class HeartChart extends View {
    private final RunRecord run;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public HeartChart(Context context,RunRecord run){super(context);this.run=run;setContentDescription("Recorded heart-rate chart. Average "+run.averageBpm()+", maximum "+run.maxBpm()+" beats per minute.");}
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(run.pulses.isEmpty())return;
        int min=255,max=0;for(RunRecord.Pulse p:run.pulses){min=Math.min(min,p.bpm);max=Math.max(max,p.bpm);}min=Math.max(0,min-8);max+=8;
        float left=dp(30),right=getWidth()-dp(8),top=dp(12),bottom=getHeight()-dp(24),width=right-left,height=bottom-top;
        paint.setTextSize(dp(10));paint.setStrokeWidth(dp(1));
        for(int i=0;i<3;i++){float y=top+height*i/2;paint.setColor(0xff30403e);canvas.drawLine(left,y,right,y,paint);paint.setColor(0xff9eafaa);canvas.drawText(Integer.toString(max-(max-min)*i/2),0,y+dp(3),paint);}
        if(run.low>0&&run.high>run.low){float highY=top+height*(max-Math.min(max,run.high))/(max-min),lowY=top+height*(max-Math.max(min,run.low))/(max-min);if(lowY>highY){paint.setColor(0x183ac770);canvas.drawRect(left,Math.max(top,highY),right,Math.min(bottom,lowY),paint);}}
        long start=run.pulses.get(0).time,end=run.pulses.get(run.pulses.size()-1).time,span=Math.max(1,end-start);
        Path path=new Path();RunRecord.Pulse previous=null;int stride=Math.max(1,run.pulses.size()/1000);
        for(int i=0;i<run.pulses.size();i+=stride){RunRecord.Pulse p=run.pulses.get(i);float x=left+width*(p.time-start)/span,y=top+height*(max-p.bpm)/(max-min);if(previous==null||previous.track!=p.track||p.time-previous.time>10000L*stride)path.moveTo(x,y);else path.lineTo(x,y);previous=p;}
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(0xffc2f970);canvas.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff9eafaa);canvas.drawText("Start",left,getHeight()-dp(4),paint);String label="Finish";canvas.drawText(label,right-paint.measureText(label),getHeight()-dp(4),paint);
    }
}
