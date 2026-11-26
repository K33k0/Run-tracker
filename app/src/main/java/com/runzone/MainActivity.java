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
    private TextView heart,status,sensorLabel,gpsLabel,distance,duration,pace,target,start,finish,backgroundLabel,distanceTarget,currentPace;
    private ZoneTrack zoneTrack;
    private LiveMapView map;
    private String screen="Run",selected;
    private Location previewFix;
    private LocationManager locations;
    private boolean previewListening;
    private Runnable afterPermission;
    private boolean checkedOpeningPermissions, permissionRequestPending;
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
        super.onCreate(state);ensureZones();locations=getSystemService(LocationManager.class);
        if(state!=null){
            checkedOpeningPermissions=state.getBoolean("checkedOpeningPermissions");
            permissionRequestPending=state.getBoolean("permissionRequestPending");
        }
        selected=getSharedPreferences("settings",0).getString("device",null);
        if(state!=null&&state.getString("export")!=null)pendingExport=new java.io.File(new java.io.File(getCacheDir(),"exports"),state.getString("export"));
        if(state!=null&&state.getString("detail")!=null)openRun(new java.io.File(new java.io.File(getFilesDir(),"runs"),state.getString("detail")));else show("Run");
        if(RunService.current==null&&selected!=null&&bluetoothAllowed())monitor(selected);
        if((RunService.current==null||!RunService.current.running)&&RunService.draftFile(this).exists())offerRecovery();
        handleAccountCallback(getIntent());
        AppUpdates.schedule(this);
        AppUpdates.check(this,getIntent().getBooleanExtra("checkUpdate",false));
        getIntent().removeExtra("checkUpdate");
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
        smallTitle(content,"TRAIN WITH INTENTION");gap(content,8);content.addView(bold("Find your rhythm.",29,TEXT));gap(content,8);TextView readiness=bold(readinessHint(),13,LIME);readiness.setPadding(0,dp(4),0,dp(4));readiness.setOnClickListener(v->show("History"));readiness.setContentDescription("Today's training readiness. Opens training details.");content.addView(readiness);refreshTraining(t->readiness.setText(readinessHint()));gap(content,8);distanceTarget=pill("Set distance target  ›",CARD,LIME,v->distanceTarget());content.addView(distanceTarget);gap(content,16);
        LinearLayout heartCard=card(),top=row();TextView live=bold("♥  LIVE HEART RATE",11,LIME);live.setLetterSpacing(.1f);weighted(top,live);
        TextView sensor=pill("Sensor  ›",0xff2a3835,TEXT,v->devices());sensor.setMinHeight(dp(36));sensor.setPadding(dp(12),dp(8),dp(12),dp(8));top.addView(sensor);heartCard.addView(top);gap(heartCard,8);
        LinearLayout numbers=row();numbers.setGravity(Gravity.BOTTOM);heart=bold("—",68,TEXT);numbers.addView(heart);TextView unit=text("bpm",17,MUTED);unit.setPadding(dp(8),0,0,dp(12));numbers.addView(unit);
        LinearLayout zone=column();zone.setGravity(Gravity.RIGHT);target=bold("120–150",17,TEXT);target.setGravity(Gravity.RIGHT);zone.addView(target);TextView caption=text("TARGET ZONE · BPM  ›",10,LIME);zone.setOnClickListener(v->zones());zone.setContentDescription("Change target heart-rate zone");caption.setGravity(Gravity.RIGHT);caption.setLetterSpacing(.08f);zone.addView(caption);weighted(numbers,zone);heartCard.addView(numbers);
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
        LinearLayout stats=row();stats.setPadding(dp(8),dp(8),dp(8),dp(8));stats.setBackgroundColor(CARD);distance=stat(stats,"DISTANCE","0.00","km");duration=stat(stats,"DURATION","00:00","active time");currentPace=stat(stats,"PACE","—","last 30 s");pace=stat(stats,"AVG PACE","—","min / km");root.addView(stats);
        LinearLayout actions=row();actions.setPadding(dp(20),dp(10),dp(20),dp(6));
        start=pill("Start run  →",LIME,BG,v->{RunService s=RunService.current;if(s!=null&&s.running){s.togglePause();render();}else begin();});weighted(actions,start);
        finish=pill("Finish",CARD,TEXT,v->finishSession());LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(dp(100),-2);fp.leftMargin=dp(10);actions.addView(finish,fp);root.addView(actions);
    }
    private TextView mapControl(String symbol,View.OnClickListener listener){TextView t=pill(symbol,0xf5f8fbf4,0xff294333,listener);t.setTextSize(22);t.setPadding(0,dp(5),0,dp(5));t.setMinHeight(dp(42));return t;}
    private TextView stat(LinearLayout parent,String title,String value,String unit){LinearLayout c=column();c.setGravity(Gravity.CENTER);TextView label=bold(title,9,MUTED);label.setLetterSpacing(.08f);c.addView(label);gap(c,8);TextView number=bold(value,22,TEXT);c.addView(number);gap(c,5);c.addView(text(unit,10,MUTED));weighted(parent,c);return number;}
    private void render(){
        if("Settings".equals(screen)){if(backgroundLabel!=null)backgroundLabel.setText(backgroundSummary());for(Map.Entry<String,TextView> label:settingsAccountLabels.entrySet())label.getValue().setText(CloudAccounts.summary(this,label.getKey()));return;}
        if("Detail".equals(screen)&&detailFile!=null){for(Map.Entry<String,TextView> label:syncLabels.entrySet())label.getValue().setText(CloudSync.status(this,detailFile.getName(),label.getKey()));return;}
        if(!"Run".equals(screen)||heart==null)return;
        RunService s=RunService.current;boolean running=s!=null&&s.running;distanceTarget.setText(distanceTargetLabel());
        int low=getSharedPreferences("settings",0).getInt("low",120),high=getSharedPreferences("settings",0).getInt("high",150),bpm=s==null?-1:s.freshBpm();
        int zone=bpm>0?HeartRate.zone(bpm,low,high):0;boolean alert=running&&ZoneCoach.enabled(running,s.paused,s.autoPaused)&&bpm>0&&zone!=0;
        heart.setText(bpm>0?Integer.toString(bpm):"—");heart.setTextColor(alert?0xffffbc8a:TEXT);target.setText(String.format(Locale.getDefault(),"Zone %d\n%d–%d",getSharedPreferences("settings",0).getInt("profile",2)+1,low,high));zoneTrack.update(bpm,low,high);
        status.setText(running?(s.paused?(s.autoPaused?"Auto-paused • move faster to resume":"Run paused • take a breath"):bpm<=0?"Waiting for a live heart-rate reading":zone>0?"Above target • ease your pace":zone<0?"Below target • pick up your pace":"In your zone • keep this rhythm"):bpm>0?"Live preview • alerts start when you run":"Connect a sensor to see your live BPM");
        status.setTextColor(alert?0xffffbc8a:bpm>0?LIME:MUTED);sensorLabel.setText(s==null?"No run needed. Just connect and wear your sensor.":s.sensor);
        long seconds=running?s.elapsed()/1000:0;double meters=running?s.meters:0;distance.setText(String.format(Locale.getDefault(),"%.2f",meters/1000));
        duration.setText(seconds>=3600?String.format(Locale.getDefault(),"%d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60):String.format(Locale.getDefault(),"%02d:%02d",seconds/60,seconds%60));
        long paceSeconds=meters>=20?Math.round(seconds/(meters/1000)):0;pace.setText(paceSeconds>0?String.format(Locale.getDefault(),"%d:%02d",paceSeconds/60,paceSeconds%60):"—");
        long now=running?s.currentPace():-1;currentPace.setText(now>0?String.format(Locale.getDefault(),"%d:%02d",now/60,now%60):"—");
        start.setText(running?(s.paused?"Resume run  →":"Pause run  Ⅱ"):"Start run  →");finish.setVisibility(running?View.VISIBLE:View.GONE);
        Location fix=running&&s.lastFix!=null?s.lastFix:previewFix;boolean fresh=fix!=null&&SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos()<30_000_000_000L;
        gpsLabel.setText(running?(s.paused?"PAUSED • LOCATION LIVE":s.gps):fresh?"YOUR LOCATION • LIVE":hasLocation()?"WAITING FOR GPS":"TAP ◎ TO ENABLE LOCATION");
        map.update(fix,running?s.route:Collections.emptyList());
    }
    private void begin(){
        if(!permissions(true,selected!=null,this::begin))return;
        if(Build.VERSION.SDK_INT>=29
                &&getPackageManager().hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_DETECTOR)
                &&checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)!=PackageManager.PERMISSION_GRANTED
                &&!getPreferences(MODE_PRIVATE).getBoolean("cadencePermissionAsked",false)){
            getPreferences(MODE_PRIVATE).edit().putBoolean("cadencePermissionAsked",true).apply();
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION},3);
            return;
        }
        if(!backgroundReady()){
            new AlertDialog.Builder(this).setTitle("Check background recording").setMessage(backgroundSummary()+"\n\nFor screen-off runs, allow background usage and set RunZone battery usage to Unrestricted / Don’t optimise. Some phones also have sleeping-app or auto-start settings.")
                .setPositiveButton("Review settings",(d,w)->backgroundSettings()).setNeutralButton("Start anyway",(d,w)->startRun()).setNegativeButton("Cancel",null).show();return;
        }
        startRun();
    }
    private void startRun(){
        if(!permissions(true,selected!=null,this::begin))return;Intent intent=new Intent(this,RunService.class).setAction("START");
        if(RunService.current==null&&selected!=null)intent.putExtra("device",selected);startForegroundService(intent);handler.postDelayed(this::render,200);
    }
    private void offerRecovery(){
        new AlertDialog.Builder(this).setTitle("Recover unfinished run?").setMessage("RunZone stopped while a run was recording. You can keep it up to the last automatic save (at most 30 seconds before it stopped).").setCancelable(false)
            .setPositiveButton("Keep run",(d,w)->{if(RunService.recoverDraft(this)!=null){Toast.makeText(this,"Run recovered",Toast.LENGTH_SHORT).show();show("History");}else Toast.makeText(this,"Couldn't recover the run",Toast.LENGTH_LONG).show();})
            .setNegativeButton("Discard",(d,w)->new AlertDialog.Builder(this).setTitle("Discard this run?").setMessage("This run cannot be recovered afterwards.").setPositiveButton("Discard run",(c,x)->RunService.discardDraft(this)).setNegativeButton("Keep run",(c,x)->offerRecovery()).show()).show();
    }
    private void finishSession(){RunService s=RunService.current;if(s==null||!s.running)return;
        new AlertDialog.Builder(this).setTitle("Save your run?").setMessage("Your route, distance and active time will be saved. Your heart-rate sensor stays connected.").setPositiveButton("Save run",(d,w)->{if(s.save()){s.finishRun();Toast.makeText(this,"Run saved. Nice work.",Toast.LENGTH_SHORT).show();render();}else Toast.makeText(this,"Couldn't save. Your run is still active; try again.",Toast.LENGTH_LONG).show();}).setNeutralButton("Discard run",(d,w)->new AlertDialog.Builder(this).setTitle("Discard this run?").setMessage("Stop recording without saving? This run cannot be recovered.").setPositiveButton("Discard run",(confirm,which)->{if(s.running){s.discardRun();render();Toast.makeText(this,"Run discarded",Toast.LENGTH_SHORT).show();}}).setNegativeButton("Keep run",null).show()).setNegativeButton("Keep running",null).show();
    }
    private void settings(){
        heading("Settings","Make RunZone work for the way you train.");
        android.content.SharedPreferences preferences=getSharedPreferences("settings",0);
        int low=preferences.getInt("low",120),high=preferences.getInt("high",150);

        smallTitle(content,"YOUR HEART");gap(content,12);LinearLayout you=card();
        settingRow(you,"Heart-rate zones","Target: Zone "+(preferences.getInt("profile",2)+1)+" · "+low+"–"+high+" bpm · suggestions from your runs",v->zones());
        divider(you);maxHeartLabel=settingRow(you,"Maximum heart rate",heartSetting("maxBpm",cachedObservedMax,"from your runs",190),v->heartRateSetting(true));
        divider(you);restingHeartLabel=settingRow(you,"Resting heart rate",heartSetting("restingBpm",cachedResting,"from morning checks",60),v->heartRateSetting(false));
        divider(you);settingRow(you,"Morning HRV check","2 minutes lying still with your strap on · sets readiness and resting HR",v->morningCheck());
        content.addView(you);gap(content,22);

        smallTitle(content,"DURING A RUN");gap(content,12);LinearLayout run=card();
        toggleRow(run,"BPM-paced zone beeps","Beep at your heart rate while you’re outside your zone. Otherwise you get one alert every 20 seconds.","zoneBeeps",false,enabled->{if(RunService.current!=null)RunService.current.updateZoneBeeps();});
        divider(run);toggleRow(run,"Announce each kilometre","Vibrate and read out your split time.","kmAlerts",true,null);
        divider(run);String target=distanceTargetLabel().replace("  ›","");settingRow(run,"Distance target",target.startsWith("Set distance")?"No target · get an alert when you reach a distance":target,v->distanceTarget());
        divider(run);settingRow(run,"Test strong vibrations","Try the below-zone, above-zone and distance patterns",v->testHaptics());
        note(run,"Alerts start after 5 seconds outside your target and repeat every 20 seconds, including during auto-pause. Manual pause silences them. Keep media volume up.");
        content.addView(run);gap(content,22);

        smallTitle(content,"AUTO-PAUSE");gap(content,12);LinearLayout auto=card();
        toggleRow(auto,"Auto-pause","Pauses after 10 seconds below your threshold pace and resumes after 3 seconds above it. Manual pauses always need a manual resume.","autoPause",false,null);
        divider(auto);settingRow(auto,"Pause when slower than",paceLabel(preferences.getInt("pausePace",720))+" min/km",v->autoPausePace());
        content.addView(auto);gap(content,22);

        smallTitle(content,"YOUR DATA");gap(content,12);LinearLayout data=card();
        settingRow(data,"Back up runs","Save every run, HRV check and training setting to one file",v->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(Backup.MIME).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"runzone-backup-"+new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date())+".zip"),81));
        divider(data);settingRow(data,"Restore from backup","Adds missing runs from a backup file. Runs already on this phone are kept.",v->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{Backup.MIME,"application/x-zip-compressed","application/octet-stream"}).addCategory(Intent.CATEGORY_OPENABLE),82));
        settingsAccountLabels.clear();CloudAccounts.refreshReadiness(this);
        for(String provider:CloudAccounts.PROVIDERS){divider(data);TextView label=settingRow(data,"Sync to "+CloudAccounts.name(provider),CloudAccounts.summary(this,provider),v->accountOptions(provider));settingsAccountLabels.put(provider,label);}
        note(data,"Recordings stay on your phone unless you back them up, export them or connect an account. Backups don’t include account sign-ins.");
        content.addView(data);gap(content,22);

        smallTitle(content,"SENSOR & PHONE");gap(content,12);LinearLayout phone=card();
        settingRow(phone,"Heart-rate sensor",RunService.current==null?"Connect for live BPM, even before a run":RunService.current.sensor,v->devices());
        if(RunService.current!=null){divider(phone);settingRow(phone,"Disconnect sensor","Stop live readings and keep your run active",v->{if(RunService.current!=null)RunService.current.disconnectSensor();getSharedPreferences("settings",0).edit().remove("device").apply();selected=null;show("Settings");});}
        divider(phone);backgroundLabel=settingRow(phone,"Screen-off recording",backgroundSummary(),v->backgroundSettings());
        divider(phone);settingRow(phone,"Location settings","Manage your phone’s GPS",v->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        divider(phone);settingRow(phone,"App permissions","Location, nearby devices and physical activity",v->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        note(phone,"For screen-off runs, allow background usage and set battery use to Unrestricted. Some phones also have sleeping-app or auto-start settings that RunZone can’t check.");
        content.addView(phone);gap(content,22);

        smallTitle(content,"ABOUT");gap(content,12);LinearLayout about=card();
        settingRow(about,"Check for updates","Installed: "+BuildConfig.VERSION_NAME,v->AppUpdates.check(this,true));
        divider(about);settingRow(about,"Release notifications","Daily checks when online",v->{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2);else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));});
        note(about,"Maps use an internet connection. © OpenStreetMap contributors.");
        content.addView(about);gap(content,20);content.addView(text("RUNZONE  /  VERSION "+BuildConfig.VERSION_NAME,10,MUTED));
        refreshTraining(null);
    }
    private TextView maxHeartLabel,restingHeartLabel;
    private int cachedObservedMax=-1,cachedResting=-1;
    private String readinessHint(){String title=getSharedPreferences("settings",0).getString("readinessTitle",null);return title==null?"See your training readiness  ›":"Today: "+title+"  ›";}
    private void divider(LinearLayout parent){gap(parent,12);View line=new View(this);line.setBackgroundColor(LINE);parent.addView(line,new LinearLayout.LayoutParams(-1,Math.max(1,dp(1)/2)));gap(parent,12);}
    private void toggleRow(LinearLayout parent,String title,String subtitle,String key,boolean fallback,java.util.function.Consumer<Boolean> changed){
        android.content.SharedPreferences prefs=getSharedPreferences("settings",0);
        LinearLayout r=row(),labels=column();labels.addView(bold(title,17,TEXT));gap(labels,6);TextView sub=text(subtitle,12,MUTED);sub.setLineSpacing(dp(3),1);labels.addView(sub);weighted(r,labels);
        Switch toggle=new Switch(this);toggle.setChecked(prefs.getBoolean(key,fallback));toggle.setContentDescription(title);toggle.setPadding(dp(12),0,0,0);
        toggle.setOnCheckedChangeListener((b,enabled)->{prefs.edit().putBoolean(key,enabled).apply();if(changed!=null)changed.accept(enabled);});
        r.addView(toggle);r.setMinimumHeight(dp(54));r.setOnClickListener(v->toggle.toggle());parent.addView(r);
    }
    private String heartSetting(String key,int detected,String source,int fallback){
        int stored=getSharedPreferences("settings",0).getInt(key,0);
        if(stored>0)return stored+" bpm · set by you";
        return detected>0?detected+" bpm · "+source:fallback+" bpm · estimate until RunZone has data";
    }
    private int maxBpm(){int stored=getSharedPreferences("settings",0).getInt("maxBpm",0);return stored>0?stored:cachedObservedMax>0?cachedObservedMax:190;}
    private int restingBpm(){int stored=getSharedPreferences("settings",0).getInt("restingBpm",0);return stored>0?stored:cachedResting>0?cachedResting:60;}
    private void heartRateSetting(boolean max){
        String key=max?"maxBpm":"restingBpm";int detected=max?cachedObservedMax:cachedResting;
        LinearLayout box=column();box.setPadding(dp(24),dp(8),dp(24),dp(8));
        box.addView(text(max?"Used for training load and zone suggestions. RunZone uses the highest heart rate you held for 10 seconds in the last 6 months, unless you set it.":"Used for training load. RunZone uses the lowest reading from your morning checks in the last 30 days, unless you set it.",14,MUTED));gap(box,12);
        EditText input=new EditText(this);input.setTextColor(TEXT);input.setInputType(2);input.setSingleLine(true);input.setHint(detected>0?Integer.toString(detected):max?"190":"60");
        int stored=getSharedPreferences("settings",0).getInt(key,0);if(stored>0)input.setText(Integer.toString(stored));input.setContentDescription(max?"Maximum heart rate":"Resting heart rate");box.addView(input);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(max?"Maximum heart rate":"Resting heart rate").setView(box).setPositiveButton("Save",null).setNeutralButton("Use automatic",(d,w)->{getSharedPreferences("settings",0).edit().remove(key).apply();show(screen);}).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{int bpm=Integer.parseInt(input.getText().toString().trim());if(max?(bpm<120||bpm>230):(bpm<30||bpm>110))throw new IllegalArgumentException();getSharedPreferences("settings",0).edit().putInt(key,bpm).apply();dialog.dismiss();show(screen);}catch(IllegalArgumentException e){input.setError(max?"Enter 120–230 bpm":"Enter 30–110 bpm");}}));dialog.show();
    }

    /** Everything derived from the run history for training load, readiness and zone suggestions. */
    private static final class Training {
        List<TrainingLoad.Day> days=Collections.emptyList();TrainingLoad.Readiness readiness;List<RunAnalysis> analyses=new ArrayList<>();
        int observedMax=-1,resting=-1,aerobic=-1,maxBpm,restingBpm;double ramp;double lastLoad=Double.NaN;
    }
    private Training computeTraining(java.io.File dir){
        Training t=new Training();long now=System.currentTimeMillis(),cutoff=now-180L*TrainingLoad.DAY;
        ArrayList<long[]> starts=new ArrayList<>();
        for(java.io.File file:runFiles(dir)){try{RunRecord r=RunRecord.read(file);if(r.startTime>=cutoff&&!r.pulses.isEmpty()){t.analyses.add(new RunAnalysis(r));starts.add(new long[]{r.startTime});}}catch(Exception ignored){}}
        List<TrainingLoad.Check> checks=MorningChecks.load(this);
        t.observedMax=TrainingLoad.observedMax(t.analyses);t.resting=MorningChecks.restingBpm(checks,now);
        ArrayList<RunAnalysis> recent=new ArrayList<>();for(RunAnalysis a:t.analyses)if(a.run.startTime>=now-90L*TrainingLoad.DAY)recent.add(a);
        t.aerobic=TrainingLoad.aerobicThreshold(recent);
        android.content.SharedPreferences prefs=getSharedPreferences("settings",0);
        t.maxBpm=prefs.getInt("maxBpm",0)>0?prefs.getInt("maxBpm",0):t.observedMax>0?t.observedMax:190;
        t.restingBpm=prefs.getInt("restingBpm",0)>0?prefs.getInt("restingBpm",0):t.resting>0?t.resting:60;
        ArrayList<Double> loads=new ArrayList<>();for(RunAnalysis a:t.analyses)loads.add(TrainingLoad.trimp(a.heart,t.restingBpm,t.maxBpm));
        if(!loads.isEmpty())t.lastLoad=loads.get(loads.size()-1);
        t.days=TrainingLoad.days(starts,loads,now,84,TimeZone.getDefault());
        TrainingLoad.Day today=t.days.get(t.days.size()-1);t.ramp=today.fitness-t.days.get(t.days.size()-8).fitness;
        t.readiness=TrainingLoad.readiness(today,checks,now,t.ramp);
        prefs.edit().putString("readinessTitle",t.readiness.title).putInt("readinessLevel",t.readiness.level).apply();
        return t;
    }
    /** Computes training in the background and refreshes the cached heart-rate labels; then runs the callback on the main thread. */
    private void refreshTraining(java.util.function.Consumer<Training> done){
        final java.io.File dir=getFilesDir();final String startedOn=screen;
        background.execute(()->{Training t=computeTraining(dir);handler.post(()->{
            cachedObservedMax=t.observedMax;cachedResting=t.resting;
            if("Settings".equals(screen)&&maxHeartLabel!=null){maxHeartLabel.setText(heartSetting("maxBpm",cachedObservedMax,"from your runs",190));restingHeartLabel.setText(heartSetting("restingBpm",cachedResting,"from morning checks",60));}
            if(done!=null&&startedOn.equals(screen))done.accept(t);
        });});
    }
    private void trainingSection(){
        LinearLayout c=section("TRAINING","Readiness");TextView waiting=text("Adding up your training load…",13,MUTED);c.addView(waiting);endSection();
        refreshTraining(t->{
            c.removeAllViews();TrainingLoad.Readiness r=t.readiness;
            int tone=r.level>0?LIME:r.level<0?0xffffbc8a:TEXT;
            c.addView(bold(r.title,24,tone));gap(c,8);TextView advice=text(r.advice,14,TEXT);advice.setLineSpacing(dp(3),1);c.addView(advice);gap(c,18);
            TrainingLoad.Day today=t.days.get(t.days.size()-1);
            LinearLayout row=row();stat(row,"FITNESS",String.format(Locale.getDefault(),"%.0f",today.fitness),"42-day load");stat(row,"FATIGUE",String.format(Locale.getDefault(),"%.0f",today.fatigue),"7-day load");stat(row,"FORM",String.format(Locale.getDefault(),"%+.0f",today.form),"fitness − fatigue");c.addView(row);
            double[] x=indexes(t.days.size()),fit=new double[x.length],fat=new double[x.length],form=new double[x.length],load=new double[x.length];
            for(int i=0;i<x.length;i++){TrainingLoad.Day d=t.days.get(i);fit[i]=d.fitness;fat[i]=d.fatigue;form[i]=d.form;load[i]=d.load>0?d.load:Double.NaN;}
            java.text.DateFormat day=java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT);
            SeriesChart.Format date=v->{int i=(int)Math.round(v);return i>=0&&i<t.days.size()?day.format(new Date(t.days.get(i).start)):"";};
            addChart(c,new SeriesChart(this,x,fit,"Fitness over the last 12 weeks").color(0xff7fb4ff).xFormat(date).labels("Fitness · 12 weeks","Today"));
            addChart(c,new SeriesChart(this,x,form,"Form over the last 12 weeks").color(0xffd4a5ff).format(v->String.format(Locale.getDefault(),"%+.0f",v)).reference(0).xFormat(date).labels("Form · dashed line 0","Today"));
            addChart(c,new SeriesChart(this,x,load,"Training load of each day").bars().color(0xffffbc8a).xFormat(date).labels("Daily load","Today"));
            note(c,"Load is TRIMP from heart rate, using max "+t.maxBpm+" and resting "+t.restingBpm+" bpm. Fitness builds over about six weeks and fatigue over about one. Form above +5 means fresh; below −30 means you need rest. Readiness also uses your morning HRV checks.");
            gap(c,14);c.addView(pill("Morning HRV check  →",0xff2a3835,LIME,v->morningCheck()));
        });
    }

    private void morningCheck(){
        RunService service=RunService.current;
        if(service!=null&&service.running){Toast.makeText(this,"Finish your run before a morning check",Toast.LENGTH_LONG).show();return;}
        if(service==null||service.freshBpm()<=0){new AlertDialog.Builder(this).setTitle("Connect your strap").setMessage("The morning check needs a heart-rate strap that sends beat-to-beat intervals. Most chest straps do; arm and watch sensors often don’t.\n\nPut it on, then connect it.").setPositiveButton("Connect sensor",(d,w)->devices()).setNegativeButton("Cancel",null).show();return;}
        LinearLayout box=column();box.setPadding(dp(24),dp(12),dp(24),dp(12));
        TextView intro=text("Do this soon after waking, before coffee. Lie or sit still and breathe normally for 2 minutes.",14,MUTED);intro.setLineSpacing(dp(3),1);box.addView(intro);gap(box,18);
        TextView clock=bold("2:00",44,TEXT);clock.setGravity(Gravity.CENTER);box.addView(clock);gap(box,8);
        TextView live=text("Waiting for beats…",14,MUTED);live.setGravity(Gravity.CENTER);box.addView(live);
        ArrayList<Double> intervals=new ArrayList<>();long[] startedAt={0};
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Morning HRV check").setView(box).setNegativeButton("Cancel",null).setCancelable(false).create();
        Runnable[] tick=new Runnable[1];
        Runnable stop=()->{if(RunService.current!=null)RunService.current.beatListener=null;handler.removeCallbacks(tick[0]);};
        tick[0]=()->{
            RunService s=RunService.current;if(s==null){stop.run();dialog.dismiss();return;}
            int bpm=s.freshBpm();
            if(startedAt[0]==0){if(intervals.size()>=3){startedAt[0]=SystemClock.elapsedRealtime();intervals.clear();}handler.postDelayed(tick[0],1000);return;}
            long left=Math.max(0,120000-(SystemClock.elapsedRealtime()-startedAt[0]));
            clock.setText(String.format(Locale.getDefault(),"%d:%02d",left/60000,(left/1000)%60));
            live.setText((bpm>0?bpm+" bpm  ·  ":"")+intervals.size()+" beats");
            if(left>0){handler.postDelayed(tick[0],1000);return;}
            stop.run();dialog.dismiss();
            List<Double> clean=RunAnalysis.cleanIntervals(intervals);
            if(clean.size()<60||clean.size()<intervals.size()*0.8){new AlertDialog.Builder(this).setTitle("Check didn’t work").setMessage(intervals.isEmpty()?"Your sensor isn’t sending beat-to-beat intervals, so HRV can’t be measured. A chest strap usually works.":"Too many irregular beats were recorded. Moisten the strap’s contacts, stay still and try again.").setPositiveButton("OK",null).show();return;}
            double rmssd=RunAnalysis.rmssd(clean),mean=0;for(double v:clean)mean+=v;mean/=clean.size();int resting=(int)Math.round(60000/mean);
            background.execute(()->{try{MorningChecks.add(this,Collections.singletonList(new TrainingLoad.Check(System.currentTimeMillis(),rmssd,resting)));}catch(Exception e){android.util.Log.w("RunZone","Could not save HRV check",e);}
                handler.post(()->{new AlertDialog.Builder(this).setTitle("Morning check saved").setMessage(String.format(Locale.getDefault(),"HRV (RMSSD): %.0f ms\nResting heart rate: %d bpm\n\nCheck every morning for a week to set your baseline. Readiness compares each morning with your last 7.",rmssd,resting)).setPositiveButton("See readiness",(d,w)->show("History")).setNegativeButton("Close",(d,w)->show(screen)).show();});});
        };
        service.beatListener=ticks->{for(int t:ticks)if(t>0)intervals.add(t*1000.0/1024);};
        dialog.setOnDismissListener(d->stop.run());
        dialog.show();handler.postDelayed(tick[0],1000);
    }
    private TextView settingRow(LinearLayout parent,String title,String subtitle,View.OnClickListener listener){LinearLayout r=row(),labels=column();labels.addView(bold(title,17,TEXT));gap(labels,6);TextView sub=text(subtitle,12,MUTED);sub.setLineSpacing(dp(3),1);labels.addView(sub);weighted(r,labels);TextView arrow=text("›",27,LIME);arrow.setPadding(dp(12),dp(8),0,dp(8));r.addView(arrow);r.setMinimumHeight(dp(54));r.setOnClickListener(listener);parent.addView(r);return sub;}
    private void historyPage(){
        heading("Your runs","Every run is a step forward.");java.io.File[] files=new java.io.File(getFilesDir(),"runs").listFiles();int count=0;
        if(files!=null&&files.length>0){trainingSection();trendsSection();}
        if(files!=null){Arrays.sort(files,Collections.reverseOrder());for(java.io.File file:files)try{
            org.json.JSONObject run=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));LinearLayout c=card();smallTitle(c,java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new Date(run.getLong("date"))).toUpperCase(Locale.getDefault()));gap(c,12);
            c.addView(bold(String.format(Locale.getDefault(),"%.2f km",run.getDouble("meters")/1000),30,TEXT));gap(c,8);long seconds=run.getLong("duration")/1000;c.addView(text(String.format(Locale.getDefault(),"%s  ·  %s",seconds>=3600?String.format(Locale.getDefault(),"%d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60):String.format(Locale.getDefault(),"%d:%02d",seconds/60,seconds%60),java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new Date(run.getLong("date")))),13,MUTED));gap(c,12);c.addView(bold("View run & stats  ›",13,LIME));c.setOnClickListener(v->openRun(file));content.addView(c);gap(content,12);count++;
        }catch(Exception ignored){}}
        if(count==0){LinearLayout c=card();c.addView(bold("Your first run starts here.",22,TEXT));gap(c,12);c.addView(text("Finish and save a run to see your progress here.",14,MUTED));gap(c,20);c.addView(pill("Let’s go  →",LIME,BG,v->show("Run")));content.addView(c);}
    }
    private void openRun(java.io.File file){try{detailRun=RunRecord.read(file);detailFile=file;show("Detail");}catch(Exception e){show("History");Toast.makeText(this,"This recording couldn't be opened.",Toast.LENGTH_LONG).show();}}
    private static String durationLabel(long ms){long s=ms/1000;return s>=3600?String.format(Locale.getDefault(),"%d:%02d:%02d",s/3600,(s/60)%60,s%60):String.format(Locale.getDefault(),"%02d:%02d",s/60,s%60);}
    private static String paceLabel(long seconds){return String.format(Locale.getDefault(),"%d:%02d",seconds/60,seconds%60);}
    private void detailPage(){
        RunRecord run=detailRun;heading("Run details",java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.LONG,java.text.DateFormat.SHORT).format(new Date(run.startTime)));
        LinearLayout summary=card(),first=row(),second=row();stat(first,"DISTANCE",String.format(Locale.getDefault(),"%.2f",run.meters/1000),"km");stat(first,"ACTIVE TIME",durationLabel(run.durationMs),"excludes pauses");stat(first,"AVG PACE",run.meters>0?paceLabel(Math.round(run.durationMs/run.meters)):"—","min / km");summary.addView(first);gap(summary,22);
        stat(second,"AVG HR",run.averageBpm()>0?Integer.toString(run.averageBpm()):"—","bpm");stat(second,"MAX HR",run.maxBpm()>0?Integer.toString(run.maxBpm()):"—","bpm");stat(second,"ELAPSED",durationLabel(run.elapsedMs),"including pauses");summary.addView(second);content.addView(summary);gap(content,14);content.addView(pill("Share run  ↗",LIME,BG,v->shareRun()));gap(content,22);
        smallTitle(content,"YOUR ROUTE");gap(content,12);ArrayList<Location> route=new ArrayList<>();int track=-1;for(RunRecord.Point point:run.points){if(track!=-1&&track!=point.track)route.add(null);Location location=new Location("recording");location.setLatitude(point.lat);location.setLongitude(point.lon);route.add(location);track=point.track;}
        FrameLayout frame=new FrameLayout(this);frame.setBackground(shape(0xffdee7da,22));frame.setClipToOutline(true);map=new LiveMapView(this);frame.addView(map,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout controls=column();controls.addView(mapControl("+",v->map.zoomBy(1)));gap(controls,2);controls.addView(mapControl("−",v->map.zoomBy(-1)));gap(controls,2);TextView fit=mapControl("Fit",v->map.fitRoute(route));fit.setTextSize(13);controls.addView(fit);FrameLayout.LayoutParams controlPosition=new FrameLayout.LayoutParams(dp(44),-2,Gravity.TOP|Gravity.RIGHT);controlPosition.setMargins(0,dp(12),dp(12),0);frame.addView(controls,controlPosition);
        TextView attribution=text("© OpenStreetMap contributors",10,0xff294333);attribution.setBackgroundColor(0xeef8fbf4);attribution.setPadding(dp(8),dp(4),dp(8),dp(4));attribution.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/copyright"))));frame.addView(attribution,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT));content.addView(frame,new LinearLayout.LayoutParams(-1,dp(300)));map.fitRoute(route);gap(content,22);
        smallTitle(content,"HEART RATE");gap(content,12);LinearLayout heartCard=card();
        if(run.pulses.isEmpty())heartCard.addView(text("No heart-rate data was recorded for this run.",14,MUTED));else{heartCard.addView(bold("Your effort, beat by beat",18,TEXT));gap(heartCard,10);heartCard.addView(new HeartChart(this,run),new LinearLayout.LayoutParams(-1,dp(150)));if(run.low>0){gap(heartCard,10);heartCard.addView(text("Starting target: "+run.low+"–"+run.high+" bpm",12,MUTED));}}
        content.addView(heartCard);gap(content,22);RunAnalysis analysis=new RunAnalysis(run);analysisSections(run,analysis);List<RunRecord.Split> splits=run.splits();
        if(!splits.isEmpty()){smallTitle(content,"KILOMETRE SPLITS");gap(content,12);LinearLayout splitCard=card();int index=1;for(RunRecord.Split split:splits){LinearLayout r=row();weighted(r,text(split.meters>=999.5?"Kilometre "+index:String.format(Locale.getDefault(),"Last %.2f km",split.meters/1000),14,TEXT));if(analysis.hasAltitude&&index-1<analysis.gapSplits.size())r.addView(text("GAP "+RunAnalysis.pace(analysis.gapSplits.get(index-1))+"   ",12,MUTED));r.addView(bold(paceLabel(Math.round(split.durationMs/split.meters))+" /km",14,LIME));splitCard.addView(r);gap(splitCard,12);index++;}content.addView(splitCard);gap(content,22);}
        sameRouteSection(run);
        if(run.legacy){content.addView(text("Older recording: start time is estimated, and kilometre splits are unavailable. Heart-rate stats use the samples that were saved.",12,MUTED));gap(content,22);}
        smallTitle(content,"SYNC & EXPORT");gap(content,12);LinearLayout sync=card();syncLabels.clear();
        for(String provider:CloudAccounts.PROVIDERS){LinearLayout r=row(),labels=column();labels.addView(bold(CloudAccounts.name(provider),16,TEXT));gap(labels,5);TextView state=text(CloudSync.status(this,detailFile.getName(),provider),12,MUTED);syncLabels.put(provider,state);labels.addView(state);weighted(r,labels);r.addView(pill("›",0xff2a3835,LIME,v->accountOptions(provider)));sync.addView(r);gap(sync,14);}
        sync.addView(pill("Export FIT file  ↗",LIME,BG,v->prepareExport()));gap(sync,12);sync.addView(text("Compatible with Smashrun, Runalyze and Strava. Save a file or share it with another app.",12,MUTED));content.addView(sync);
        gap(content,16);TextView delete=bold("Delete run",15,0xffff8a80);delete.setPadding(dp(8),dp(12),dp(8),dp(12));delete.setOnClickListener(v->confirmDelete());content.addView(delete);
    }
    private static double[] indexes(int n){double[] x=new double[n];for(int i=0;i<n;i++)x[i]=i;return x;}
    private static String number(double value,String format){return Double.isFinite(value)?String.format(Locale.getDefault(),format,value):"—";}
    private static String minutes(long seconds){return seconds>=3600?String.format(Locale.getDefault(),"%d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60):String.format(Locale.getDefault(),"%d:%02d",seconds/60,seconds%60);}
    private void addChart(LinearLayout card,SeriesChart chart){gap(card,14);card.addView(chart,new LinearLayout.LayoutParams(-1,dp(150)));}
    private void note(LinearLayout card,String value){gap(card,12);TextView t=text(value,12,MUTED);t.setLineSpacing(dp(3),1);card.addView(t);}
    private LinearLayout section(String title,String headline){smallTitle(content,title);gap(content,12);LinearLayout c=card();if(headline!=null){c.addView(bold(headline,18,TEXT));gap(c,14);}content.addView(c);return c;}
    private void endSection(){gap(content,22);}
    private void analysisSections(RunRecord run,RunAnalysis a){
        String end=durationLabel(run.durationMs);double[] time=indexes(a.n);
        if(a.hasRoute&&a.hasHeart){
            LinearLayout c=section("EFFICIENCY","How far each heartbeat carries you");LinearLayout r=row();
            stat(r,"M / BEAT",number(a.metresPerBeat,"%.2f"),"metres");stat(r,"GAP",RunAnalysis.pace(a.gapSecondsPerKm),"min / km");stat(r,"DECOUPLING",number(a.decouplingPercent,"%+.1f%%"),"Pa:HR");c.addView(r);
            addChart(c,new SeriesChart(this,time,a.efficiency60,"Rolling 60-second efficiency in metres per heartbeat").format(v->String.format(Locale.getDefault(),"%.2f",v)).labels("Rolling 60 s · m/beat",end).reference(a.metresPerBeat));
            String drift=!Double.isFinite(a.decouplingPercent)?"Decoupling needs at least 20 minutes of running with heart rate.":
                a.decouplingPercent<5?"Under 5% decoupling: heart rate held steady against pace. Your aerobic base handled this effort.":
                a.decouplingPercent<10?"5–10% decoupling: some cardiac drift. Heat, hills, fuelling or an effort near your aerobic limit.":
                "Over 10% decoupling: efficiency faded a lot in the second half. This effort was beyond your current aerobic base.";
            note(c,drift+(a.decouplingWarmupSeconds>0?" The first 5 minutes are left out as warm-up.":"")+" Efficiency and decoupling use grade-adjusted distance.");endSection();
        }
        if(a.hasRoute){
            LinearLayout c=section("PACE & TERRAIN",a.hasAltitude?"Grade-adjusted pace":"Pace");LinearLayout r=row();
            stat(r,"AVG PACE",run.meters>0?paceLabel(Math.round(run.durationMs/run.meters)):"—","actual");stat(r,"GAP",RunAnalysis.pace(a.gapSecondsPerKm),"flat effort");
            stat(r,"CLIMB",a.hasAltitude?number(a.elevationGain,"+%.0f"):"—",a.hasAltitude?number(a.elevationLoss,"−%.0f m down"):"no altitude");c.addView(r);
            SeriesChart chart=new SeriesChart(this,time,a.gapPace,"Grade-adjusted pace over the run").inverted().format(RunAnalysis::pace).labels(a.hasAltitude?"GAP · elevation shaded":"Pace, 30 s average",end);
            if(a.hasAltitude)chart.shade(a.altitude);addChart(c,chart);
            note(c,"Grade-adjusted pace is the flat-ground pace that would cost the same energy, using Minetti’s cost-of-running curve. Climbs make it faster than your actual pace, and gentle descents make it slower.");endSection();
        }
        if(a.hasHeart&&run.low>0&&a.secondsBelow+a.secondsIn+a.secondsAbove>0){
            LinearLayout c=section("TIME IN ZONE","Target "+run.low+"–"+run.high+" bpm");long total=a.secondsBelow+a.secondsIn+a.secondsAbove;
            int[] values={a.secondsBelow,a.secondsIn,a.secondsAbove};String[] names={"Below","In zone","Above"};int[] colors={0xff7fb4ff,LIME,0xffffbc8a};
            for(int i=0;i<3;i++){
                LinearLayout r=row();r.addView(text(names[i],13,TEXT),new LinearLayout.LayoutParams(dp(64),-2));
                LinearLayout track=row();track.setBackground(shape(0xff2a3835,6));View fill=new View(this);fill.setBackground(shape(colors[i],6));
                track.addView(fill,new LinearLayout.LayoutParams(0,dp(12),Math.max(0.0001f,values[i])));track.addView(new View(this),new LinearLayout.LayoutParams(0,dp(12),Math.max(0.0001f,total-values[i])));
                weighted(r,track);r.addView(bold(String.format(Locale.getDefault(),"  %d%%  %s",Math.round(100.0*values[i]/total),minutes(values[i])),12,MUTED));c.addView(r);gap(c,10);
            }
            endSection();
        }
        if(a.hasCadence){
            LinearLayout c=section("STRIDE","Cadence and step length");LinearLayout r=row();
            stat(r,"CADENCE",number(a.averageCadence,"%.0f"),"steps / min");stat(r,"STEP LENGTH",number(a.averageStepLength,"%.2f"),"metres (est.)");c.addView(r);
            if(Double.isFinite(a.averageStepLength))addChart(c,new SeriesChart(this,time,a.stepLength,"Estimated step length over the run").color(0xff7fb4ff).format(v->String.format(Locale.getDefault(),"%.2f m",v)).labels("Step length",end));
            else addChart(c,new SeriesChart(this,time,a.cadence,"Cadence over the run").color(0xff7fb4ff).labels("Cadence, spm",end));
            note(c,"Step length is estimated from GPS speed divided by the phone’s step count, so it is only as accurate as both sensors.");endSection();
        }
        if(a.hasHeart){
            LinearLayout c=section("HEART-RATE VARIABILITY","During steady effort");
            if(a.hrvWindows.isEmpty())c.addView(text("No steady two-minute stretches with beat-to-beat intervals were found. HRV needs a chest strap that sends RR intervals, and a section of even effort.",13,MUTED));
            else{
                LinearLayout r=row();stat(r,"RMSSD",number(a.rmssd,"%.1f"),"ms");stat(r,"DFA α1",number(a.alpha1,"%.2f"),"median");stat(r,"STEADY",Integer.toString(a.hrvWindows.size()*2),"minutes");c.addView(r);
                double[] x=new double[a.hrvWindows.size()],rm=new double[x.length],al=new double[x.length];
                for(int i=0;i<x.length;i++){RunAnalysis.HrvWindow w=a.hrvWindows.get(i);x[i]=w.startSecond;rm[i]=w.rmssd;al[i]=w.alpha1;}
                addChart(c,new SeriesChart(this,x,al,"DFA alpha 1 for each steady two-minute window").color(0xffd4a5ff).format(v->String.format(Locale.getDefault(),"%.2f",v)).reference(0.75).labels("DFA α1 · dashed 0.75",end));
                addChart(c,new SeriesChart(this,x,rm,"RMSSD for each steady two-minute window").color(0xff7fb4ff).labels("RMSSD, ms",end));
                note(c,"DFA α1 above 0.75 suggests you were under your aerobic threshold. Around 0.5 is near the anaerobic threshold. RMSSD is very low while running, so compare it between similar runs, not with resting values.");
            }
            endSection();
        }
        LinearLayout recovery=section("HEART-RATE RECOVERY",null);
        if(a.recoverySeconds.length<5)recovery.addView(text("Keep your strap on after you tap Finish. RunZone records heart rate for 2 minutes afterwards, while the sensor stays connected, and shows how quickly it drops.",13,MUTED));
        else{
            LinearLayout r=row();stat(r,"AT FINISH",a.finishBpm>0?Integer.toString(a.finishBpm):"—","bpm");stat(r,"1 MIN",a.recovery60>=0?"−"+a.recovery60:"—","bpm drop");stat(r,"2 MIN",a.recovery120>=0?"−"+a.recovery120:"—","bpm drop");recovery.addView(r);
            addChart(recovery,new SeriesChart(this,a.recoverySeconds,a.recoveryBpm,"Heart rate in the two minutes after finishing").color(0xffff8a80).xFormat(v->"+"+SeriesChart.clock(v)).format(v->String.format(Locale.getDefault(),"%.0f bpm",v)).labels("Finish","+2:00"));
            note(recovery,a.recovery60<0?"Not enough readings around the one-minute mark.":a.recovery60>=20?"A drop of 20+ bpm in the first minute is a strong recovery.":a.recovery60>=12?"A 12–20 bpm drop in the first minute is typical.":"Under 12 bpm in the first minute is slow. Fatigue, heat and illness all slow recovery, so compare after similar runs.");
        }
        endSection();
        boolean anyBest=false;for(long b:a.bestEfforts)if(b>0)anyBest=true;
        if(anyBest){
            LinearLayout c=section("BEST EFFORTS IN THIS RUN",null);
            for(int i=0;i<a.bestEfforts.length;i++){if(a.bestEfforts[i]<=0)continue;LinearLayout r=row();weighted(r,text(RunAnalysis.BEST_EFFORT_NAMES[i],14,TEXT));r.addView(text(paceLabel(Math.round(a.bestEfforts[i]*1000.0/RunAnalysis.BEST_EFFORT_METERS[i]))+" /km   ",12,MUTED));r.addView(bold(minutes(a.bestEfforts[i]),14,LIME));c.addView(r);gap(c,12);}
            endSection();
        }
    }
    /** Saved runs, oldest first (file names are save timestamps). */
    private static java.io.File[] runFiles(java.io.File dir){java.io.File[] files=new java.io.File(dir,"runs").listFiles((d,name)->name.endsWith(".json"));if(files==null)return new java.io.File[0];Arrays.sort(files);return files;}
    private void sameRouteSection(RunRecord run){
        LinearLayout c=section("SAME ROUTE","Earlier runs on this course");TextView waiting=text("Looking for earlier runs on this route…",13,MUTED);c.addView(waiting);endSection();
        final java.io.File current=detailFile;final java.io.File dir=getFilesDir();
        background.execute(()->{
            ArrayList<RunRecord> matches=new ArrayList<>();ArrayList<RunAnalysis> analyses=new ArrayList<>();
            for(java.io.File file:runFiles(dir)){
                try{RunRecord other=file.equals(current)?run:RunRecord.read(file);if(other==run||RunAnalysis.sameRoute(run,other)){matches.add(other);analyses.add(new RunAnalysis(other));}}catch(Exception ignored){}
            }
            handler.post(()->{
                if(!"Detail".equals(screen)||detailRun!=run)return;c.removeView(waiting);
                if(matches.size()<2){c.addView(text("This is the only run on this route so far. Run it again to compare.",13,MUTED));return;}
                int self=matches.indexOf(run);double[] x=indexes(matches.size()),pace=new double[x.length],eff=new double[x.length];boolean anyEff=false;
                for(int i=0;i<x.length;i++){RunAnalysis an=analyses.get(i);RunRecord m=matches.get(i);pace[i]=Double.isFinite(an.gapSecondsPerKm)?an.gapSecondsPerKm:(m.meters>0?m.durationMs/m.meters:Double.NaN);eff[i]=an.metresPerBeat;anyEff|=Double.isFinite(eff[i]);}
                c.addView(text(matches.size()+" runs, oldest first. This run is highlighted.",13,MUTED));java.text.DateFormat shortDate=java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT);SeriesChart.Format runDate=v->{int i=(int)Math.round(v);return i>=0&&i<matches.size()?shortDate.format(new Date(matches.get(i).startTime)):"";};
                addChart(c,new SeriesChart(this,x,pace,"Grade-adjusted pace on each run of this route").inverted().format(RunAnalysis::pace).highlight(self).xFormat(runDate).labels("GAP per run","Latest"));
                if(anyEff)addChart(c,new SeriesChart(this,x,eff,"Metres per heartbeat on each run of this route").color(0xff7fb4ff).format(v->String.format(Locale.getDefault(),"%.2f",v)).highlight(self).xFormat(runDate).labels("Metres per beat","Latest"));
                gap(c,14);java.text.DateFormat date=java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
                for(int i=matches.size()-1;i>=0;i--){
                    RunRecord m=matches.get(i);RunAnalysis an=analyses.get(i);LinearLayout r=row();
                    weighted(r,text(date.format(new Date(m.startTime))+(i==self?"  · this run":""),13,i==self?LIME:TEXT));
                    r.addView(text(minutes(m.durationMs/1000)+"  ·  "+(m.averageBpm()>0?m.averageBpm()+" bpm  ·  ":"")+(Double.isFinite(an.metresPerBeat)?number(an.metresPerBeat,"%.2f")+" m/b  ·  ":"")+RunAnalysis.pace(an.gapSecondsPerKm),12,MUTED));
                    c.addView(r);gap(c,10);
                }
            });
        });
    }
    private void trendsSection(){
        LinearLayout c=section("TRENDS","Your last 12 weeks");TextView waiting=text("Crunching your runs…",13,MUTED);c.addView(waiting);endSection();
        final java.io.File dir=getFilesDir();
        background.execute(()->{
            ArrayList<RunRecord> runs=new ArrayList<>();ArrayList<RunAnalysis> analyses=new ArrayList<>();
            long now=System.currentTimeMillis(),cutoff=now-84L*86400000;
            for(java.io.File file:runFiles(dir)){try{RunRecord r=RunRecord.read(file);if(r.startTime>=cutoff){runs.add(r);analyses.add(new RunAnalysis(r));}}catch(Exception ignored){}}
            handler.post(()->{
                if(!"History".equals(screen))return;c.removeView(waiting);
                if(runs.isEmpty()){c.addView(text("No runs in the last 12 weeks.",13,MUTED));return;}
                double[] weeks=indexes(12),km=new double[12];
                for(RunRecord r:runs){int w=11-(int)((now-r.startTime)/(7L*86400000));if(w>=0&&w<12)km[w]+=r.meters/1000;}
                double total=0;for(double v:km)total+=v;
                LinearLayout row=row();stat(row,"12 WEEKS",String.format(Locale.getDefault(),"%.0f",total),"km");stat(row,"PER WEEK",String.format(Locale.getDefault(),"%.1f",total/12),"km avg");stat(row,"RUNS",Integer.toString(runs.size()),"recorded");c.addView(row);
                addChart(c,new SeriesChart(this,weeks,km,"Kilometres run each week for the last 12 weeks").bars().format(v->String.format(Locale.getDefault(),"%.0f km",v)).xFormat(v->{int w=11-(int)Math.round(v);return w==0?"This week":w+" wk ago";}).highlight(11).labels("Weekly km · 12 wk ago","This week"));
                double[] x=indexes(runs.size()),eff=new double[x.length],gap=new double[x.length],drift=new double[x.length];boolean anyEff=false,anyGap=false,anyDrift=false;java.text.DateFormat shortDate=java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT);SeriesChart.Format runDate=v->{int i=(int)Math.round(v);return i>=0&&i<runs.size()?shortDate.format(new Date(runs.get(i).startTime)):"";};
                for(int i=0;i<x.length;i++){RunAnalysis a=analyses.get(i);eff[i]=a.metresPerBeat;gap[i]=a.gapSecondsPerKm;drift[i]=a.decouplingPercent;anyEff|=Double.isFinite(eff[i]);anyGap|=Double.isFinite(gap[i]);anyDrift|=Double.isFinite(drift[i]);}
                if(anyEff)addChart(c,new SeriesChart(this,x,eff,"Metres per heartbeat for each run").color(0xff7fb4ff).format(v->String.format(Locale.getDefault(),"%.2f",v)).xFormat(runDate).labels("Metres per beat · per run","Latest"));
                if(anyGap)addChart(c,new SeriesChart(this,x,gap,"Grade-adjusted pace for each run").inverted().format(RunAnalysis::pace).xFormat(runDate).labels("Grade-adjusted pace · per run","Latest"));
                if(anyDrift)addChart(c,new SeriesChart(this,x,drift,"Aerobic decoupling for each run of 20 minutes or more").color(0xffffbc8a).format(v->String.format(Locale.getDefault(),"%.0f%%",v)).reference(5).xFormat(runDate).labels("Decoupling · dashed 5%","Latest"));
                note(c,"Rising metres per beat at a similar effort means your aerobic fitness is improving. Decoupling under 5% on long easy runs means your base is solid at that duration.");
            });
        });
    }
    private ShareCard.Format shareFormat=ShareCard.Format.POST;
    private ShareCard.Theme shareTheme=ShareCard.Theme.NIGHT;
    private Bitmap shareBitmap;
    private void shareRun(){
        RunRecord run=detailRun;if(run==null)return;String id=detailFile.getName().replace(".json","");
        RunAnalysis analysis=new RunAnalysis(run);
        LinearLayout box=column();box.setPadding(dp(20),dp(16),dp(20),dp(8));
        FrameLayout frame=new FrameLayout(this);frame.setBackground(shape(0xff0b1112,18));frame.setClipToOutline(true);
        ImageView preview=new ImageView(this);preview.setAdjustViewBounds(true);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setContentDescription("Share card preview");
        frame.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        int previewHeight=Math.round(getResources().getDisplayMetrics().heightPixels*0.5f);
        box.addView(frame,new LinearLayout.LayoutParams(-1,previewHeight));gap(box,16);
        LinearLayout formats=row(),themes=row();ArrayList<TextView> formatPills=new ArrayList<>(),themePills=new ArrayList<>();
        Runnable[] redraw=new Runnable[1];
        Runnable style=()->{
            for(int i=0;i<formatPills.size();i++){boolean on=ShareCard.Format.values()[i]==shareFormat;formatPills.get(i).setBackground(shape(on?0xff263c2c:CARD,14));formatPills.get(i).setTextColor(on?LIME:MUTED);}
            for(int i=0;i<themePills.size();i++){boolean on=ShareCard.Theme.values()[i]==shareTheme;themePills.get(i).setBackground(shape(on?0xff263c2c:CARD,14));themePills.get(i).setTextColor(on?LIME:MUTED);}
        };
        for(ShareCard.Format f:ShareCard.Format.values()){TextView t=pill(f.label,CARD,MUTED,v->{shareFormat=f;style.run();redraw[0].run();});t.setMinHeight(dp(40));t.setPadding(dp(8),dp(8),dp(8),dp(8));formatPills.add(t);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.rightMargin=dp(6);formats.addView(t,lp);}
        for(ShareCard.Theme th:ShareCard.Theme.values()){TextView t=pill(th.label,CARD,MUTED,v->{shareTheme=th;style.run();redraw[0].run();});t.setMinHeight(dp(40));t.setPadding(dp(8),dp(8),dp(8),dp(8));themePills.add(t);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.rightMargin=dp(6);themes.addView(t,lp);}
        smallTitle(box,"SHAPE");gap(box,8);box.addView(formats);gap(box,14);smallTitle(box,"STYLE");gap(box,8);box.addView(themes);
        style.run();
        redraw[0]=()->{
            ShareCard.Format f=shareFormat;ShareCard.Theme th=shareTheme;
            background.execute(()->{Bitmap b=ShareCard.render(run,analysis,f,th);handler.post(()->{if(f!=shareFormat||th!=shareTheme){b.recycle();return;}shareBitmap=b;preview.setImageBitmap(b);});});
        };
        redraw[0].run();
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle("Share your run").setView(scroll)
            .setPositiveButton("Share  ↗",null).setNegativeButton("Close",null);
        if(Build.VERSION.SDK_INT>=29)builder.setNeutralButton("Save to Photos",null);
        AlertDialog dialog=builder.create();
        dialog.setOnShowListener(d->{
            dialog.getButton(-1).setOnClickListener(v->{Bitmap b=shareBitmap;if(b==null)return;background.execute(()->{try{
                java.io.File dir=new java.io.File(getCacheDir(),"exports");if(!dir.exists()&&!dir.mkdirs())throw new java.io.IOException();
                java.io.File file=new java.io.File(dir,"runzone-"+id+"-"+shareFormat.name().toLowerCase(Locale.ROOT)+".png");
                try(java.io.OutputStream out=new java.io.FileOutputStream(file)){b.compress(Bitmap.CompressFormat.PNG,100,out);}
                Uri uri=new Uri.Builder().scheme("content").authority(getPackageName()+".exports").appendPath(file.getName()).build();
                runOnUiThread(()->{Intent send=new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newRawUri("Run",uri));startActivity(Intent.createChooser(send,"Share your run"));});
            }catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Couldn't prepare the image",Toast.LENGTH_LONG).show());}});});
            if(Build.VERSION.SDK_INT>=29)dialog.getButton(-3).setOnClickListener(v->{Bitmap b=shareBitmap;if(b==null)return;background.execute(()->{
                android.content.ContentValues values=new android.content.ContentValues();
                values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"runzone-"+id+"-"+shareFormat.name().toLowerCase(Locale.ROOT)+".png");
                values.put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png");
                values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,android.os.Environment.DIRECTORY_PICTURES+"/RunZone");
                Uri uri=getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
                boolean ok=false;if(uri!=null)try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){ok=out!=null&&b.compress(Bitmap.CompressFormat.PNG,100,out);}catch(Exception ignored){}
                boolean saved=ok;runOnUiThread(()->Toast.makeText(this,saved?"Saved to Pictures/RunZone":"Couldn't save the image",Toast.LENGTH_LONG).show());
            });});
        });
        dialog.show();
    }
    private void confirmDelete(){
        java.io.File file=detailFile;if(file==null)return;
        new AlertDialog.Builder(this).setTitle("Delete this run?").setMessage("It will be removed from this phone and won't sync. Copies already uploaded to Smashrun, Strava or Runalyze stay there. This can't be undone.")
            .setPositiveButton("Delete",(d,w)->{CloudSync.forget(this,file.getName());if(file.delete()||!file.exists()){detailFile=null;detailRun=null;Toast.makeText(this,"Run deleted",Toast.LENGTH_SHORT).show();show("History");}else Toast.makeText(this,"Couldn't delete the run",Toast.LENGTH_LONG).show();})
            .setNegativeButton("Cancel",null).show();
    }
    private void autoPausePace(){
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Minutes:seconds per km");input.setText(paceLabel(getSharedPreferences("settings",0).getInt("pausePace",720)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Auto-pause pace").setMessage("Pause when slower than this pace for 10 seconds. Example: 12:00 min/km. Auto-pause is optional and does not react to lost GPS.").setView(input).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{String[] parts=input.getText().toString().trim().split(":");if(parts.length!=2)throw new IllegalArgumentException();int min=Integer.parseInt(parts[0]),sec=Integer.parseInt(parts[1]),total=min*60+sec;if(sec<0||sec>59||total<180||total>1800)throw new IllegalArgumentException();getSharedPreferences("settings",0).edit().putInt("pausePace",total).apply();dialog.dismiss();show("Settings");}catch(IllegalArgumentException e){input.setError("Use 3:00 to 30:00 min/km");}}));dialog.show();
    }
    private void prepareExport(){
        RunRecord run=detailRun;String id=detailFile.getName().replace(".json","");Toast.makeText(this,"Preparing FIT export…",Toast.LENGTH_SHORT).show();
        background.execute(()->{try{java.io.File dir=new java.io.File(getCacheDir(),"exports");if(!dir.exists()&&!dir.mkdirs())throw new java.io.IOException();java.io.File file=new java.io.File(dir,"runzone-"+id+".fit");try(java.io.OutputStream output=new java.io.FileOutputStream(file)){FitExporter.write(run,output);}runOnUiThread(()->{if(isDestroyed())return;pendingExport=file;new AlertDialog.Builder(this).setTitle("Export your run").setMessage("FIT includes recorded GPS, timing, distance, heart rate and beat intervals when available. Import this file on Smashrun, Runalyze or Strava. Runs without GPS may not import on Smashrun.").setPositiveButton("Save file",(d,w)->{Intent create=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(FitExporter.MIME_TYPE).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,file.getName());startActivityForResult(create,80);}).setNeutralButton("Share file",(d,w)->{Uri uri=new Uri.Builder().scheme("content").authority(getPackageName()+".exports").appendPath(file.getName()).build();Intent share=new Intent(Intent.ACTION_SEND).setType(FitExporter.MIME_TYPE).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(ClipData.newRawUri("Run recording",uri));startActivity(Intent.createChooser(share,"Share FIT recording"));}).setNegativeButton("Cancel",null).show();});}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Couldn't prepare the export. Please try again.",Toast.LENGTH_LONG).show());}});
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if((request==81||request==82)&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();if(request==81)writeBackup(uri);else new AlertDialog.Builder(this).setTitle("Restore from backup").setMessage("Missing runs and HRV checks will be added. Runs already on this phone are kept as they are.\n\nAlso restore zones, alerts and other training settings from the backup?").setPositiveButton("Runs and settings",(d,w)->restoreBackup(uri,true)).setNeutralButton("Runs only",(d,w)->restoreBackup(uri,false)).setNegativeButton("Cancel",null).show();return;}if(request==80&&result==RESULT_OK&&data!=null&&data.getData()!=null&&pendingExport!=null){Uri destination=data.getData();java.io.File file=pendingExport;background.execute(()->{try(java.io.InputStream input=new java.io.FileInputStream(file);java.io.OutputStream output=getContentResolver().openOutputStream(destination)){if(output==null)throw new java.io.IOException();byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);runOnUiThread(()->Toast.makeText(this,"FIT file saved",Toast.LENGTH_SHORT).show());}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Couldn't save the export. Try another location.",Toast.LENGTH_LONG).show());}});}}
    private void writeBackup(Uri uri){
        Toast.makeText(this,"Backing up…",Toast.LENGTH_SHORT).show();
        background.execute(()->{try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new java.io.IOException();int runs=Backup.write(this,out);runOnUiThread(()->Toast.makeText(this,"Backed up "+runs+(runs==1?" run":" runs"),Toast.LENGTH_LONG).show());}
            catch(Exception e){android.util.Log.w("RunZone","Backup failed",e);runOnUiThread(()->Toast.makeText(this,"Couldn't write the backup. Try another location.",Toast.LENGTH_LONG).show());}});
    }
    private void restoreBackup(Uri uri,boolean settings){
        Toast.makeText(this,"Restoring…",Toast.LENGTH_SHORT).show();
        background.execute(()->{try(java.io.InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new java.io.IOException();Backup.Result r=Backup.restore(this,in,settings);
                runOnUiThread(()->{new AlertDialog.Builder(this).setTitle("Backup restored").setMessage(r.runs+(r.runs==1?" run":" runs")+" added"+(r.skipped>0?", "+r.skipped+" already here or unreadable":"")+".\n"+r.checks+" HRV checks added."+(r.settings?"\nSettings restored.":"")).setPositiveButton("OK",null).show();show(screen);});}
            catch(Exception e){android.util.Log.w("RunZone","Restore failed",e);runOnUiThread(()->Toast.makeText(this,"That file isn't a RunZone backup, or it couldn't be read.",Toast.LENGTH_LONG).show());}});
    }
    private void accountOptions(String provider){
        if(CloudAccounts.connected(this,provider)){ArrayList<String> options=new ArrayList<>();if(CloudAccounts.needsReconnect(this,provider))options.add("Reconnect account");if("Detail".equals(screen))options.add("Sync this run");options.add(CloudAccounts.enabled(this,provider)?"Turn automatic sync off":"Turn automatic sync on");options.add("Disconnect account");
            new AlertDialog.Builder(this).setTitle(CloudAccounts.name(provider)).setItems(options.toArray(new String[0]),(d,index)->{String option=options.get(index);if(option.equals("Reconnect account")){connectAccount(provider);}else if(option.equals("Sync this run")){CloudSync.enqueue(this,detailFile,provider);Toast.makeText(this,"Run queued for sync",Toast.LENGTH_SHORT).show();render();}else if(option.equals("Disconnect account")){new AlertDialog.Builder(this).setTitle("Disconnect "+CloudAccounts.name(provider)+"?").setMessage("Stops future uploads from this phone. Runs already uploaded remain in your account.").setPositiveButton("Disconnect",(a,w)->{CloudAccounts.disconnect(this,provider);show(screen);}).setNegativeButton("Cancel",null).show();}else{getSharedPreferences("sync",0).edit().putBoolean("enabled_"+provider,!CloudAccounts.enabled(this,provider)).apply();CloudSync.schedule(this);show(screen);}}).setNegativeButton("Cancel",null).show();
        }else connectAccount(provider);
    }
    private void connectAccount(String provider){
            if(!CloudAccounts.configured(provider)&&!"smashrun".equals(provider)){new AlertDialog.Builder(this).setTitle("Account connections need setup").setMessage("RunZone’s shared account service has not been configured in this build yet. Once it is live, every user can sign in with their own account here. FIT file export is available now.").setPositiveButton("OK",null).show();return;}
            if(CloudAccounts.setupPending(this,provider)){CloudAccounts.refreshReadiness(this);new AlertDialog.Builder(this).setTitle(CloudAccounts.name(provider)+" connection not available yet").setMessage("This connection is still being set up for RunZone. Try again later. FIT file export is available now.").setPositiveButton("OK",null).show();return;}
            new AlertDialog.Builder(this).setTitle("Connect "+CloudAccounts.name(provider)+"?").setMessage("You'll sign in with "+CloudAccounts.name(provider)+". New saved runs will sync automatically; older runs sync only when you choose. You can turn this off or disconnect in Settings."+("smashrun".equals(provider)?" When Smashrun access expires, sign in again to continue syncing.":"")).setPositiveButton("Continue",(d,w)->background.execute(()->{try{Uri authorize=CloudAccounts.begin(this,provider);runOnUiThread(()->startActivity(new Intent(Intent.ACTION_VIEW,authorize)));}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"Could not start sign-in. The service may not be configured yet.",Toast.LENGTH_LONG).show());}})).setNegativeButton("Cancel",null).show();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);if(intent.getBooleanExtra("checkUpdate",false)){intent.removeExtra("checkUpdate");AppUpdates.check(this,true);}setIntent(intent);handleAccountCallback(intent);}
    private void handleAccountCallback(Intent intent){
        Uri uri=intent.getData();
        if(uri==null||!"runzone".equals(uri.getScheme())||!"accounts".equals(uri.getHost()))return;
        setIntent(new Intent(this,MainActivity.class));
        background.execute(()->{
            try{
                String provider=CloudAccounts.complete(this,uri);
                runOnUiThread(()->{
                    show("Settings");
                    Toast.makeText(this,CloudAccounts.name(provider)+" connected. New runs will sync automatically.",Toast.LENGTH_LONG).show();
                });
            }catch(Exception e){
                String message="Sign-in was cancelled, expired or did not grant upload permission. Please try again.";
                if(e instanceof SmashrunAuth.Failure)message=e.getMessage();
                else if("smashrun".equals(uri.getLastPathSegment()))
                    message="RunZone couldn’t complete Smashrun sign-in. Start again from Settings.";
                String failureMessage=message;
                runOnUiThread(()->Toast.makeText(this,failureMessage,Toast.LENGTH_LONG).show());
            }
        });
    }
    private boolean hasLocation(){return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private boolean bluetoothAllowed(){return Build.VERSION.SDK_INT<31?hasLocation():checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private boolean permissions(boolean location,boolean bluetooth,Runnable continuation){
        return requestMissingPermissions(missingPermissions(location,bluetooth),continuation);
    }
    private ArrayList<String> missingPermissions(boolean location,boolean bluetooth){
        ArrayList<String> missing=new ArrayList<>();
        if((location||(bluetooth&&Build.VERSION.SDK_INT<31))&&!hasLocation()){
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if(bluetooth&&Build.VERSION.SDK_INT>=31){
            addIfMissing(missing,Manifest.permission.BLUETOOTH_SCAN);
            addIfMissing(missing,Manifest.permission.BLUETOOTH_CONNECT);
        }
        return missing;
    }
    private void addIfMissing(ArrayList<String> missing,String permission){
        if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)missing.add(permission);
    }
    private boolean requestMissingPermissions(ArrayList<String> missing,Runnable continuation){
        if(missing.isEmpty())return true;
        if(!permissionRequestPending){
            permissionRequestPending=true;
            afterPermission=continuation;
            requestPermissions(missing.toArray(new String[0]),1);
        }
        return false;
    }
    private void requestOpeningPermissions(){
        if(checkedOpeningPermissions||permissionRequestPending)return;
        checkedOpeningPermissions=true;
        ArrayList<String> missing=missingPermissions(true,getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE));
        if(Build.VERSION.SDK_INT>=33)addIfMissing(missing,Manifest.permission.POST_NOTIFICATIONS);
        requestMissingPermissions(missing,null);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==3){if(!isFinishing()&&results.length>0)begin();return;}
        if(code!=1)return;
        permissionRequestPending=false;
        Runnable next=afterPermission;
        afterPermission=null;
        startPreview();
        if(next!=null){
            boolean granted=results.length>0;
            for(int i=0;i<results.length;i++){
                if(Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])
                        ||Manifest.permission.ACCESS_COARSE_LOCATION.equals(permissions[i]))granted&=hasLocation();
                else granted&=results[i]==PackageManager.PERMISSION_GRANTED;
            }
            if(granted)next.run();
            else if(results.length>0)Toast.makeText(this,"Permission needed. You can change it in Settings → App permissions.",Toast.LENGTH_LONG).show();
        }
        else if(selected!=null&&RunService.current==null&&bluetoothAllowed())monitor(selected);
    }
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
    @Override protected void onResume(){super.onResume();handler.post(refresh);startPreview();CloudAccounts.refreshReadiness(this);requestOpeningPermissions();}
    @Override protected void onPause(){super.onPause();handler.removeCallbacks(refresh);stopScan();stopPreview();if(RunService.current==null||!RunService.current.running)RunHaptics.cancel(this);}
    @Override protected void onStop(){super.onStop();if(!permissionRequestPending&&!isChangingConfigurations())checkedOpeningPermissions=false;}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if("Detail".equals(screen)&&detailFile!=null)state.putString("detail",detailFile.getName());if(pendingExport!=null)state.putString("export",pendingExport.getName());state.putBoolean("checkedOpeningPermissions",checkedOpeningPermissions);state.putBoolean("permissionRequestPending",permissionRequestPending);}
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
    private String distanceTargetLabel(){
        RunService service=RunService.current;boolean running=service!=null&&service.running;
        int meters=running?service.distanceGoal.targetMeters():getSharedPreferences("settings",0).getInt("distanceTargetMeters",0);
        if(meters<=0)return running?"No distance target · keep running":"Set distance target  ›";
        String goal=String.format(Locale.getDefault(),"%.3f km",meters/1000.0);
        if(running)return service.distanceGoal.reached()?goal+" reached · still recording":String.format(Locale.getDefault(),"Target %s · %.0f m to go",goal,Math.ceil(Math.max(0,meters-service.meters)));
        return "Target "+goal+" · tap to change  ›";
    }
    private void distanceTarget(){
        RunService service=RunService.current;
        if(service!=null&&service.running){new AlertDialog.Builder(this).setTitle("Distance target").setMessage(distanceTargetLabel()+"\n\nYour target stays fixed for this run. Reaching it plays a sound and three long vibrations; recording continues until you finish.").setPositiveButton("OK",null).show();return;}
        LinearLayout box=column();box.setPadding(dp(24),dp(8),dp(24),dp(8));
        box.addView(text("Get a sound and three long vibrations when you reach your distance. Your run keeps recording. This target is reused until you change or clear it.",14,MUTED));gap(box,16);
        box.addView(text("Distance in kilometres",14,TEXT));EditText input=new EditText(this);input.setTextColor(TEXT);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);input.setSingleLine(true);input.setHint("e.g. 5.00");input.setContentDescription("Target distance in kilometres");
        int stored=getSharedPreferences("settings",0).getInt("distanceTargetMeters",0);if(stored>0)input.setText(String.format(Locale.ROOT,"%.3f",stored/1000.0));box.addView(input);
        LinearLayout presets=row();for(String km:new String[]{"5","10","21.1"})weighted(presets,pill(km+" km",CARD,LIME,v->{input.setText(km);input.setSelection(input.length());}));box.addView(presets);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Set distance target").setView(box).setPositiveButton("Set target",null).setNeutralButton("No target",(d,w)->{getSharedPreferences("settings",0).edit().remove("distanceTargetMeters").apply();show(screen);}).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{
            double km=Double.parseDouble(input.getText().toString().trim().replace(',','.'));
            if(!Double.isFinite(km)||km<0.01||km>1000)throw new IllegalArgumentException();
            int meters=(int)Math.round(km*1000);getSharedPreferences("settings",0).edit().putInt("distanceTargetMeters",meters).apply();dialog.dismiss();show(screen);
        }catch(IllegalArgumentException e){input.setError("Enter 0.01–1000 km, or choose No target");}}));dialog.show();
    }
    private void testHaptics(){
        if(RunService.current!=null&&RunService.current.running){Toast.makeText(this,"Finish your run before testing vibrations",Toast.LENGTH_LONG).show();return;}
        new AlertDialog.Builder(this).setTitle("Test strong vibrations").setItems(new String[]{"Below zone · three half-second pulses","Above zone · three longer pulses","Distance reached · three one-second pulses"},(d,which)->{
            if(!RunHaptics.play(this,which==0?RunHaptics.BELOW:which==1?RunHaptics.ABOVE:RunHaptics.DISTANCE))Toast.makeText(this,"This device has no vibration motor",Toast.LENGTH_LONG).show();
        }).setNegativeButton("Cancel",null).show();
    }
    private boolean backgroundRestricted(){return Build.VERSION.SDK_INT>=28&&getSystemService(ActivityManager.class).isBackgroundRestricted();}
    private boolean backgroundReady(){PowerManager power=getSystemService(PowerManager.class);return !backgroundRestricted()&&!power.isPowerSaveMode()&&power.isIgnoringBatteryOptimizations(getPackageName())&&getSystemService(NotificationManager.class).areNotificationsEnabled();}
    private String backgroundSummary(){
        PowerManager power=getSystemService(PowerManager.class);
        return (backgroundRestricted()?"Background usage restricted":"No Android background restriction detected")+"\n"+
            (power.isIgnoringBatteryOptimizations(getPackageName())?"Battery optimisation off for RunZone":"Battery optimisation on for RunZone")+"\n"+
            (power.isPowerSaveMode()?"Phone Battery Saver on":"Phone Battery Saver off")+"\n"+
            (getSystemService(NotificationManager.class).areNotificationsEnabled()?"Recording notifications enabled":"Recording notifications disabled");
    }
    private void backgroundSettings(){
        new AlertDialog.Builder(this).setTitle("Background recording settings").setMessage(backgroundSummary()+"\n\nReturn here after changing settings to check again.")
            .setPositiveButton("App settings",(d,w)->openSystemSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))))
            .setNeutralButton("Battery settings",(d,w)->openSystemSettings(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))
            .setNegativeButton("Close",null).show();
    }
    private void openSystemSettings(Intent intent){try{startActivity(intent);}catch(ActivityNotFoundException e){Toast.makeText(this,"Open your phone’s Settings → Apps → RunZone → Battery",Toast.LENGTH_LONG).show();}}
    private int[] zoneEnds(){
        android.content.SharedPreferences prefs=getSharedPreferences("settings",0);int[] ends=ZoneRanges.defaults();
        for(int i=0;i<4;i++)ends[i]=prefs.getInt("zoneEnd"+i,ends[i]);return ZoneRanges.valid(ends)?ends:ZoneRanges.defaults();
    }
    private void saveZones(int[] ends,int selectedZone){
        android.content.SharedPreferences.Editor edit=getSharedPreferences("settings",0).edit().putBoolean("sharedZones",true).putInt("profile",selectedZone);
        for(int i=0;i<4;i++)edit.putInt("zoneEnd"+i,ends[i]);
        for(int i=0;i<5;i++)edit.putInt("low"+i,ZoneRanges.low(ends,i)).putInt("high"+i,ZoneRanges.high(ends,i));
        edit.putInt("low",ZoneRanges.low(ends,selectedZone)).putInt("high",ZoneRanges.high(ends,selectedZone)).apply();
    }
    private void ensureZones(){
        android.content.SharedPreferences prefs=getSharedPreferences("settings",0);
        if(!prefs.getBoolean("sharedZones",false)){
            int selectedZone=prefs.contains("profile")?Math.max(0,Math.min(4,prefs.getInt("profile",2))):2;
            int low=prefs.getInt("low",120),high=prefs.getInt("high",150);
            int[] ends=ZoneRanges.migrate(selectedZone,low,high);
            if(ZoneRanges.low(ends,selectedZone)!=low||ZoneRanges.high(ends,selectedZone)!=high){
                for(int i=0;i<5;i++){int[] candidate=ZoneRanges.migrate(i,low,high);if(ZoneRanges.low(candidate,i)==low&&ZoneRanges.high(candidate,i)==high){selectedZone=i;ends=candidate;break;}}
            }
            boolean existing=prefs.contains("profile");saveZones(ends,selectedZone);
            if(existing)handler.post(()->new AlertDialog.Builder(this).setTitle("Your zones now join up").setMessage("Zones now use shared boundaries with no gaps or overlaps. Your previous BPM target was kept where possible; its zone number may have changed. Review all five ranges before your next run.").setPositiveButton("Review zones",(d,w)->zones()).setNegativeButton("Later",null).show());
        }
    }
    private void zones(){
        if(RunService.current!=null&&RunService.current.running){new AlertDialog.Builder(this).setTitle("Target locked during your run").setMessage("Finish or discard this run before changing zones. Your current target stays active while paused.").setPositiveButton("OK",null).show();return;}
        LinearLayout box=column();box.setPadding(dp(24),dp(12),dp(24),dp(12));
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        RadioGroup targets=new RadioGroup(this);TextView preview=text("",14,MUTED);
        int selectedZone=getSharedPreferences("settings",0).getInt("profile",2);int[] ends=zoneEnds();
        box.addView(text("Target for your next run",16,TEXT));
        for(int i=0;i<5;i++){RadioButton button=new RadioButton(this);button.setId(View.generateViewId());button.setTextColor(TEXT);targets.addView(button);if(i==selectedZone)targets.check(button.getId());}
        box.addView(targets);gap(box,12);box.addView(text("Zone boundaries · BPM",16,TEXT));
        box.addView(text("Edit the top of Zones 1–4. The next zone starts one BPM higher, so every BPM from 30–240 belongs to exactly one zone.",13,MUTED));
        EditText[] fields=new EditText[4];
        for(int i=0;i<4;i++){gap(box,8);box.addView(text("Zone "+(i+1)+" ends at",13,TEXT));fields[i]=new EditText(this);fields[i].setTextColor(TEXT);fields[i].setInputType(2);fields[i].setSingleLine(true);fields[i].setText(Integer.toString(ends[i]));fields[i].setContentDescription("Zone "+(i+1)+" upper BPM boundary");box.addView(fields[i]);}
        gap(box,12);box.addView(preview);
        gap(box,16);box.addView(text("Suggested from your runs",16,TEXT));TextView suggestion=text("Looking at your recent runs…",13,MUTED);suggestion.setLineSpacing(dp(3),1);box.addView(suggestion);
        TextView useSuggestion=pill("Use suggested boundaries",0xff2a3835,LIME,null);useSuggestion.setVisibility(View.GONE);gap(box,8);box.addView(useSuggestion);
        final java.io.File runsDir=getFilesDir();
        background.execute(()->{Training t=computeTraining(runsDir);int[] suggested=TrainingLoad.suggestZones(t.maxBpm,t.aerobic);handler.post(()->{
            cachedObservedMax=t.observedMax;cachedResting=t.resting;
            if(suggested==null){suggestion.setText("Record a few hard runs with heart rate and RunZone will suggest zones.");return;}
            String basis=t.aerobic>0?"Your aerobic threshold looks like "+t.aerobic+" bpm (where DFA α1 crosses 0.75 on steady runs), so Zone 2 tops out there.":"Based on 60/70/80/90% of your maximum heart rate. Run steady with a chest strap to find your aerobic threshold too.";
            suggestion.setText("Maximum "+t.maxBpm+" bpm"+(getSharedPreferences("settings",0).getInt("maxBpm",0)>0?" (set by you)":t.observedMax>0?" (from your runs)":" (estimate)")+". "+basis+"\nZones 1–4 end at "+suggested[0]+", "+suggested[1]+", "+suggested[2]+" and "+suggested[3]+" bpm.");
            useSuggestion.setVisibility(View.VISIBLE);useSuggestion.setOnClickListener(v->{for(int i=0;i<4;i++)fields[i].setText(Integer.toString(suggested[i]));});
        });});
        AlertDialog[] zoneDialog=new AlertDialog[1];
        Runnable update=()->{
            int[] draft=new int[4];try{for(int i=0;i<4;i++)draft[i]=Integer.parseInt(fields[i].getText().toString());if(!ZoneRanges.valid(draft))throw new IllegalArgumentException();
                for(int i=0;i<5;i++){RadioButton button=(RadioButton)targets.getChildAt(i);boolean active=button.isChecked();button.setText("Zone "+(i+1)+" · "+ZoneRanges.low(draft,i)+"–"+ZoneRanges.high(draft,i)+" bpm"+(active?" · TARGET":""));}
                if(zoneDialog[0]!=null&&zoneDialog[0].isShowing())zoneDialog[0].getButton(-1).setEnabled(true);
                preview.setText("All five ranges meet with no gaps or overlaps.");preview.setTextColor(LIME);
            }catch(IllegalArgumentException e){if(zoneDialog[0]!=null&&zoneDialog[0].isShowing())zoneDialog[0].getButton(-1).setEnabled(false);preview.setText("Enter four increasing boundaries from 30–239. Each zone needs at least one BPM. Save is unavailable until these are valid.");preview.setTextColor(0xffffbc8a);}
        };
        targets.setOnCheckedChangeListener((group,id)->update.run());
        for(EditText field:fields)field.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){update.run();}public void afterTextChanged(android.text.Editable e){}});
        update.run();
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Heart-rate zones").setView(scroll).setPositiveButton("Save & activate",null).setNegativeButton("Cancel",null).create();
        zoneDialog[0]=dialog;
        dialog.setOnShowListener(d->{update.run();dialog.getButton(-1).setOnClickListener(v->{
            int[] draft=new int[4];try{for(int i=0;i<4;i++)draft[i]=Integer.parseInt(fields[i].getText().toString());if(!ZoneRanges.valid(draft))throw new IllegalArgumentException();
                int active=0;for(int i=0;i<5;i++)if(((RadioButton)targets.getChildAt(i)).isChecked())active=i;
                saveZones(draft,active);dialog.dismiss();show(screen);
            }catch(IllegalArgumentException e){update.run();Toast.makeText(this,"Check the zone boundaries before saving",Toast.LENGTH_SHORT).show();}
        });});dialog.show();
    }
    private class ZoneTrack extends View {
        private int bpm=-1;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        ZoneTrack(){super(MainActivity.this);setContentDescription("Heart-rate target range");}
        void update(int b,int l,int h){bpm=b;setContentDescription("Target "+l+" to "+h+" BPM"+(b>0?", current "+b+" BPM":""));invalidate();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float w=getWidth(),h=getHeight();int[] ends=zoneEnds();
            int active=getSharedPreferences("settings",0).getInt("profile",2);
            for(int i=0;i<5;i++){
                float left=(ZoneRanges.low(ends,i)-ZoneRanges.MIN)/(float)(ZoneRanges.MAX-ZoneRanges.MIN+1)*w;
                float right=(ZoneRanges.high(ends,i)-ZoneRanges.MIN+1)/(float)(ZoneRanges.MAX-ZoneRanges.MIN+1)*w;
                paint.setColor(i==active?LIME:LINE);c.drawRoundRect(left,h*.25f,Math.max(left,right-dp(2)),h*.75f,dp(3),dp(3),paint);
            }
            if(bpm>0){float position=(bpm-ZoneRanges.MIN)/(float)(ZoneRanges.MAX-ZoneRanges.MIN);paint.setColor(TEXT);c.drawCircle(Math.max(h/2,Math.min(w-h/2,w*position)),h/2,h/2,paint);}
        }
    }
}
