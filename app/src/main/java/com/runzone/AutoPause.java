package com.runzone;

/** Hysteresis avoids rapid pause/resume switches around the chosen pace. */
public final class AutoPause {
    private long slowSince=-1,fastSince=-1;
    public void reset(){slowSince=-1;fastSince=-1;}
    public int accept(boolean enabled,boolean manualPause,boolean autoPaused,int secondsPerKm,double speed,long now){
        if(!enabled){reset();return autoPaused?1:0;}
        if(manualPause||secondsPerKm<=0||!Double.isFinite(speed)||speed<0){reset();return 0;}
        double threshold=1000d/secondsPerKm;
        if(autoPaused){slowSince=-1;if(speed>=threshold){if(fastSince<0)fastSince=now;if(now-fastSince>=3000){reset();return 1;}}else fastSince=-1;}
        else {fastSince=-1;if(speed<threshold){if(slowSince<0)slowSince=now;if(now-slowSince>=10000){reset();return -1;}}else slowSince=-1;}
        return 0;
    }
}
