package com.runzone;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/**
 * Backup zip: every saved run, morning HRV checks, and training settings. Account tokens and the
 * paired sensor are left out, so a backup is safe to keep in cloud storage.
 */
public final class Backup {
    public static final String MIME="application/zip";
    static final Pattern RUN_NAME=Pattern.compile("runs/[0-9]{1,19}\\.json");
    /** Settings worth carrying to a new phone. */
    static final Set<String> SETTINGS=new HashSet<>(Arrays.asList(
        "low","high","profile","sharedZones","zoneEnd0","zoneEnd1","zoneEnd2","zoneEnd3",
        "low0","low1","low2","low3","low4","high0","high1","high2","high3","high4",
        "zoneBeeps","kmAlerts","autoPause","pausePace","distanceTargetMeters","maxBpm","restingBpm"));
    static final long MAX_ENTRY=32L*1024*1024;

    public static final class Result {
        public final int runs,skipped,checks;public final boolean settings;
        Result(int runs,int skipped,int checks,boolean settings){this.runs=runs;this.skipped=skipped;this.checks=checks;this.settings=settings;}
    }
    private Backup(){}

    public static int write(Context c,OutputStream out)throws Exception{
        int count=0;
        try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(out))){
            File[] runs=new File(c.getFilesDir(),"runs").listFiles((d,name)->name.endsWith(".json"));
            if(runs!=null){Arrays.sort(runs);for(File f:runs){zip.putNextEntry(new ZipEntry("runs/"+f.getName()));zip.write(Files.readAllBytes(f.toPath()));zip.closeEntry();count++;}}
            File checks=MorningChecks.file(c);
            if(checks.exists()){zip.putNextEntry(new ZipEntry("hrv-checks.json"));zip.write(Files.readAllBytes(checks.toPath()));zip.closeEntry();}
            zip.putNextEntry(new ZipEntry("settings.json"));zip.write(settingsJson(c.getSharedPreferences("settings",0)).toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("backup.json"));zip.write(new JSONObject().put("format",1).put("created",System.currentTimeMillis()).put("runs",count).toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
        return count;
    }

    static JSONObject settingsJson(SharedPreferences prefs)throws JSONException{
        JSONObject json=new JSONObject();
        for(Map.Entry<String,?> e:prefs.getAll().entrySet())if(SETTINGS.contains(e.getKey())&&(e.getValue() instanceof Integer||e.getValue() instanceof Boolean))json.put(e.getKey(),e.getValue());
        return json;
    }

    /** Adds runs and checks that aren't already on this phone; never overwrites an existing run. */
    public static Result restore(Context c,InputStream in,boolean includeSettings)throws Exception{
        File dir=new File(c.getFilesDir(),"runs");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Can't create runs folder");
        int runs=0,skipped=0,checks=0;boolean settings=false;boolean recognised=false;
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(in))){
            for(ZipEntry e;(e=zip.getNextEntry())!=null;){
                if(e.isDirectory())continue;
                String name=e.getName();
                if(RUN_NAME.matcher(name).matches()){
                    recognised=true;String text=readEntry(zip);
                    try{RunRecord.fromJson(new JSONObject(text));}catch(JSONException bad){skipped++;continue;}
                    File target=new File(dir,name.substring(5));
                    if(target.exists()){skipped++;continue;}
                    RunService.writeAtomically(target,text);CloudSync.forget(c,target.getName());runs++;
                }else if(name.equals("hrv-checks.json")){
                    recognised=true;checks+=MorningChecks.add(c,MorningChecks.parse(new JSONArray(readEntry(zip))));
                }else if(name.equals("settings.json")){
                    recognised=true;JSONObject json=new JSONObject(readEntry(zip));
                    if(includeSettings){
                        SharedPreferences.Editor edit=c.getSharedPreferences("settings",0).edit();
                        for(Iterator<String> keys=json.keys();keys.hasNext();){String key=keys.next();if(!SETTINGS.contains(key))continue;Object v=json.get(key);
                            if(v instanceof Boolean)edit.putBoolean(key,(Boolean)v);else if(v instanceof Number)edit.putInt(key,((Number)v).intValue());}
                        edit.apply();settings=true;
                    }
                }else if(name.equals("backup.json"))recognised=true;
            }
        }
        if(!recognised)throw new IOException("Not a RunZone backup");
        return new Result(runs,skipped,checks,settings);
    }

    private static String readEntry(ZipInputStream zip)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];long total=0;
        for(int n;(n=zip.read(buffer))>0;){total+=n;if(total>MAX_ENTRY)throw new IOException("Backup entry too large");bytes.write(buffer,0,n);}
        return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
}
