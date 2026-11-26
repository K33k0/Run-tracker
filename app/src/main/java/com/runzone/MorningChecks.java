package com.runzone;

import android.content.Context;
import org.json.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Morning resting HRV checks, stored next to the runs so backups include them. */
public final class MorningChecks {
    private MorningChecks(){}
    static File file(Context c){return new File(c.getFilesDir(),"hrv-checks.json");}
    public static List<TrainingLoad.Check> load(Context c){return parse(read(file(c)));}
    static JSONArray read(File file){
        try{return file.exists()?new JSONArray(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)):new JSONArray();}
        catch(Exception e){return new JSONArray();}
    }
    static List<TrainingLoad.Check> parse(JSONArray array){
        ArrayList<TrainingLoad.Check> checks=new ArrayList<>();
        for(int i=0;i<array.length();i++){
            JSONObject o=array.optJSONObject(i);if(o==null)continue;
            long time=o.optLong("time",0);double rmssd=o.optDouble("rmssd",Double.NaN);int bpm=o.optInt("restingBpm",-1);
            if(time>0&&Double.isFinite(rmssd)&&rmssd>0&&rmssd<400&&RunRecord.validBpm(bpm))checks.add(new TrainingLoad.Check(time,rmssd,bpm));
        }
        checks.sort(Comparator.comparingLong(x->x.time));
        return checks;
    }
    static JSONArray toJson(List<TrainingLoad.Check> checks)throws JSONException{
        JSONArray array=new JSONArray();
        for(TrainingLoad.Check c:checks)array.put(new JSONObject().put("time",c.time).put("rmssd",c.rmssd).put("restingBpm",c.restingBpm));
        return array;
    }
    /** Adds checks, ignoring any with the same timestamp as one already saved. Returns how many were new. */
    public static synchronized int add(Context c,List<TrainingLoad.Check> extra)throws Exception{
        List<TrainingLoad.Check> all=new ArrayList<>(load(c));HashSet<Long> seen=new HashSet<>();for(TrainingLoad.Check x:all)seen.add(x.time);
        int added=0;for(TrainingLoad.Check x:extra)if(seen.add(x.time)){all.add(x);added++;}
        all.sort(Comparator.comparingLong(x->x.time));
        RunService.writeAtomically(file(c),toJson(all).toString());
        return added;
    }
    /** Lowest resting heart rate from the last 30 days of checks, or -1. */
    public static int restingBpm(List<TrainingLoad.Check> checks,long now){
        int best=-1;for(TrainingLoad.Check c:checks)if(now-c.time<=30*TrainingLoad.DAY&&(best<0||c.restingBpm<best))best=c.restingBpm;
        return best;
    }
}
