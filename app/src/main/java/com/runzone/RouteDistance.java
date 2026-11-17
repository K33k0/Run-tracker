package com.runzone;

/** Retains the distance anchor across sub-two-metre fixes, including slow runs. */
final class RouteDistance {
    private boolean anchored;
    private double latitude,longitude;
    private long time;
    void reset(){anchored=false;}
    double accept(double lat,double lon,long elapsedMs){
        if(!anchored){anchor(lat,lon,elapsedMs);return 0;}
        double distance=RunRecord.distance(latitude,longitude,lat,lon),seconds=(elapsedMs-time)/1000d;
        if(seconds<=0||seconds>=15||distance/seconds>=12){anchor(lat,lon,elapsedMs);return 0;}
        if(distance<2)return 0;
        anchor(lat,lon,elapsedMs);return distance;
    }
    private void anchor(double lat,double lon,long elapsedMs){anchored=true;latitude=lat;longitude=lon;time=elapsedMs;}
}
