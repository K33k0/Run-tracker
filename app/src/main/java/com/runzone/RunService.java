package com.runzone;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.location.*;
import android.media.*;
import android.os.*;
import org.json.*;
import java.util.*;

public class RunService extends Service implements LocationListener {
    public static volatile RunService current;
    public volatile int bpm = -1;
    public double meters;
    public boolean paused;
    public boolean running;
    public boolean autoPaused;
    private final AutoPause autoPause=new AutoPause();
    private double recentSpeed=Double.NaN;
    private long speedAt,segmentStarted;
    private final Runnable paceCheck=new Runnable(){public void run(){
        if(running){
            android.content.SharedPreferences prefs=getSharedPreferences("settings",0);
            double speed=SystemClock.elapsedRealtime()-speedAt<=5000?recentSpeed:Double.NaN;
            int change=autoPause.accept(prefs.getBoolean("autoPause",false),paused&&!autoPaused,autoPaused,prefs.getInt("pausePace",720),speed,SystemClock.elapsedRealtime());
            if(change<0)setPaused(true,true);else if(change>0)setPaused(false,false);
        }
        handler.postDelayed(this,1000);
    }};
    public Location lastFix;
    public final ArrayList<Location> route = new ArrayList<>();
    public volatile String sensor = "No heart-rate sensor", gps = "Waiting for GPS";
    public long started, pausedAt, pausedTotal, lastHr;
    private long startedAt;
    private int trackIndex,zoneLow,zoneHigh;
    private final RouteDistance distanceTracker=new RouteDistance();
    private LocationManager locations;
    private volatile BluetoothGatt gatt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONArray points = new JSONArray();
    private JSONArray heartSamples = new JSONArray();
    private final ZoneCoach coach=new ZoneCoach();
    private ToneGenerator tone;
    private Notification notification;
    static final UUID HR = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    static final UUID MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    @Override public void onCreate() {
        super.onCreate(); current = this;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("run", "Active run", NotificationManager.IMPORTANCE_LOW));
        locations = getSystemService(LocationManager.class);
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC,85); } catch (RuntimeException ignored) {}
        handler.post(paceCheck);
    }
    private void promote(boolean bluetooth) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        notification=new Notification.Builder(this,"run").setContentTitle(running?"RunZone • Run in progress":"RunZone • Live heart rate")
            .setContentText(running?"Recording your route and coaching your heart rate":"Sensor connected • no run is being recorded")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(open).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29) {
            int types=running?ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:0;
            if(bluetooth) types|=ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            startForeground(1,notification,types);
        } else startForeground(1,notification);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent==null) { stopSelf(); return START_NOT_STICKY; }
        if("START".equals(intent.getAction()) && !running) {
            running=true; paused=false; started=SystemClock.elapsedRealtime(); pausedTotal=0; meters=0;
            startedAt=System.currentTimeMillis();trackIndex=0;
            autoPaused=false;autoPause.reset();recentSpeed=Double.NaN;segmentStarted=started;
            zoneLow=getSharedPreferences("settings",0).getInt("low",120);zoneHigh=getSharedPreferences("settings",0).getInt("high",150);
            coach.reset(); distanceTracker.reset(); route.clear(); points=new JSONArray();heartSamples=new JSONArray();
            promote(gatt!=null || intent.hasExtra("device"));
            try { locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this); }
            catch(SecurityException | IllegalArgumentException e) { gps="GPS unavailable • check Settings"; }
        }
        if(intent.hasExtra("device")) { promote(true); connect(intent.getStringExtra("device")); }
        return START_NOT_STICKY;
    }
    public long elapsed() { return !running?0:(paused ? pausedAt : SystemClock.elapsedRealtime()) - started - pausedTotal; }
    public void finishRun() {
        running=false; paused=false; autoPaused=false;autoPause.reset();coach.reset(); distanceTracker.reset();
        if(locations!=null) locations.removeUpdates(this);
        if(gatt!=null) promote(true); else stopSelf();
    }
    public void disconnectSensor() {
        if(gatt!=null) { try { gatt.disconnect(); gatt.close(); } catch(SecurityException ignored){} gatt=null; }
        bpm=-1; sensor="No sensor connected"; coach.reset();
        if(running) promote(false); else stopSelf();
    }
    public void togglePause() {
        setPaused(!paused,false);
    }
    private void setPaused(boolean value,boolean automatic) {
        if(paused==value)return;
        if (paused) {pausedTotal += SystemClock.elapsedRealtime()-pausedAt;trackIndex++;} else pausedAt=SystemClock.elapsedRealtime();
        paused=value;autoPaused=value&&automatic;segmentStarted=SystemClock.elapsedRealtime();distanceTracker.reset();coach.reset();autoPause.reset();route.add(null);
    }
    @Override public void onLocationChanged(Location location) {
        if (!running) return;
        if (!location.hasAccuracy() || location.getAccuracy()>30 || SystemClock.elapsedRealtimeNanos()-location.getElapsedRealtimeNanos()>15_000_000_000L) { gps="Weak GPS signal"; distanceTracker.reset(); return; }
        gps="GPS connected • ±"+(int)location.getAccuracy()+" m";
        if(location.getAccuracy()<=15&&location.hasSpeed()&&(!location.hasSpeedAccuracy()||location.getSpeedAccuracyMetersPerSecond()<=.8f)){
            recentSpeed=location.getSpeed();speedAt=SystemClock.elapsedRealtime();
        }else{recentSpeed=Double.NaN;}
        lastFix=new Location(location);
        if(paused) return;
        if(location.getElapsedRealtimeNanos()/1000000<segmentStarted)return;
        meters+=distanceTracker.accept(location.getLatitude(),location.getLongitude(),location.getElapsedRealtimeNanos()/1000000);
        route.add(new Location(location));
        try {
            JSONObject point=new JSONObject().put("lat",location.getLatitude()).put("lon",location.getLongitude()).put("time",location.getTime()).put("bpm",freshBpm()).put("segment",route.size()>1&&route.get(route.size()-2)==null)
                .put("track",trackIndex).put("activeMs",elapsed()).put("distanceMeters",meters);
            if(location.hasAltitude())point.put("altitude",location.getAltitude());points.put(point);
        } catch(JSONException ignored) {}
    }
    @Override public void onProviderDisabled(String provider) { gps="GPS turned off"; distanceTracker.reset(); }
    @Override public void onProviderEnabled(String provider) { gps="Waiting for GPS"; }
    public int freshBpm() { return SystemClock.elapsedRealtime()-lastHr>10000 ? -1 : bpm; }
    private void receive(byte[] bytes) { handler.post(()-> {
        if(current!=this) return;
        bpm=HeartRate.decode(bytes); lastHr=SystemClock.elapsedRealtime();
        if(running&&!paused&&RunRecord.validBpm(bpm))try{heartSamples.put(new JSONObject().put("time",System.currentTimeMillis()).put("activeMs",elapsed()).put("bpm",bpm).put("track",trackIndex));}catch(JSONException ignored){}
        android.content.SharedPreferences prefs=getSharedPreferences("settings",0);
        int zone=coach.accept(running&&!paused,bpm,prefs.getInt("low",120),prefs.getInt("high",150),lastHr);
        if(zone!=0) {
            if(tone!=null) tone.startTone(zone>0 ? ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD : ToneGenerator.TONE_PROP_BEEP2,700);
            Vibrator vibrator=(Vibrator)getSystemService(VIBRATOR_SERVICE);
            if(vibrator!=null) vibrator.vibrate(VibrationEffect.createWaveform(zone>0 ? new long[]{0,400,150,400} : new long[]{0,200,150,200},-1));
        }
    }); }
    private void connect(String address) {
        sensor="Connecting…";
        try {
            if(gatt!=null) { gatt.disconnect(); gatt.close(); }
            bpm=-1; lastHr=0;
            BluetoothAdapter adapter=getSystemService(BluetoothManager.class).getAdapter();
            if(adapter==null || !adapter.isEnabled()) { sensor="Bluetooth is off"; return; }
            BluetoothDevice device=adapter.getRemoteDevice(address);
            gatt=device.connectGatt(this,false,new BluetoothGattCallback(){
                @Override public void onConnectionStateChange(BluetoothGatt connection,int status,int state) {
                    handler.post(()->{
                        if(connection!=gatt) return;
                        try {
                            if(state==BluetoothProfile.STATE_CONNECTED && status==BluetoothGatt.GATT_SUCCESS) { sensor="Discovering heart-rate service…"; connection.discoverServices(); }
                            else { sensor="Sensor disconnected • tap to reconnect"; bpm=-1; }
                        } catch(SecurityException e) { sensor="Nearby devices permission needed"; }
                    });
                }
                @Override public void onServicesDiscovered(BluetoothGatt connection,int status) {
                    handler.post(()->{
                    if(connection!=gatt) return;
                    try {
                    BluetoothGattService service=connection.getService(HR);
                    BluetoothGattCharacteristic characteristic=service==null?null:service.getCharacteristic(MEASUREMENT);
                    BluetoothGattDescriptor descriptor=characteristic==null?null:characteristic.getDescriptor(CCC);
                    if(status!=0 || descriptor==null || !connection.setCharacteristicNotification(characteristic,true)) { sensor="Heart-rate notifications unavailable"; return; }
                    if(Build.VERSION.SDK_INT>=33) {
                        if(connection.writeDescriptor(descriptor,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)!=BluetoothStatusCodes.SUCCESS) sensor="Could not subscribe to sensor";
                    } else {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        if(!connection.writeDescriptor(descriptor)) sensor="Could not subscribe to sensor";
                    }
                    } catch(SecurityException e) { sensor="Nearby devices permission needed"; }
                    });
                }
                @Override public void onDescriptorWrite(BluetoothGatt connection,BluetoothGattDescriptor descriptor,int status) { if(connection==gatt) sensor=status==0?"Heart-rate sensor connected":"Sensor subscription failed"; }
                @Override public void onCharacteristicChanged(BluetoothGatt connection,BluetoothGattCharacteristic characteristic) { if(connection==gatt&&MEASUREMENT.equals(characteristic.getUuid())) receive(characteristic.getValue()); }
                @Override public void onCharacteristicChanged(BluetoothGatt connection,BluetoothGattCharacteristic characteristic,byte[] value) { if(connection==gatt&&MEASUREMENT.equals(characteristic.getUuid())) receive(value); }
            },BluetoothDevice.TRANSPORT_LE);
        } catch(SecurityException | IllegalArgumentException e) { sensor="Bluetooth permission unavailable"; }
    }
    public boolean save() {
        try {
            java.io.File dir=new java.io.File(getFilesDir(),"runs"); if(!dir.exists() && !dir.mkdirs()) return false;
            JSONObject run=new JSONObject().put("schemaVersion",2).put("date",startedAt).put("startTime",startedAt).put("endTime",System.currentTimeMillis())
                .put("meters",meters).put("duration",elapsed()).put("route",points).put("heartRate",heartSamples).put("zoneLow",zoneLow).put("zoneHigh",zoneHigh);
            java.io.File file=new java.io.File(dir,System.currentTimeMillis()+".json");
            try(java.io.OutputStreamWriter writer=new java.io.OutputStreamWriter(new java.io.FileOutputStream(file),java.nio.charset.StandardCharsets.UTF_8)) { writer.write(run.toString()); }
            CloudSync.enqueueNew(this,file);
            return true;
        } catch(Exception e) { return false; }
    }
    @Override public void onDestroy() {
        if(locations!=null) locations.removeUpdates(this);
        if(gatt!=null) { try { gatt.disconnect(); gatt.close(); } catch(SecurityException ignored) {} }
        handler.removeCallbacksAndMessages(null); if(tone!=null) tone.release(); current=null; super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
