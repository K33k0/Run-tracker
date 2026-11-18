package com.runzone;

/** Web Mercator coordinates, independent of Android for boundary testing. */
public final class MapProjection {
    private MapProjection() {}
    /** Closest street-level zoom that contains the route; spans use zoom-19 pixels. */
    public static int routeZoom(double spanX,double spanY,double width,double height) {
        int zoom=18;
        while(zoom>3&&(spanX/Math.pow(2,19-zoom)>Math.max(1,width)
                ||spanY/Math.pow(2,19-zoom)>Math.max(1,height)))zoom--;
        return zoom;
    }
    public static double x(double longitude,int zoom) { return (longitude+180)/360*(256L<<zoom); }
    public static double y(double latitude,int zoom) {
        double lat=Math.toRadians(Math.max(-85.05112878,Math.min(85.05112878,latitude)));
        return (1-Math.log(Math.tan(lat)+1/Math.cos(lat))/Math.PI)/2*(256L<<zoom);
    }
    public static double longitude(double x,int zoom) { return x/(256L<<zoom)*360-180; }
    public static double latitude(double y,int zoom) { return Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*y/(256L<<zoom))))); }
}
