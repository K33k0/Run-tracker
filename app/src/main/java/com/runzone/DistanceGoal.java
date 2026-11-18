package com.runzone;

/** A per-run distance milestone, independent of recording and pause state. */
public final class DistanceGoal {
    private int targetMeters;
    private boolean reached;
    public void reset(int target){targetMeters=target>0&&target<=1_000_000?target:0;reached=false;}
    public int targetMeters(){return targetMeters;}
    public boolean reached(){return reached;}
    public boolean accept(double meters){
        if(targetMeters==0||reached||!Double.isFinite(meters)||meters<targetMeters)return false;
        reached=true;return true;
    }
}
