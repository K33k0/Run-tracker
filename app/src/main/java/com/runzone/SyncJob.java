package com.runzone;
import android.app.job.*;
import android.os.*;

public class SyncJob extends JobService {
    private final java.util.concurrent.ConcurrentHashMap<JobParameters,java.util.concurrent.atomic.AtomicBoolean> tasks=new java.util.concurrent.ConcurrentHashMap<>();
    @Override public boolean onStartJob(JobParameters parameters){java.util.concurrent.atomic.AtomicBoolean stopped=new java.util.concurrent.atomic.AtomicBoolean();tasks.put(parameters,stopped);new Thread(()->{boolean retry=CloudSync.process(getApplicationContext(),stopped::get);new Handler(Looper.getMainLooper()).post(()->{tasks.remove(parameters);if(!stopped.get())jobFinished(parameters,retry);});},"runzone-sync").start();return true;}
    @Override public boolean onStopJob(JobParameters parameters){java.util.concurrent.atomic.AtomicBoolean stopped=tasks.remove(parameters);if(stopped!=null)stopped.set(true);return true;}
}
