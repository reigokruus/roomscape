package com.hackathon.setscan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the app alive while a finished scan is processed (depth AI etc.), so switching to another app doesn't
 * freeze or kill the work. Shows one quiet notification; stops itself when processing ends.
 */
public class ProcessingService extends Service {
    private static final String CH = "processing";
    private static boolean running;

    static void start(Context c) {
        if (running) return;
        try {
            Intent i = new Intent(c, ProcessingService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            running = true;
        } catch (Throwable ignored) { } // e.g. not allowed right now: processing still runs while the app is open
    }

    static void stop(Context c) {
        if (!running) return;
        running = false;
        try { c.stopService(new Intent(c, ProcessingService.class)); } catch (Throwable ignored) { }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CH, "Scan processing", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CH);
        } else b = new Notification.Builder(this);
        Notification n = b.setContentTitle("CineX is improving your scan")
                .setContentText("You can use other apps. Come back when it's done.")
                .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(1, n);
        } catch (Throwable e) { running = false; stopSelf(); }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { running = false; super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
