package com.runzone;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity implements LocationListener {
    private static final int BG=0xff101819,CARD=0xff1b2627,LINE=0xff30403e,TEXT=0xfff2f5ee,MUTED=0xff9eafaa,LIME=0xffc2f970;
    private LinearLayout root,content;
    private TextView heart,status,sensorLabel,gpsLabel,distance,duration,pace,target,start,finish;
    private ZoneTrack zoneTrack;
    private LiveMapView map;
    private String screen="Run",selected;
    private Location previewFix;
    private LocationManager locations;
    private boolean previewListening;
    private Runnable afterPermission;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private ScanCallback scan;
    private RunRecord detailRun;
    private java.io.File detailFile,pendingExport;
    private final java.util.concurrent.ExecutorService background=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Map<String,TextView> syncLabels=new HashMap<>();
    private final Map<String,TextView> settingsAccountLabels=new HashMap<>();
    private final Runnable refresh=new Runnable(){ public void run(){render();handler.postDelayed(this,1000);}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);locations=getSystemService(LocationManager.class);
        selected=getSharedPreferences("settings",0).getString("device",null);
        if(state!=null&&state.getString("export")!=null)pendingExport=new java.io.File(new java.io.File(getCacheDir(),"exports"),state.getString("export"));
        if(state!=null&&state.getString("detail")!=null)openRun(new java.io.File(new java.io.File(getFilesDir(),"runs"),state.getString("detail")));else show("Run");
        if(RunService.current==null&&selected!=null&&bluetoothAllowed())monitor(selected);
        handleAccountCallback(getIntent());
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("tnum");t.setIncludeFontPadding(false);return t;}
    private TextView bold(String value,int size,int color){TextView t=text(value,size,color);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void gap(LinearLayout parent,int height){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private void weighted(LinearLayout parent,View view){parent.addView(view,new LinearLayout.LayoutParams(0,-2,1));}
    private TextView pill(String title,int color,int foreground,View.OnClickListener listener){TextView t=bold(title,14,foreground);t.setGravity(Gravity.CENTER);t.setPadding(dp(16),dp(12),dp(16),dp(12));t.setBackground(shape(color,14));t.setOnClickListener(listener);t.setMinHeight(dp(48));return t;}
    private void smallTitle(LinearLayout parent,String title){TextView t=bold(title,11,MUTED);t.setLetterSpacing(.13f);parent.addView(t);}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(18),dp(18),dp(18),dp(18));c.setBackground(shape(CARD,22));return c;}
    private void show(String tab){
        stopPreview();if(map!=null){map.close();map=null;}screen=tab;
        root=column();root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);root.requestApplyInsets();
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        content=column();content.setPadding(dp(20),dp(14),dp(20),dp(18));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if("Run".equals(tab))home();else if("Settings".equals(tab))settings();else if("Detail".equals(tab))detailPage();else historyPage();
        LinearLayout nav=row();nav.setPadding(dp(14),dp(6),dp(14),dp(6));
        String active="Detail".equals(tab)?"History":tab;
        for(String name:new String[]{"Run","History","Settings"})weighted(nav,pill(name,name.equals(active)?0xff263c2c:BG,name.equals(active)?LIME:MUTED,v->show(name)));
        root.addView(nav);if("Run".equals(tab)){startPreview();render();}
    }
    private void heading(String title,String subtitle){
        LinearLayout header=row();TextView back=pill("‹",CARD,TEXT,v->show("Detail".equals(screen)?"History":"Run"));back.setContentDescription("Back");header.addView(back);
        TextView name=bold(title,28,TEXT);name.setPadding(dp(14),0,0,0);weighted(header,name);content.addView(header);gap(content,16);
        TextView intro=text(subtitle,14,MUTED);intro.setLineSpacing(dp(4),1);content.addView(intro);gap(content,24);
    }
    private void home(){
        LinearLayout header=row();header.addView(bold("r/",27,LIME));TextView name=bold("runzone",24,TEXT);name.setPadding(dp(8),0,0,0);weighted(header,name);
        header.addView(pill("Settings",CARD,MUTED,v->show("Settings")));content.addView(header);gap(content,24);
        smallTitle(content,"TRAIN WITH INTENTION");gap(content,8);content.addView(bold("Find your rhythm.",29,TEXT));gap(content,20);
        LinearLayout heartCard=card(),top=row();TextView live=bold("♥  LIVE HEART RATE",11,LIME);live.setLetterSpacing(.1f);weighted(top,live);
        TextView sensor=pill("Sensor  ›",0xff2a3835,TEXT,v->devices());sensor.setMinHeight(dp(36));sensor.setPadding(dp(12),dp(8),dp(12),dp(8));top.addView(sensor);heartCard.addView(top);gap(heartCard,8);
        LinearLayout numbers=row();numbers.setGravity(Gravity.BOTTOM);heart=bold("—",68,TEXT);numbers.addView(heart);TextView unit=text("bpm",17,MUTED);unit.setPadding(dp(8),0,0,dp(12));numbers.addView(unit);
        LinearLayout zone=column();zone.setGravity(Gravity.RIGHT);target=bold("120–150",17,TEXT);target.setGravity(Gravity.RIGHT);zone.addView(target);TextView caption=text("TARGET BPM",10,MUTED);caption.setGravity(Gravity.RIGHT);caption.setLetterSpacing(.08f);zone.addView(caption);weighted(numbers,zone);heartCard.addView(numbers);
        gap(heartCard,8);zoneTrack=new ZoneTrack();heartCard.addView(zoneTrack,new LinearLayout.LayoutParams(-1,dp(12)));gap(heartCard,12);
        status=text("Connect a sensor to see your live BPM",13,MUTED);heartCard.addView(status);gap(heartCard,6);sensorLabel=text("No run needed. Just connect and wear your sensor.",11,MUTED);heartCard.addView(sensorLabel);content.addView(heartCard);gap(content,16);
        FrameLayout mapCard=new FrameLayout(this);mapCard.setBackground(shape(0xffdee7da,22));mapCard.setClipToOutline(true);
        map=new LiveMapView(this);mapCard.addView(map,new FrameLayout.LayoutParams(-1,-1));
        gpsLabel=bold("LOCATING YOU",10,0xff274233);gpsLabel.setPadding(dp(12),dp(9),dp(12),dp(9));gpsLabel.setBackground(shape(0xedf8fbf4,10));
        FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);badge.setMargins(dp(12),dp(12),dp(60),0);mapCard.addView(gpsLabel,badge);
        LinearLayout zoom=column();zoom.addView(mapControl("+",v->map.zoomBy(1)));gap(zoom,2);zoom.addView(mapControl("−",v->map.zoomBy(-1)));
        FrameLayout.LayoutParams z=new FrameLayout.LayoutParams(dp(42),-2,Gravity.TOP|Gravity.RIGHT);z.setMargins(0,dp(12),dp(12),0);mapCard.addView(zoom,z);
        TextView locate=mapControl("◎",v->{if(permissions(true,false,this::startPreview)){startPreview();map.recenter();}});locate.setContentDescription("Center map on my location");
        FrameLayout.LayoutParams lc=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.BOTTOM|Gravity.RIGHT);lc.setMargins(0,0,dp(12),dp(34));mapCard.addView(locate,lc);
        TextView attribution=text("© OpenStreetMap contributors",10,0xff294333);attribution.setPadding(dp(8),dp(4),dp(8),dp(4));attribution.setBackgroundColor(0xeef8fbf4);attribution.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/copyright"))));
        mapCard.addView(attribution,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT));
        int mapHeight=Math.max(270,Math.min(420,Math.round(getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density)-700));
        content.addView(mapCard,new LinearLayout.LayoutParams(-1,dp(mapHeight)));gap(content,16);
        LinearLayout stats=row();stats.setPadding(dp(8),dp(8),dp(8),dp(8));distance=stat(stats,"DISTANCE","0.00","km");duration=stat(stats,"DURATION","00:00","active time");pace=stat(stats,"AVG PACE","—","min / km");content.addView(stats);
        LinearLayout actions=row();actions.setPadding(dp(20),dp(10),dp(20),dp(6));
        start=pill("Start run  →",LIME,BG,v->{RunService s=RunService.current;if(s!=null&&s.running){s.togglePause();render();}else begin();});weighted(actions,start);
        finish=pill("Finish",CARD,TEXT,v->finishSession());LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(dp(100),-2);fp.leftMargin=dp(10);actions.addView(finish,fp);root.addView(actions);
    }
    private TextView mapControl(String symbol,View.OnClickListener listener){TextView t=pill(symbol,0xf5f8fbf4,0xff294333,listener);t.setTextSize(22);t.setPadding(0,dp(5),0,dp(5));t.setMinHeight(dp(42));return t;}
    private TextView stat(LinearLayout parent,String title,String value,String unit){LinearLayout c=column();c.setGravity(Gravity.CENTER);TextView label=bold(title,9,MUTED);label.setLetterSpacing(.08f);c.addView(label);gap(c,8);TextView number=bold(value,25,TEXT);c.addView(number);gap(c,5);c.addView(text(unit,10,MUTED));weighted(parent,c);return number;}
    private void render(){
        if("Settings".equals(screen)){for(Map.Entry<String,TextView> label:settingsAccountLabels.entrySet())label.getValue().setText(CloudAccounts.summary(this,label.getKey()));return;}
        if("Detail".equals(screen)&&detailFile!=null){for(Map.Entry<String,TextView> label:syncLabels.entrySet())label.getValue().setText(CloudSync.status(this,detailFile.getName(),label.getKey()));return;}
        if(!"Run".equals(screen)||heart==null)return;
        RunService s=RunService.current;boolean running=s!=null&&s.running;
        int low=getSharedPreferences("settings",0).getInt("low",120),high=getSharedPreferences("settings",0).getInt("high",150),bpm=s==null?-1:s.freshBpm();
        int zone=bpm>0?HeartRate.zone(bpm,low,high):0;boolean alert=running&&!s.paused&&bpm>0&&zone!=0;
        heart.setText(bpm>0?Integer.toString(bpm):"—");heart.setTextColor(alert?0xffffbc8a:TEXT);target.setText(String.format(Locale.getDefault(),"%d–%d",low,high));zoneTrack.update(bpm,low,high);
        status.setText(running?(s.paused?(s.autoPaused?"Auto-paused • move faster to resume":"Run paused • take a breath"):bpm<=0?"Waiting for a live heart-rate reading":zone>0?"Above target • ease your pace":zone<0?"Below target • pick up your pace":"In your zone • keep this rhythm"):bpm>0?"Live preview • alerts start when you run":"Connect a sensor to see your live BPM");
        status.setTextColor(alert?0xffffbc8a:bpm>0?LIME:MUTED);sensorLabel.setText(s==null?"No run needed. Just connect and wear your sensor.":s.sensor);
        long seconds=running?s.elapsed()/1000:0;double meters=running?s.meters:0;distance.setText(String.format(Locale.getDefault(),"%.2f",meters/1000));
        duration.setText(seconds>=3600?String.format(Locale.getDefault(),"%d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60):String.format(Locale.getDefault(),"%02d:%02d",seconds/60,seconds%60));
        long paceSeconds=meters>=20?Math.round(seconds/(meters/1000)):0;pace.setText(paceSeconds>0?String.format(Locale.getDefault(),"%d:%02d",paceSeconds/60,paceSeconds%60):"—");
        start.setText(running?(s.paused?"Resume run  →":"Pause run  Ⅱ"):"Start run  →");finish.setVisibility(running?View.VISIBLE:View.GONE);
        Location fix=running&&s.lastFix!=null?s.lastFix:previewFix;boolean fresh=fix!=null&&SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos()<30_000_000_000L;
        gpsLabel.setText(running?(s.paused?"PAUSED • LOCATION LIVE":s.gps):fresh?"YOUR LOCATION • LIVE":hasLocation()?"WAITING FOR GPS":"TAP ◎ TO ENABLE LOCATION");
        map.update(fix,running?s.route:Collections.emptyList());
    }
    private void begin(){
        if(!permissions(true,selected!=null,this::begin))return;Intent intent=new Intent(this,RunService.class).setAction("START");
        if(RunService.current==null&&selected!=null)intent.putExtra("device",selected);startForegroundService(intent);handler.postDelayed(this::render,200);
    }
    private void finishSession(){RunService s=RunService.current;if(s==null||!s.running)return;
        new AlertDialog.Builder(this).setTitle("Save your run?").setMessage("Your route, distance and active time will be saved. Your heart-rate sensor stays connected.").setPositiveButton("Save run",(d,w)->{if(s.save()){s.finishRun();Toast.makeText(this,"Run saved. Nice work.",Toast.LENGTH_SHORT).show();render();}else Toast.makeText(this,"Couldn't save. Your run is still active; try again.",Toast.LENGTH_LONG).show();}).setNegativeButton("Keep running",null).show();
    }
    private void settings(){
        heading("Settings","Make RunZone work for the way you train.");smallTitle(content,"TRAINING");gap(content,12);
        LinearLayout training=card();int low=getSharedPreferences("settings",0).getInt("low",120),high=getSharedPreferences("settings",0).getInt("high",150);
        settingRow(training,"Heart-rate zones","Zone "+(getSharedPreferences("settings",0).getInt("profile",0)+1)+" · "+low+"–"+high+" bpm",v->zones());
        gap(training,18);android.content.SharedPreferences preferences=getSharedPreferences("settings",0);
        Switch auto=new Switch(this);auto.setText("Auto-pause");auto.setTextSize(17);auto.setTextColor(TEXT);auto.setChecked(preferences.getBoolean("autoPause",false));auto.setOnCheckedChangeListener((button,enabled)->preferences.edit().putBoolean("autoPause",enabled).apply());training.addView(auto);
        gap(training,12);settingRow(training,"Pause when slower than",paceLabel(preferences.getInt("pausePace",720))+" min/km · 10-second delay",v->autoPausePace());
        content.addView(training);gap(content,22);smallTitle(content,"AUTO SYNC");gap(content,12);
        settingsAccountLabels.clear();CloudAccounts.refreshReadiness(this);
        LinearLayout sync=card();for(String provider:CloudAccounts.PROVIDERS){TextView label=settingRow(sync,CloudAccounts.name(provider),CloudAccounts.summary(this,provider),v->accountOptions(provider));settingsAccountLabels.put(provider,label);gap(sync,12);}content.addView(sync);
        gap(content,22);smallTitle(content,"CONNECTIONS");gap(content,12);
        LinearLayout connections=card();settingRow(connections,"Heart-rate sensor",RunService.current==null?"Connect for live BPM, even before a run":RunService.current.sensor,v->devices());
        if(RunService.current!=null){gap(connections,16);settingRow(connections,"Disconnect sensor","Stop live readings; keep your run active",v->{if(RunService.current!=null)RunService.current.disconnectSensor();getSharedPreferences("settings",0).edit().remove("device").apply();selected=null;show("Settings");});}
        content.addView(connections);gap(content,22);smallTitle(content,"LOCATION");gap(content,12);
        LinearLayout location=card();settingRow(location,"Location settings","Manage your phone’s GPS",v->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        gap(location,18);settingRow(location,"App permissions","Precise location & nearby devices",v->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));content.addView(location);
        gap(content,24);TextView note=text("YOUR ZONE, YOUR PACE\n\nAlerts sound and vibrate after 5 seconds outside your active target, repeating every 20 seconds during a run. Keep media volume audible.\n\nAuto-pause uses reliable GPS speed. It pauses after 10 seconds below your pace threshold and resumes after 3 seconds clearly above it. Manual pauses always need manual resume.\n\nMaps use an internet connection. Recordings stay on your phone unless you export them or connect an account for sync.",13,MUTED);note.setLineSpacing(dp(4),1);content.addView(note);gap(content,20);content.addView(text("RUNZONE  /  VERSION "+BuildConfig.VERSION_NAME,10,MUTED));
    }
    private TextView settingRow(LinearLayout parent,String title,String subtitle,View.OnClickListener listener){LinearLayout r=row(),labels=column();labels.addView(bold(title,17,TEXT));gap(labels,6);TextView sub=text(subtitle,12,MUTED);sub.setLineSpacing(dp(3),1);labels.addView(sub);weighted(r,labels);TextView arrow=text("›",27,LIME);arrow.setPadding(dp(12),dp(8),0,dp(8));r.addView(arrow);r.setMinimumHeight(dp(54));r.setOnClickListener(listener);parent.addView(r);return sub;}
    private void historyPage(){
        heading("Your runs","Every run is a step forward.");java.io.File[] files=new java.io.File(getFilesDir(),"runs").listFiles();int count=0;
        if(files!=null){Arrays.sort(files,Collections.reverseOrder());for(java.io.File file:files)try{
            org.json.JSONObject run=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));LinearLayout c=card();smallTitle(c,java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new Date(run.getLong("date"))).toUpperCase(Locale.getDefault()));gap(c,12);
            c.addView(bold(String.format(Locale.getDefault(),"%.2f km",run.getDouble("meters")/1000),30,TEXT));gap(c,8);long seconds=run.getLong("duration")/1000;c.addView(text(String.format(Locale.getDefault(),"%d min %02d sec  ·  %s",seconds/60,seconds%60,java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new Date(run.getLong("date")))),13,MUTED));gap(c,12);c.addView(bold("View run & stats  ›",13,LIME));c.setOnClickListener(v->openRun(file));content.addView(c);gap(content,12);count++;
        }catch(Exception ignored){}}
        if(count==0){LinearLayout c=card();c.addView(bold("Your first run starts here.",22,TEXT));gap(c,12);c.addView(text("Finish and save a run to see your progress here.",14,MUTED));gap(c,20);c.addView(pill("Let’s go  →",LIME,BG,v->show("Run")));content.addView(c);}
    }
    private void openRun(java.io.File file){try{detailRun=RunRecord.read(file);detailFile=file;show("Detail");}catch(Exception e){show("History");Toast.makeText(this,"This recording couldn't be opened.",Toast.LENGTH_LONG).show();}}
    private static String durationLabel(long ms){long s=ms/1000;return s>=3600?String.format(Locale.getDefault(),"%d:%02d:%02d",s/3600,(s/60)%60,s%60):String.format(Locale.getDefault(),"%02d:%02d",s/60,s%60);}
    private static String paceLabel(long seconds){return String.format(Locale.getDefault(),"%d:%02d",seconds/60,seconds%60);}
    private void detailPage(){
        RunRecord run=detailRun;heading("Run details",java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.LONG,java.text.DateFormat.SHORT).format(new Date(run.startTime)));
        LinearLayout summary=card(),first=row(),second=row();stat(first,"DISTANCE",String.format(Locale.getDefault(),"%.2f",run.meters/1000),"km");stat(first,"ACTIVE TIME",durationLabel(run.durationMs),"excludes pauses");stat(first,"AVG PACE",run.meters>0?paceLabel(Math.round(run.durationMs/run.meters)):"—","min / km");summary.addView(first);gap(summary,22);
        stat(second,"AVG HR",run.averageBpm()>0?Integer.toString(run.averageBpm()):"—","bpm");stat(second,"MAX HR",run.maxBpm()>0?Integer.toString(run.maxBpm()):"—","bpm");stat(second,"ELAPSED",durationLabel(run.elapsedMs),"including pauses");summary.addView(second);content.addView(summary);gap(content,22);
        smallTitle(content,"YOUR ROUTE");gap(content,12);ArrayList<Location> route=new ArrayList<>();int track=-1;for(RunRecord.Point point:run.points){if(track!=-1&&track!=point.track)route.add(null);Location location=new Location("recording");location.setLatitude(point.lat);location.setLongitude(point.lon);route.add(location);track=point.track;}
        FrameLayout frame=new FrameLayout(this);frame.setBackground(shape(0xffdee7da,22));frame.setClipToOutline(true);map=new LiveMapView(this);frame.addView(map,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout controls=column();controls.addView(mapControl("+",v->map.zoomBy(1)));gap(controls,2);controls.addView(mapControl("−",v->map.zoomBy(-1)));gap(controls,2);TextView fit=mapControl("Fit",v->map.fitRoute(route));fit.setTextSize(13);controls.addView(fit);FrameLayout.LayoutParams controlPosition=new FrameLayout.LayoutParams(dp(44),-2,Gravity.TOP|Gravity.RIGHT);controlPosition.setMargins(0,dp(12),dp(12),0);frame.addView(controls,controlPosition);
        TextView attribution=text("© OpenStreetMap contributors",10,0xff294333);attribution.setBackgroundColor(0xeef8fbf4);attribution.setPadding(dp(8),dp(4),dp(8),dp(4));attribution.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/copyright"))));frame.addView(attribution,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT));content.addView(frame,new LinearLayout.LayoutParams(-1,dp(300)));map.fitRoute(route);gap(content,22);
        smallTitle(content,"HEART RATE");gap(content,12);LinearLayout heartCard=card();
        if(run.pulses.isEmpty())heartCard.addView(text("No heart-rate data was recorded for this run.",14,MUTED));else{heartCard.addView(bold("Your effort, beat by beat",18,TEXT));gap(heartCard,10);heartCard.addView(new HeartChart(this,run),new LinearLayout.LayoutParams(-1,dp(150)));if(run.low>0){gap(heartCard,10);heartCard.addView(text("Starting target: "+run.low+"–"+run.high+" bpm",12,MUTED));}}
        content.addView(heartCard);gap(content,22);List<RunRecord.Split> splits=run.splits();
        if(!splits.isEmpty()){smallTitle(content,"KILOMETRE SPLITS");gap(content,12);LinearLayout splitCard=card();int index=1;for(RunRecord.Split split:splits){LinearLayout r=row();weighted(r,text(split.meters>=999.5?"Kilometre "+index:String.format(Locale.getDefault(),"Last %.2f km",split.meters/1000),14,TEXT));r.addView(bold(paceLabel(Math.round(split.durationMs/split.meters))+" /km",14,LIME));splitCard.addView(r);gap(splitCard,12);index++;}content.addView(splitCard);gap(content,22);}
        if(run.legacy){content.addView(text("Older recording: start time is estimated, and kilometre splits are unavailable. Heart-rate stats use the samples that were saved.",12,MUTED));gap(content,22);}
        smallTitle(content,"SYNC & EXPORT");gap(content,12);LinearLayout sync=card();syncLabels.clear();
        for(String provider:CloudAccounts.PROVIDERS){LinearLayout r=row(),labels=column();labels.addView(bold(CloudAccounts.name(provider),16,TEXT));gap(labels,5);TextView state=text(CloudSync.status(this,detailFile.getName(),provider),12,MUTED);syncLabels.put(provider,state);labels.addView(state);weighted(r,labels);r.addView(pill("›",0xff2a3835,LIME,v->accountOptions(provider)));sync.addView(r);gap(sync,14);}
        sync.addView(pill("Export TCX file  ↗",LIME,BG,v->prepareExport()));gap(sync,12);sync.addView(text("Compatible with Smashrun, Runalyze and Strava. Save a file or share it with another app.",12,MUTED));content.addView(sync);
    }
    private void autoPausePace(){
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Minutes:seconds per km");input.setText(paceLabel(getSharedPreferences("settings",0).getInt("pausePace",720)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Auto-pause pace").setMessage("Pause when slower than this pace for 10 seconds. Example: 12:00 min/km. Auto-pause is optional and does not react to lost GPS.").setView(input).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{String[] parts=input.getText().toString().trim().split(":");if(parts.length!=2)throw new IllegalArgumentException();int min=Integer.parseInt(parts[0]),sec=Integer.parseInt(parts[1]),total=min*60+sec;if(sec<0||sec>59||total<180||total>1800)throw new IllegalArgumentException();getSharedPreferences("settings",0).edit().putInt("pausePace",total).apply();dialog.dismiss();show("Settings");}catch(IllegalArgumentException e){input.setError("Use 3:00 to 30:00 min/km");}}));dialog.show();
    }
    private void prepareExport(){
        RunRecord run=detailRun;String id=detailFile.getName().replace(".json","");Toast.makeText(this,"Preparing TCX export…",Toast.LENGTH_SHORT).show();
        background.execute(()->{try{java.io.File dir=new java.io.File(getCacheDir(),"exports");if(!dir.exists()&&!dir.mkdirs())throw new java.io.IOException();java.io.File file=new java.io.File(dir,"runzone-"+id+".tcx");try(java.io.Writer writer=new java.io.OutputStreamWriter(new java.io.FileOutputStream(file),java.nio.charset.StandardCharsets.UTF_8)){TcxExporter.write(run,writer);}runOnUiThread(()->{if(isDestroyed())return;pendingExport=file;new AlertDialog.Builder(this).setTitle("Export your run").setMessage("TCX includes recorded GPS, timing, distance and heart rate. Import this file on Smashrun, Runalyze or Strava. Runs without GPS may not import on Smashrun.").setPositiveButton("Save file",(d,w)->{Intent create=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/xml").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,file.getName());startActivityForResult(create,80);}).setNeutralButton("Share file",(d,w)->{Uri uri=new Uri.Builder().scheme("content").authority(getPackageName()+".exports").appendPath(file.getName()).build();Intent share=new Intent(Intent.ACTION_SEND).setType("application/vnd.garmin.tcx+xml").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(ClipData.newRawUri("Run recording",uri));startActivity(Intent.createChooser(share,"Share TCX recording"));}).setNegativeButton("Cancel",null).show();});}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Couldn't prepare the export. Please try again.",Toast.LENGTH_LONG).show());}});
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==80&&result==RESULT_OK&&data!=null&&data.getData()!=null&&pendingExport!=null){Uri destination=data.getData();java.io.File file=pendingExport;background.execute(()->{try(java.io.InputStream input=new java.io.FileInputStream(file);java.io.OutputStream output=getContentResolver().openOutputStream(destination)){if(output==null)throw new java.io.IOException();byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);runOnUiThread(()->Toast.makeText(this,"TCX file saved",Toast.LENGTH_SHORT).show());}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Couldn't save the export. Try another location.",Toast.LENGTH_LONG).show());}});}}
    private void accountOptions(String provider){
        if(CloudAccounts.connected(this,provider)){ArrayList<String> options=new ArrayList<>();if(CloudAccounts.needsReconnect(this,provider))options.add("Reconnect account");if("Detail".equals(screen))options.add("Sync this run");options.add(CloudAccounts.enabled(this,provider)?"Turn automatic sync off":"Turn automatic sync on");options.add("Disconnect account");
            new AlertDialog.Builder(this).setTitle(CloudAccounts.name(provider)).setItems(options.toArray(new String[0]),(d,index)->{String option=options.get(index);if(option.equals("Reconnect account")){connectAccount(provider);}else if(option.equals("Sync this run")){CloudSync.enqueue(this,detailFile,provider);Toast.makeText(this,"Run queued for sync",Toast.LENGTH_SHORT).show();render();}else if(option.equals("Disconnect account")){new AlertDialog.Builder(this).setTitle("Disconnect "+CloudAccounts.name(provider)+"?").setMessage("Stops future uploads from this phone. Runs already uploaded remain in your account.").setPositiveButton("Disconnect",(a,w)->{CloudAccounts.disconnect(this,provider);show(screen);}).setNegativeButton("Cancel",null).show();}else{getSharedPreferences("sync",0).edit().putBoolean("enabled_"+provider,!CloudAccounts.enabled(this,provider)).apply();CloudSync.schedule(this);show(screen);}}).setNegativeButton("Cancel",null).show();
        }else connectAccount(provider);
    }
    private void connectAccount(String provider){
            if(!CloudAccounts.configured(provider)&&!"smashrun".equals(provider)){new AlertDialog.Builder(this).setTitle("Account connections need setup").setMessage("RunZone’s shared account service has not been configured in this build yet. Once it is live, every user can sign in with their own account here. TCX file export is available now.").setPositiveButton("OK",null).show();return;}
            if(CloudAccounts.setupPending(this,provider)){CloudAccounts.refreshReadiness(this);new AlertDialog.Builder(this).setTitle(CloudAccounts.name(provider)+" connection not available yet").setMessage("This connection is still being set up for RunZone. Try again later. TCX file export is available now.").setPositiveButton("OK",null).show();return;}
            new AlertDialog.Builder(this).setTitle("Connect "+CloudAccounts.name(provider)+"?").setMessage("You'll sign in with "+CloudAccounts.name(provider)+". New saved runs will sync automatically; older runs sync only when you choose. You can turn this off or disconnect in Settings."+("smashrun".equals(provider)?" When Smashrun access expires, sign in again to continue syncing.":"")).setPositiveButton("Continue",(d,w)->background.execute(()->{try{Uri authorize=CloudAccounts.begin(this,provider);runOnUiThread(()->startActivity(new Intent(Intent.ACTION_VIEW,authorize)));}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Could not start sign-in. The service may not be configured yet.",Toast.LENGTH_LONG).show());}})).setNegativeButton("Cancel",null).show();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleAccountCallback(intent);}
    private void handleAccountCallback(Intent intent){Uri uri=intent.getData();if(uri==null||!"runzone".equals(uri.getScheme())||!"accounts".equals(uri.getHost()))return;setIntent(new Intent(this,MainActivity.class));background.execute(()->{try{String provider=CloudAccounts.complete(this,uri);runOnUiThread(()->{show("Settings");Toast.makeText(this,CloudAccounts.name(provider)+" connected. New runs will sync automatically.",Toast.LENGTH_LONG).show();});}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Sign-in was cancelled, expired or did not grant upload permission. Please try again.",Toast.LENGTH_LONG).show());}});}
    private boolean hasLocation(){return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private boolean bluetoothAllowed(){return Build.VERSION.SDK_INT<31?hasLocation():checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private boolean permissions(boolean location,boolean bluetooth,Runnable continuation){
        ArrayList<String> required=new ArrayList<>();if(location||(bluetooth&&Build.VERSION.SDK_INT<31)){required.add(Manifest.permission.ACCESS_FINE_LOCATION);required.add(Manifest.permission.ACCESS_COARSE_LOCATION);}
        if(bluetooth&&Build.VERSION.SDK_INT>=31){required.add(Manifest.permission.BLUETOOTH_SCAN);required.add(Manifest.permission.BLUETOOTH_CONNECT);}
        ArrayList<String> missing=new ArrayList<>();for(String p:required)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);
        if(!missing.isEmpty()){afterPermission=continuation;requestPermissions(missing.toArray(new String[0]),1);return false;}return true;
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);Runnable next=afterPermission;afterPermission=null;if(code==1&&results.length>0){boolean granted=true;for(int r:results)granted&=r==PackageManager.PERMISSION_GRANTED;if(granted&&next!=null)next.run();else Toast.makeText(this,"Permission needed. You can change it in Settings → App permissions.",Toast.LENGTH_LONG).show();}}
    private void startPreview(){
        if(previewListening||!hasLocation()||!"Run".equals(screen))return;
        try{
            if(locations.isProviderEnabled(LocationManager.GPS_PROVIDER))locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000,2,this);
            if(locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER))locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,5000,5,this);
            for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}){Location last=locations.getLastKnownLocation(provider);if(last!=null&&(previewFix==null||last.getElapsedRealtimeNanos()>previewFix.getElapsedRealtimeNanos())&&SystemClock.elapsedRealtimeNanos()-last.getElapsedRealtimeNanos()<120_000_000_000L)previewFix=last;}previewListening=true;
        }catch(SecurityException|IllegalArgumentException ignored){}render();
    }
    private void stopPreview(){if(locations!=null)locations.removeUpdates(this);previewListening=false;}
    @Override public void onLocationChanged(Location location){if(previewFix==null||LocationManager.GPS_PROVIDER.equals(location.getProvider())||SystemClock.elapsedRealtimeNanos()-previewFix.getElapsedRealtimeNanos()>10000000000L){previewFix=new Location(location);render();}}
    @Override public void onProviderEnabled(String provider){stopPreview();startPreview();}
    @Override public void onProviderDisabled(String provider){render();}
    @Override protected void onResume(){super.onResume();handler.post(refresh);startPreview();CloudAccounts.refreshReadiness(this);}
    @Override protected void onPause(){super.onPause();handler.removeCallbacks(refresh);stopScan();stopPreview();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if("Detail".equals(screen)&&detailFile!=null)state.putString("detail",detailFile.getName());if(pendingExport!=null)state.putString("export",pendingExport.getName());}
    @Override protected void onDestroy(){if(map!=null)map.close();background.shutdown();handler.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override public void onBackPressed(){if("Detail".equals(screen))show("History");else if(!"Run".equals(screen))show("Run");else super.onBackPressed();}
    private void monitor(String address){startForegroundService(new Intent(this,RunService.class).putExtra("device",address));}
    private void stopScan(){if(scanner!=null&&scan!=null){try{scanner.stopScan(scan);}catch(SecurityException ignored){}scan=null;}}
    private void devices(){
        if(!permissions(false,true,this::devices))return;
        try{
            BluetoothAdapter adapter=getSystemService(BluetoothManager.class).getAdapter();if(adapter==null){Toast.makeText(this,"Bluetooth LE unavailable",Toast.LENGTH_LONG).show();return;}if(!adapter.isEnabled()){startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));return;}
            stopScan();scanner=adapter.getBluetoothLeScanner();if(scanner==null)return;ArrayList<BluetoothDevice> found=new ArrayList<>();ArrayAdapter<String> names=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1);
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Finding your heart-rate sensor…").setAdapter(names,(d,index)->{selected=found.get(index).getAddress();getSharedPreferences("settings",0).edit().putString("device",selected).apply();monitor(selected);handler.postDelayed(()->{if("Settings".equals(screen))show("Settings");else render();},500);}).setNegativeButton("Cancel",null).create();dialog.setOnDismissListener(d->stopScan());dialog.show();
            scan=new ScanCallback(){@Override public void onScanResult(int type,ScanResult result){runOnUiThread(()->{try{if(!dialog.isShowing())return;if(!found.contains(result.getDevice())){found.add(result.getDevice());String name=result.getDevice().getName();names.add((name==null?"Heart-rate sensor":name)+"\n"+result.getDevice().getAddress());}}catch(SecurityException e){dialog.dismiss();}});}@Override public void onScanFailed(int code){runOnUiThread(()->dialog.setTitle("Scan failed • try again"));}};
            scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(RunService.HR)).build()),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scan);
            handler.postDelayed(()->{if(dialog.isShowing()){stopScan();dialog.setTitle(found.isEmpty()?"No sensors found • wake your sensor and retry":"Choose your sensor");}},15000);
        }catch(SecurityException e){stopScan();Toast.makeText(this,"Nearby devices permission needed. Check app settings.",Toast.LENGTH_LONG).show();}
    }
    private void zones(){
        LinearLayout box=column();box.setPadding(dp(24),dp(12),dp(24),dp(12));android.content.SharedPreferences prefs=getSharedPreferences("settings",0);
        Spinner profile=new Spinner(this);profile.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Zone 1","Zone 2","Zone 3","Zone 4","Zone 5"}));box.addView(profile);EditText low=new EditText(this),high=new EditText(this);low.setInputType(2);high.setInputType(2);low.setHint("Minimum bpm");high.setHint("Maximum bpm");box.addView(low);box.addView(high);
        profile.setSelection(prefs.getInt("profile",0));profile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long id){low.setText(Integer.toString(prefs.getInt("low"+position,prefs.getInt("low",120))));high.setText(Integer.toString(prefs.getInt("high"+position,prefs.getInt("high",150))));}public void onNothingSelected(AdapterView<?> p){}});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Your heart-rate zones").setMessage("Choose a profile and set its bpm range. Save to use this target for your run.").setView(box).setPositiveButton("Save & activate",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{int l=Integer.parseInt(low.getText().toString()),h=Integer.parseInt(high.getText().toString());if(l<30||h>240||l>=h)throw new IllegalArgumentException();int position=profile.getSelectedItemPosition();prefs.edit().putInt("profile",position).putInt("low"+position,l).putInt("high"+position,h).putInt("low",l).putInt("high",h).apply();dialog.dismiss();show("Settings");}catch(IllegalArgumentException e){high.setError("Use 30–240 bpm, minimum below maximum");}}));dialog.show();
    }
    private class ZoneTrack extends View {
        private int bpm=-1,low=120,high=150;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        ZoneTrack(){super(MainActivity.this);setContentDescription("Heart-rate target range");}
        void update(int b,int l,int h){bpm=b;low=l;high=h;invalidate();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();paint.setColor(LINE);c.drawRoundRect(0,h*.25f,w,h*.75f,dp(3),dp(3),paint);paint.setColor(LIME);c.drawRoundRect(w/3,h*.25f,w*2/3,h*.75f,dp(3),dp(3),paint);if(bpm>0){float p=Math.max(0,Math.min(1,(bpm-low+(high-low))/(float)(3*(high-low))));paint.setColor(TEXT);c.drawCircle(Math.max(h/2,Math.min(w-h/2,w*p)),h/2,h/2,paint);}}
    }
}
