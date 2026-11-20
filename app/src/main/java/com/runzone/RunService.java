package com.runzone;

import android.app.*;
import android.Manifest;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.*;
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
    private Location speedAnchor;
    private long speedAt,segmentStarted;
    private final Runnable paceCheck=new Runnable(){public void run(){
        long now=SystemClock.elapsedRealtime();
        if(sensorAddress!=null&&gatt!=null&&now-(lastHr>0?lastHr:sensorAttemptAt)>30000)retrySensor();
        if(running){
            if(runWakeLock!=null&&now-wakeRenewedAt>=60000){runWakeLock.acquire(120000);wakeRenewedAt=now;}
            android.content.SharedPreferences prefs=getSharedPreferences("settings",0);
            double speed=SystemClock.elapsedRealtime()-speedAt<=5000?recentSpeed:Double.NaN;
            int change=autoPause.accept(prefs.getBoolean("autoPause",false),paused&&!autoPaused,autoPaused,prefs.getInt("pausePace",720),speed,SystemClock.elapsedRealtime());
            if(change<0)setPaused(true,true);else if(change>0)setPaused(false,false);
        }
        if(running&&now-draftSavedAt>=DRAFT_INTERVAL_MS){draftSavedAt=now;saveDraft();}
        handler.postDelayed(this,1000);
    }};
    static final long DRAFT_INTERVAL_MS=30000;
    private long draftSavedAt;
    private final java.util.concurrent.ExecutorService draftWriter=java.util.concurrent.Executors.newSingleThreadExecutor();
    static java.io.File draftFile(Context c){return new java.io.File(c.getFilesDir(),"run-draft.json");}
    private void saveDraft(){
        final String json;
        try{json=snapshot().put("recovered",true).toString();}catch(JSONException e){return;}
        final java.io.File target=draftFile(this);
        draftWriter.execute(()->{try{writeAtomically(target,json);}catch(java.io.IOException e){android.util.Log.w("RunZone","Could not write run draft",e);}});
    }
    private void deleteDraft(){final java.io.File target=draftFile(this);draftWriter.execute(()->{if(target.exists()&&!target.delete())android.util.Log.w("RunZone","Could not delete run draft");});}
    static void writeAtomically(java.io.File file,String text)throws java.io.IOException{
        java.io.File temp=new java.io.File(file.getPath()+".tmp");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(temp)){out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
        if(!temp.renameTo(file)){temp.delete();throw new java.io.IOException("rename failed: "+file);}
    }
    /** Moves a run left behind by a killed recording into the run list. Returns the saved file, or null. */
    static java.io.File recoverDraft(Context c){
        java.io.File draft=draftFile(c);if(!draft.exists())return null;
        java.io.File dir=new java.io.File(c.getFilesDir(),"runs");if(!dir.exists()&&!dir.mkdirs())return null;
        java.io.File file=new java.io.File(dir,draft.lastModified()+".json");
        if(!draft.renameTo(file))return null;
        CloudSync.enqueueNew(c,file);
        return file;
    }
    static void discardDraft(Context c){java.io.File draft=draftFile(c);if(draft.exists()&&!draft.delete())android.util.Log.w("RunZone","Could not delete run draft");}
    public Location lastFix;
    public final ArrayList<Location> route = new ArrayList<>();
    public volatile String sensor = "No heart-rate sensor", gps = "Waiting for GPS";
    public long started, pausedAt, pausedTotal, lastHr;
    private long startedAt;
    private int trackIndex,zoneLow,zoneHigh;
    private final RouteDistance distanceTracker=new RouteDistance();
    private LocationManager locations;
    private volatile BluetoothGatt gatt;
    private String sensorAddress;
    private long sensorAttemptAt;
    private final Runnable reconnectSensor=()->{if(sensorAddress!=null)openSensor();};
    private PowerManager.WakeLock runWakeLock;
    private long wakeRenewedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONArray points = new JSONArray();
    private JSONArray heartSamples = new JSONArray();
    private JSONArray timerOffsets = new JSONArray();
    private JSONArray cadenceSamples = new JSONArray();
    private SensorManager motionSensors;
    private final ArrayDeque<Long> recentSteps = new ArrayDeque<>();
    private long lastCadenceAt;
    private final SensorEventListener stepListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            long at = event.timestamp / 1_000_000;
            if (!running || paused || at < segmentStarted) return;
            if (!recentSteps.isEmpty() && at <= recentSteps.getLast()) return;
            while (!recentSteps.isEmpty() && at - recentSteps.getFirst() > 3000) recentSteps.removeFirst();
            recentSteps.addLast(at);
            if (recentSteps.size() < 3 || at - lastCadenceAt < 1000) return;
            int spm = (int) Math.round(60000.0 * (recentSteps.size() - 1) / (at - recentSteps.getFirst()));
            if (spm <= 0 || spm >= 510) return;
            try {
                cadenceSamples.put(new JSONObject().put("time", startedAt + at - started)
                        .put("track", trackIndex).put("spm", spm));
                lastCadenceAt = at;
            } catch (JSONException ignored) {}
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };
    public final DistanceGoal distanceGoal=new DistanceGoal();
    private long goalFeedbackUntil;
    private final ZoneCoach coach=new ZoneCoach();
    private final ZoneBeep zoneBeep=new ZoneBeep();
    private boolean bpmToneActive;
    // Beeps are paced at the heart rate, so this needs fine resolution, but only while beeps can actually play.
    private boolean beepLoop;
    private final Runnable beepCheck=new Runnable(){public void run(){
        updateZoneBeeps();
        if(beepsWanted())handler.postDelayed(this,20);else beepLoop=false;
    }};
    private boolean beepsWanted(){return getSharedPreferences("settings",0).getBoolean("zoneBeeps",false)&&ZoneCoach.enabled(running,paused,autoPaused);}
    private void ensureBeepLoop(){if(!beepLoop&&beepsWanted()){beepLoop=true;handler.post(beepCheck);}}
    public void updateZoneBeeps(){
        ensureBeepLoop();
        if(SystemClock.elapsedRealtime()<goalFeedbackUntil)return;
        android.content.SharedPreferences prefs=getSharedPreferences("settings",0);
        int fresh=freshBpm();
        boolean enabled=prefs.getBoolean("zoneBeeps",false)&&ZoneCoach.enabled(running,paused,autoPaused);
        int zone=zoneBeep.accept(enabled,fresh,prefs.getInt("low",120),prefs.getInt("high",150),SystemClock.elapsedRealtime());
        if(tone!=null){
            if(zone!=0){tone.startTone(zone>0?ToneGenerator.TONE_PROP_BEEP:ToneGenerator.TONE_PROP_BEEP2,80);bpmToneActive=true;}
            else if(!enabled||!RunRecord.validBpm(fresh)||HeartRate.zone(fresh,prefs.getInt("low",120),prefs.getInt("high",150))==0){
                if(bpmToneActive){tone.stopTone();bpmToneActive=false;}
            }
        }
    }
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
    private PendingIntent control(String action,int code){return PendingIntent.getService(this,code,new Intent(this,RunService.class).setAction(action),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
    private void promote(boolean bluetooth) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,"run").setContentTitle(running&&paused?"RunZone • Paused":running?"RunZone • Run in progress":"RunZone • Live heart rate")
            .setContentText(running?"Recording your route and coaching your heart rate":"Sensor connected • no run is being recorded")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC);
        if(running){
            builder.addAction(new Notification.Action.Builder(null,paused?"Resume":"Pause",control("TOGGLE_PAUSE",1)).build());
            builder.addAction(new Notification.Action.Builder(null,"Finish & save",control("FINISH",2)).build());
        }
        notification=builder.build();
        if(Build.VERSION.SDK_INT>=29) {
            int types=running?ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:0;
            if(bluetooth) types|=ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            startForeground(1,notification,types);
        } else startForeground(1,notification);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent==null) { stopSelf(); return START_NOT_STICKY; }
        if("TOGGLE_PAUSE".equals(intent.getAction())){if(running)togglePause();return START_NOT_STICKY;}
        if("FINISH".equals(intent.getAction())){
            if(running){
                if(save()){finishRun();android.widget.Toast.makeText(this,"Run saved. Nice work.",android.widget.Toast.LENGTH_SHORT).show();}
                else android.widget.Toast.makeText(this,"Couldn't save. Your run is still active.",android.widget.Toast.LENGTH_LONG).show();
            }
            return START_NOT_STICKY;
        }
        if("START".equals(intent.getAction()) && !running) {
            distanceGoal.reset(getSharedPreferences("settings",0).getInt("distanceTargetMeters",0));goalFeedbackUntil=0;
            running=true; paused=false; started=SystemClock.elapsedRealtime(); pausedTotal=0; meters=0;
            runWakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"RunZone:recording");
            runWakeLock.setReferenceCounted(false);
            runWakeLock.acquire(120000);wakeRenewedAt=started;
            startedAt=System.currentTimeMillis();trackIndex=0;draftSavedAt=started;beepLoop=false;handler.removeCallbacks(beepCheck);
            autoPaused=false;autoPause.reset();recentSpeed=Double.NaN;speedAnchor=null;segmentStarted=started;
            zoneLow=getSharedPreferences("settings",0).getInt("low",120);zoneHigh=getSharedPreferences("settings",0).getInt("high",150);
            coach.reset(); distanceTracker.reset(); route.clear(); points=new JSONArray();heartSamples=new JSONArray();timerOffsets=new JSONArray();
            promote(gatt!=null || intent.hasExtra("device"));
            cadenceSamples=new JSONArray();recentSteps.clear();lastCadenceAt=0;
            motionSensors=getSystemService(SensorManager.class);
            if(motionSensors!=null&&(Build.VERSION.SDK_INT<29
                    ||checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)==PackageManager.PERMISSION_GRANTED)){
                Sensor stepDetector=motionSensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
                if(stepDetector!=null){
                    try{motionSensors.registerListener(stepListener,stepDetector,SensorManager.SENSOR_DELAY_NORMAL,handler);}
                    catch(SecurityException ignored){}
                }
            }
            try { locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this); }
            catch(SecurityException | IllegalArgumentException e) { gps="GPS unavailable • check Settings"; }
        }
        if(intent.hasExtra("device")) { promote(true); connect(intent.getStringExtra("device")); }
        return START_NOT_STICKY;
    }
    public long elapsed() { return !running?0:(paused ? pausedAt : SystemClock.elapsedRealtime()) - started - pausedTotal; }
    public void finishRun() {
        goalFeedbackUntil=0;RunHaptics.cancel(this);
        if(motionSensors!=null)motionSensors.unregisterListener(stepListener);
        running=false; paused=false; autoPaused=false;autoPause.reset();coach.reset(); distanceTracker.reset();
        zoneBeep.reset();if(tone!=null)tone.stopTone();
        if(runWakeLock!=null&&runWakeLock.isHeld())runWakeLock.release();
        if(locations!=null) locations.removeUpdates(this);
        if(sensorAddress!=null) promote(true); else stopSelf();
    }
    public void discardRun() {
        finishRun();deleteDraft();
        route.clear();points=new JSONArray();heartSamples=new JSONArray();timerOffsets=new JSONArray();cadenceSamples=new JSONArray();meters=0;pausedTotal=0;
    }
    public void disconnectSensor() {
        sensorAddress=null;handler.removeCallbacks(reconnectSensor);closeSensor();
        bpm=-1; sensor="No sensor connected"; coach.reset();
        zoneBeep.reset();if(tone!=null)tone.stopTone();
        if(running) promote(false); else stopSelf();
    }
    public void togglePause() {
        setPaused(!paused,false);
    }
    private void setPaused(boolean value,boolean automatic) {
        if(!running||paused==value)return;
        long now=SystemClock.elapsedRealtime();
        timerOffsets.put(now-started);
        recentSteps.clear();lastCadenceAt=0;
        if (paused) {pausedTotal += now-pausedAt;trackIndex++;} else pausedAt=now;
        goalFeedbackUntil=0;RunHaptics.cancel(this);
        paused=value;autoPaused=value&&automatic;segmentStarted=now;distanceTracker.reset();coach.reset();autoPause.reset();route.add(null);
        zoneBeep.reset();if(tone!=null)tone.stopTone();bpmToneActive=false;
        ensureBeepLoop();
        promote(gatt!=null);
    }
    @Override public void onLocationChanged(Location location) {
        if (!running) return;
        long fixAge=SystemClock.elapsedRealtimeNanos()-location.getElapsedRealtimeNanos();
        if (!location.hasAccuracy() || location.getAccuracy()>30 || fixAge<0 || fixAge>5_000_000_000L) { gps="Weak GPS signal"; distanceTracker.reset();recentSpeed=Double.NaN;speedAnchor=null; return; }
        gps="GPS connected • ±"+(int)location.getAccuracy()+" m";
        boolean hasMeasuredSpeed=location.getAccuracy()<=15&&location.hasSpeed()&&RunRecord.validSpeed(location.getSpeed())
                &&(!location.hasSpeedAccuracy()||location.getSpeedAccuracyMetersPerSecond()<=.8f);
        if(hasMeasuredSpeed){
            recentSpeed=location.getSpeed();speedAt=location.getElapsedRealtimeNanos()/1000000;
        }else if(location.getAccuracy()<=15){
            // Some phones omit speed after stopping. Keep a separate anchor while paused.
            if(speedAnchor!=null){
                long delta=location.getElapsedRealtimeNanos()-speedAnchor.getElapsedRealtimeNanos();
                if(delta>=3_000_000_000L&&delta<=10_000_000_000L){
                    float distance=location.distanceTo(speedAnchor);
                    if(distance>location.getAccuracy()+speedAnchor.getAccuracy()){
                        recentSpeed=distance/(delta/1e9);speedAt=location.getElapsedRealtimeNanos()/1000000;
                    }
                }
                if(delta>=10_000_000_000L||delta<=0)speedAnchor=null;
            }
        }else{recentSpeed=Double.NaN;speedAnchor=null;}
        if(location.getAccuracy()<=15&&speedAnchor==null)speedAnchor=new Location(location);
        lastFix=new Location(location);
        if(paused) return;
        if(location.getElapsedRealtimeNanos()/1000000<segmentStarted)return;
        meters+=distanceTracker.accept(location.getLatitude(),location.getLongitude(),location.getElapsedRealtimeNanos()/1000000);
        if(distanceGoal.accept(meters)){
            goalFeedbackUntil=SystemClock.elapsedRealtime()+4000;
            zoneBeep.reset();bpmToneActive=false;
            if(tone!=null){tone.stopTone();tone.startTone(ToneGenerator.TONE_CDMA_CONFIRM,1800);}
            RunHaptics.play(this,RunHaptics.DISTANCE);
            promote(gatt!=null);
        }
        route.add(new Location(location));
        try {
            JSONObject point=new JSONObject().put("lat",location.getLatitude()).put("lon",location.getLongitude()).put("time",location.getTime()).put("bpm",freshBpm()).put("segment",route.size()>1&&route.get(route.size()-2)==null)
                .put("track",trackIndex).put("activeMs",elapsed()).put("distanceMeters",meters);
            if(location.hasAltitude())point.put("altitude",location.getAltitude());
            if(hasMeasuredSpeed)point.put("speedMps",(double) location.getSpeed());
            points.put(point);
        } catch(JSONException ignored) {}
    }
    @Override public void onProviderDisabled(String provider) { gps="GPS turned off"; distanceTracker.reset(); }
    @Override public void onProviderEnabled(String provider) { gps="Waiting for GPS"; }
    public int freshBpm() { return SystemClock.elapsedRealtime()-lastHr>10000 ? -1 : bpm; }
    private void receive(BluetoothGatt connection,byte[] bytes) { handler.post(()-> {
        if(current!=this||connection!=gatt) return;
        bpm=HeartRate.decode(bytes); lastHr=SystemClock.elapsedRealtime();
        if(running&&!paused&&RunRecord.validBpm(bpm))try{
            JSONObject pulse=new JSONObject().put("time",System.currentTimeMillis())
                    .put("activeMs",elapsed()).put("bpm",bpm).put("track",trackIndex);
            JSONArray intervals=new JSONArray();
            for(int ticks:HeartRate.rrIntervals(bytes))if(ticks>0)intervals.put(ticks);
            if(intervals.length()>0)pulse.put("rrTicks",intervals);
            heartSamples.put(pulse);
        }catch(JSONException ignored){}
        android.content.SharedPreferences prefs=getSharedPreferences("settings",0);
        updateZoneBeeps();
        int zone=coach.accept(ZoneCoach.enabled(running,paused,autoPaused),bpm,prefs.getInt("low",120),prefs.getInt("high",150),lastHr);
        if(zone!=0&&SystemClock.elapsedRealtime()>=goalFeedbackUntil) {
            if(tone!=null&&!prefs.getBoolean("zoneBeeps",false)) tone.startTone(zone>0 ? ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD : ToneGenerator.TONE_PROP_BEEP2,700);
            RunHaptics.play(this,zone>0?RunHaptics.ABOVE:RunHaptics.BELOW);
        }
    }); }
    private void connect(String address) {
        sensorAddress=address;handler.removeCallbacks(reconnectSensor);openSensor();
    }
    private void closeSensor() {
        BluetoothGatt old=gatt;gatt=null;
        if(old!=null)try{old.disconnect();old.close();}catch(SecurityException ignored){}
    }
    private void retrySensor() {
        closeSensor();bpm=-1;lastHr=0;coach.reset();
        handler.removeCallbacks(reconnectSensor);
        if(sensorAddress!=null){sensor="Sensor disconnected • reconnecting…";handler.postDelayed(reconnectSensor,5000);}
    }
    private void openSensor() {
        handler.removeCallbacks(reconnectSensor);
        closeSensor();
        sensor="Connecting…";
        sensorAttemptAt=SystemClock.elapsedRealtime();
        try {
            bpm=-1; lastHr=0;
            BluetoothAdapter adapter=getSystemService(BluetoothManager.class).getAdapter();
            if(adapter==null || !adapter.isEnabled()) { retrySensor();sensor="Bluetooth is off • waiting to reconnect";return; }
            BluetoothDevice device=adapter.getRemoteDevice(sensorAddress);
            gatt=device.connectGatt(this,false,new BluetoothGattCallback(){
                @Override public void onConnectionStateChange(BluetoothGatt connection,int status,int state) {
                    handler.post(()->{
                        if(connection!=gatt) return;
                        try {
                            if(state==BluetoothProfile.STATE_CONNECTED && status==BluetoothGatt.GATT_SUCCESS) { sensor="Discovering heart-rate service…"; if(!connection.discoverServices())retrySensor(); }
                            else if(state==BluetoothProfile.STATE_DISCONNECTED||status!=BluetoothGatt.GATT_SUCCESS)retrySensor();
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
                    if(status!=0 || descriptor==null || !connection.setCharacteristicNotification(characteristic,true)) { retrySensor(); return; }
                    if(Build.VERSION.SDK_INT>=33) {
                        if(connection.writeDescriptor(descriptor,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)!=BluetoothStatusCodes.SUCCESS) retrySensor();
                    } else {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        if(!connection.writeDescriptor(descriptor)) retrySensor();
                    }
                    } catch(SecurityException e) { sensor="Nearby devices permission needed"; }
                    });
                }
                @Override public void onDescriptorWrite(BluetoothGatt connection,BluetoothGattDescriptor descriptor,int status) { handler.post(()->{if(connection==gatt){if(status==0)sensor="Heart-rate sensor connected";else retrySensor();}}); }
                @Override public void onCharacteristicChanged(BluetoothGatt connection,BluetoothGattCharacteristic characteristic) { if(connection==gatt&&MEASUREMENT.equals(characteristic.getUuid())) receive(connection,characteristic.getValue()); }
                @Override public void onCharacteristicChanged(BluetoothGatt connection,BluetoothGattCharacteristic characteristic,byte[] value) { if(connection==gatt&&MEASUREMENT.equals(characteristic.getUuid())) receive(connection,value); }
            },BluetoothDevice.TRANSPORT_LE);
        } catch(SecurityException | IllegalArgumentException e) { sensor="Bluetooth permission unavailable"; }
    }
    private JSONObject snapshot() throws JSONException {
        long now=SystemClock.elapsedRealtime();
        long active=(paused?pausedAt:now)-started-pausedTotal;
        return new JSONObject().put("schemaVersion",2).put("date",startedAt).put("startTime",startedAt).put("endTime",startedAt+now-started)
            .put("meters",meters).put("duration",active).put("route",points).put("heartRate",heartSamples).put("timerOffsets",timerOffsets).put("cadence",cadenceSamples).put("zoneLow",zoneLow).put("zoneHigh",zoneHigh).put("distanceTargetMeters",distanceGoal.targetMeters()).put("distanceTargetReached",distanceGoal.reached());
    }
    public boolean save() {
        try {
            java.io.File dir=new java.io.File(getFilesDir(),"runs"); if(!dir.exists() && !dir.mkdirs()) return false;
            java.io.File file=new java.io.File(dir,System.currentTimeMillis()+".json");
            writeAtomically(file,snapshot().toString());
            deleteDraft();
            CloudSync.enqueueNew(this,file);
            return true;
        } catch(Exception e) { android.util.Log.e("RunZone","Could not save run",e); return false; }
    }
    @Override public void onDestroy() {
        RunHaptics.cancel(this);
        if(motionSensors!=null)motionSensors.unregisterListener(stepListener);
        sensorAddress=null;
        if(runWakeLock!=null&&runWakeLock.isHeld())runWakeLock.release();
        if(locations!=null) locations.removeUpdates(this);
        if(gatt!=null) { try { gatt.disconnect(); gatt.close(); } catch(SecurityException ignored) {} }
        if(running)saveDraft();draftWriter.shutdown();
        handler.removeCallbacksAndMessages(null); if(tone!=null) tone.release(); current=null; super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
