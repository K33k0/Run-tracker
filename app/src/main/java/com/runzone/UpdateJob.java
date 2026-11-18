package com.runzone;

import android.app.job.*;
import android.os.*;

public class UpdateJob extends JobService {
    private final java.util.concurrent.ConcurrentHashMap<JobParameters, Boolean> active = new java.util.concurrent.ConcurrentHashMap<>();
    @Override public boolean onStartJob(JobParameters params) {
        active.put(params, true);
        new Thread(() -> {
            boolean retry = false;
            try {
                AppUpdates.Release release = AppUpdates.latest();
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (active.remove(params) != null) { AppUpdates.notifyRelease(this, release); jobFinished(params, false); }
                });
                return;
            } catch (Exception e) { retry = true; }
            final boolean reschedule = retry;
            new Handler(Looper.getMainLooper()).post(() -> { if (active.remove(params) != null) jobFinished(params, reschedule); });
        }, "runzone-release-check").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { active.remove(params); return true; }
}
