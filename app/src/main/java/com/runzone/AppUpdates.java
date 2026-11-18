package com.runzone;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.widget.Toast;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Only published stable releases and APKs from this repository are offered. */
final class AppUpdates {
    private static final String CHANNEL = "releases";
    private static final int JOB = 421, NOTICE = 422;
    private static final String PREFIX = "https://github.com/K33k0/Run-tracker/releases/download/";
    static final class Release {
        final String tag, url;
        Release(String tag, String url) { this.tag = tag; this.url = url; }
    }
    static void schedule(Context context) {
        context.getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "New releases", NotificationManager.IMPORTANCE_DEFAULT));
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(JOB) == null) scheduler.schedule(new JobInfo.Builder(JOB,
            new ComponentName(context, UpdateJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true).setPeriodic(24 * 60 * 60 * 1000L).build());
    }
    static Release latest() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("https://api.github.com/repos/K33k0/Run-tracker/releases/latest").openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "RunZone/" + BuildConfig.VERSION_NAME);
        try {
            if (connection.getResponseCode() == 404) return null;
            if (connection.getResponseCode() != 200) throw new IOException("Release check failed");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 2_000_000) throw new IOException("Release response too large");
                    bytes.write(buffer, 0, count);
                }
            }
            JSONObject json = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            String tag = json.getString("tag_name");
            if (json.optBoolean("draft") || json.optBoolean("prerelease") || !ReleaseVersion.newer(tag, BuildConfig.VERSION_NAME)) return null;
            JSONArray assets = json.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = "RunZone-" + tag + ".apk", url = asset.optString("browser_download_url");
                if (name.equals(asset.optString("name")) && url.equals(PREFIX + tag + "/" + name)) return new Release(tag, url);
            }
            throw new IOException("Release APK unavailable");
        } finally { connection.disconnect(); }
    }
    static void notifyRelease(Context context, Release release) {
        if (release == null) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences("updates", 0);
        if (release.tag.equals(prefs.getString("notified", ""))) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) return;
        Intent intent = new Intent(context, MainActivity.class).putExtra("checkUpdate", true);
        PendingIntent open = PendingIntent.getActivity(context, NOTICE, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            manager.notify(NOTICE, new Notification.Builder(context, CHANNEL).setSmallIcon(com.runzone.R.drawable.ic_launcher)
                .setContentTitle("RunZone " + release.tag + " available").setContentText("Tap to download and install the new release.")
                .setContentIntent(open).setAutoCancel(true).build());
            prefs.edit().putString("notified", release.tag).apply();
        } catch (SecurityException ignored) { /* Notification permission can change during the check. */ }
    }
    static void check(Activity activity, boolean manual) {
        if (manual) Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Release release = latest();
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (!manual) { notifyRelease(activity, release); return; }
                    if (release == null) { Toast.makeText(activity, "You're using the latest release", Toast.LENGTH_LONG).show(); return; }
                    new AlertDialog.Builder(activity).setTitle("RunZone " + release.tag + " available")
                        .setMessage("Download the APK in your browser, then open the downloaded file to install. Android may ask you to allow installs from your browser. Your runs and settings are kept when updating a production release.")
                        .setPositiveButton("Download & install", (d, w) -> {
                            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(release.url))); }
                            catch (ActivityNotFoundException e) { Toast.makeText(activity, "Install a browser to download the update", Toast.LENGTH_LONG).show(); }
                        }).setNegativeButton("Later", null).show();
                });
            } catch (Exception e) {
                if (manual) activity.runOnUiThread(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) Toast.makeText(activity, "Couldn't check for updates. Check your connection and try again.", Toast.LENGTH_LONG).show(); });
            }
        }, "runzone-updates").start();
    }
}
