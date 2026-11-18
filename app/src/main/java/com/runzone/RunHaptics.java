package com.runzone;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Long, separated pulses at maximum supported amplitude for pocket/leg use. */
public final class RunHaptics {
    public static final int BELOW=-1, ABOVE=1, DISTANCE=2;
    private RunHaptics() {}
    public static boolean play(Context context,int kind){
        Vibrator vibrator=context.getSystemService(Vibrator.class);
        if(vibrator==null||!vibrator.hasVibrator())return false;
        long pulse=kind==DISTANCE?1000:kind==ABOVE?800:500;
        long[] timings={0,pulse,250,pulse,250,pulse};
        int strength=vibrator.hasAmplitudeControl()?255:VibrationEffect.DEFAULT_AMPLITUDE;
        vibrator.vibrate(VibrationEffect.createWaveform(timings,new int[]{0,strength,0,strength,0,strength},-1));
        return true;
    }
    public static void cancel(Context context){Vibrator vibrator=context.getSystemService(Vibrator.class);if(vibrator!=null)vibrator.cancel();}
}
