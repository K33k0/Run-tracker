package com.runzone;

/** Four shared upper boundaries partition every supported BPM exactly once. */
public final class ZoneRanges {
    public static final int MIN=30, MAX=240;
    private ZoneRanges() {}
    public static int[] defaults(){return new int[]{99,119,150,170};}
    public static boolean valid(int[] ends){
        if(ends==null||ends.length!=4)return false;
        int previous=MIN-1;
        for(int end:ends){if(end<=previous||end>=MAX)return false;previous=end;}
        return true;
    }
    public static int low(int[] ends,int zone){return zone==0?MIN:ends[zone-1]+1;}
    public static int high(int[] ends,int zone){return zone==4?MAX:ends[zone];}
    /** Preserve the legacy active target when it leaves room for the other zones. */
    public static int[] migrate(int zone,int low,int high){
        int[] ends=defaults();
        if(zone<0||zone>4||low<MIN+zone||high>MAX-(4-zone)||low>high
                ||(zone==0&&low!=MIN)||(zone==4&&high!=MAX))return ends;
        if(zone>0)ends[zone-1]=low-1;
        if(zone<4)ends[zone]=high;
        for(int i=zone-2;i>=0;i--)ends[i]=Math.min(ends[i],ends[i+1]-1);
        for(int i=zone+1;i<4;i++)ends[i]=Math.max(ends[i],ends[i-1]+1);
        return valid(ends)?ends:defaults();
    }
}
