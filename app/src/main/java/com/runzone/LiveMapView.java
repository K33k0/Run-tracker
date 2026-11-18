package com.runzone;

import android.content.Context;
import android.graphics.*;
import android.location.Location;
import android.util.LruCache;
import android.view.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** A small native slippy map. Downloads only visible tiles; keeps a seven-day disk cache. */
public class LiveMapView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LruCache<String,Bitmap> tiles=new LruCache<String,Bitmap>(12*1024*1024){ @Override protected int sizeOf(String key,Bitmap bitmap){ return bitmap.getByteCount(); }};
    private final Set<String> pending=new HashSet<>();
    private final Map<String,Long> failures=new HashMap<>();
    private final ExecutorService network=Executors.newFixedThreadPool(3);
    private final File cache;
    private final ArrayList<Location> route=new ArrayList<>();
    private Location position;
    private double latitude,longitude;
    private int zoom=18;
    private boolean routeOverview;
    private boolean centered, follow=true,closed;
    private float touchX,touchY;
    private final ScaleGestureDetector scale;
    private float scaleAmount=1;
    private String emptyMessage="Allow location to find your starting point";
    public void fitRoute(List<Location> points){
        route.clear();route.addAll(points);emptyMessage="No GPS route was recorded for this run";follow=false;routeOverview=true;
        post(()->{
            if(closed)return;Location first=null;double minX=Double.MAX_VALUE,maxX=-Double.MAX_VALUE,minY=Double.MAX_VALUE,maxY=-Double.MAX_VALUE;
            for(Location point:route){if(point==null)continue;if(first==null)first=point;double lon=first.getLongitude()+(((point.getLongitude()-first.getLongitude()+180)%360+360)%360-180);double x=MapProjection.x(lon,19),y=MapProjection.y(point.getLatitude(),19);minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);position=point;}
            if(first==null){centered=false;invalidate();return;}
            if(getWidth()<=0||getHeight()<=0)return;
            zoom=MapProjection.routeZoom(maxX-minX,maxY-minY,getWidth()-dp(64),getHeight()-dp(64));
            longitude=MapProjection.longitude((minX+maxX)/2,19);latitude=MapProjection.latitude((minY+maxY)/2,19);centered=true;invalidate();
        });
    }
    public LiveMapView(Context context){
        super(context); cache=new File(context.getCacheDir(),"map-tiles"); cache.mkdirs();
        setContentDescription("Live street map with your location and recorded route. Drag to pan; pinch to zoom.");
        scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){ scaleAmount*=detector.getScaleFactor(); if(scaleAmount>1.4){ zoomBy(1); scaleAmount=1; } else if(scaleAmount<0.7){ zoomBy(-1); scaleAmount=1; } return true; }
            @Override public boolean onScaleBegin(ScaleGestureDetector detector){ scaleAmount=1; return true; }
        });
    }
    public void update(Location fix,List<Location> points){
        if(fix!=null){ position=new Location(fix); if(!centered){ latitude=fix.getLatitude(); longitude=fix.getLongitude(); centered=true; } }
        route.clear(); if(points!=null) route.addAll(points);
        if(follow)frameLiveRoute();
        invalidate();
    }
    private void frameLiveRoute(){
        if(getWidth()<=0||getHeight()<=0)return;
        Location anchor=position;
        if(anchor==null)for(Location point:route)if(point!=null){anchor=point;break;}
        if(anchor==null)return;
        double minX=MapProjection.x(anchor.getLongitude(),19),maxX=minX,minY=MapProjection.y(anchor.getLatitude(),19),maxY=minY;
        for(Location point:route){
            if(point==null)continue;
            double lon=anchor.getLongitude()+(((point.getLongitude()-anchor.getLongitude()+180)%360+360)%360-180);
            double x=MapProjection.x(lon,19),y=MapProjection.y(point.getLatitude(),19);
            minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
        }
        zoom=MapProjection.routeZoom(maxX-minX,maxY-minY,getWidth()-dp(64),getHeight()-dp(64));
        longitude=MapProjection.longitude((minX+maxX)/2,19);latitude=MapProjection.latitude((minY+maxY)/2,19);centered=true;
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        super.onSizeChanged(w,h,oldw,oldh);
        if(follow)frameLiveRoute();else if(routeOverview)fitRoute(new ArrayList<>(route));
    }
    public void recenter(){ follow=true;routeOverview=false;frameLiveRoute();invalidate(); }
    public void zoomBy(int amount){ follow=false;routeOverview=false;zoom=Math.max(3,Math.min(19,zoom+amount)); invalidate(); }
    public void close(){ closed=true; network.shutdownNow(); }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas); canvas.drawColor(Color.rgb(222,231,218));
        if(!centered){
            paint.setColor(Color.rgb(195,208,191)); paint.setStrokeWidth(1);
            for(int x=0;x<getWidth();x+=60) canvas.drawLine(x,0,x,getHeight(),paint);
            for(int y=0;y<getHeight();y+=60) canvas.drawLine(0,y,getWidth(),y,paint);
            paint.setTextSize(dp(14)); paint.setColor(Color.rgb(60,80,66)); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(emptyMessage,getWidth()/2f,getHeight()/2f,paint); paint.setTextAlign(Paint.Align.LEFT); return;
        }
        double cx=MapProjection.x(longitude,zoom),cy=MapProjection.y(latitude,zoom);
        double left=cx-getWidth()/2d,top=cy-getHeight()/2d;
        int world=1<<zoom;
        int firstX=(int)Math.floor(left/256),firstY=(int)Math.floor(top/256);
        for(int x=firstX;x<=Math.floor((left+getWidth())/256);x++) for(int y=firstY;y<=Math.floor((top+getHeight())/256);y++){
            if(y<0||y>=world)continue;
            int wrapped=Math.floorMod(x,world); String key=zoom+"_"+wrapped+"_"+y;
            Bitmap bitmap=tiles.get(key);
            float px=(float)(x*256d-left),py=(float)(y*256d-top);
            if(bitmap!=null){ paint.setAlpha(255); canvas.drawBitmap(bitmap,null,new RectF(px,py,px+256,py+256),paint); }
            else { paint.setColor(Color.rgb(211,222,207)); canvas.drawRect(px,py,px+255,py+255,paint); load(key,zoom,wrapped,y); }
        }
        Path path=new Path(); boolean segment=false;
        for(Location location:route){ if(location==null){segment=false;continue;} float x=screenX(location.getLongitude(),left,world),y=(float)(MapProjection.y(location.getLatitude(),zoom)-top); if(!segment){path.moveTo(x,y);segment=true;}else path.lineTo(x,y); }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Color.WHITE); paint.setStrokeWidth(dp(7)); canvas.drawPath(path,paint);
        paint.setColor(Color.rgb(49,130,80)); paint.setStrokeWidth(dp(4)); canvas.drawPath(path,paint); paint.setStyle(Paint.Style.FILL);
        if(position!=null){
            float x=screenX(position.getLongitude(),left,world),y=(float)(MapProjection.y(position.getLatitude(),zoom)-top);
            paint.setColor(Color.argb(45,49,130,80)); canvas.drawCircle(x,y,dp(22),paint);
            paint.setColor(Color.WHITE); canvas.drawCircle(x,y,dp(10),paint);
            paint.setColor(Color.rgb(40,126,73)); canvas.drawCircle(x,y,dp(6),paint);
        }
        boolean unavailable=false; for(Long failure:failures.values()) if(System.currentTimeMillis()-failure<30000){unavailable=true;break;}
        if(unavailable){ paint.setColor(Color.argb(220,255,255,255)); canvas.drawRoundRect(dp(12),getHeight()-dp(57),getWidth()-dp(12),getHeight()-dp(28),dp(8),dp(8),paint); paint.setColor(Color.rgb(50,70,55)); paint.setTextSize(dp(11)); canvas.drawText("Map unavailable • check your internet connection",dp(20),getHeight()-dp(38),paint); }
    }
    private float screenX(double lon,double left,int world){ double x=MapProjection.x(lon,zoom)-left; double width=world*256d; while(x>getWidth()/2d+width/2)x-=width; while(x<getWidth()/2d-width/2)x+=width; return (float)x; }
    private void load(String key,int z,int x,int y){
        if(closed||pending.contains(key)||pending.size()>24)return;
        Long failed=failures.get(key); if(failed!=null&&System.currentTimeMillis()-failed<30000)return;
        pending.add(key);
        network.execute(()->{
            Bitmap bitmap=null; HttpURLConnection connection=null;
            try{
                File file=new File(cache,key+".png");
                boolean valid=file.exists()&&System.currentTimeMillis()-file.lastModified()<7L*24*60*60*1000;
                if(!valid){
                    connection=(HttpURLConnection)new URL("https://tile.openstreetmap.org/"+z+"/"+x+"/"+y+".png").openConnection();
                    connection.setRequestProperty("User-Agent","RunZone/1.1 (com.runzone; Android live running map)");
                    connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
                    if(file.exists()) connection.setIfModifiedSince(file.lastModified());
                    int response=connection.getResponseCode();
                    if(response==304) file.setLastModified(System.currentTimeMillis());
                    else if(response==200){
                        File temp=new File(cache,key+".tmp");
                        try(InputStream input=connection.getInputStream(); OutputStream output=new FileOutputStream(temp)){ byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count); }
                        if(!temp.renameTo(file)){ try(InputStream input=new FileInputStream(temp);OutputStream output=new FileOutputStream(file)){ byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count); } temp.delete(); }
                    } else throw new IOException("Tile unavailable");
                }
                bitmap=BitmapFactory.decodeFile(file.getAbsolutePath());
            }catch(IOException ignored){}finally{ if(connection!=null)connection.disconnect(); }
            final Bitmap result=bitmap;
            post(()->{ pending.remove(key); if(closed)return; if(result!=null){tiles.put(key,result);failures.remove(key);}else failures.put(key,System.currentTimeMillis()); invalidate(); });
        });
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        scale.onTouchEvent(event);
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN: routeOverview=false;touchX=event.getX();touchY=event.getY();getParent().requestDisallowInterceptTouchEvent(true);return true;
            case MotionEvent.ACTION_MOVE:
                if(!scale.isInProgress()&&event.getPointerCount()==1&&centered){ follow=false; double x=MapProjection.x(longitude,zoom)+touchX-event.getX(),y=MapProjection.y(latitude,zoom)+touchY-event.getY(); longitude=MapProjection.longitude(x,zoom); longitude=((longitude+180)%360+360)%360-180; latitude=Math.max(-85,Math.min(85,MapProjection.latitude(y,zoom))); invalidate(); }
                touchX=event.getX();touchY=event.getY();return true;
            case MotionEvent.ACTION_UP: performClick();getParent().requestDisallowInterceptTouchEvent(false);return true;
            case MotionEvent.ACTION_CANCEL: getParent().requestDisallowInterceptTouchEvent(false);return true;
            default:return true;
        }
    }
    @Override public boolean performClick(){ super.performClick(); return true; }
    private float dp(float n){ return n*getResources().getDisplayMetrics().density; }
}
