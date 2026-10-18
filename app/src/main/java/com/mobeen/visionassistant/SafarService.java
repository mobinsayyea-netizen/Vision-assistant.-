package com.mobeen.visionassistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground service for Safar (journey) mode. It keeps GPS updates and the page's JavaScript
 * alive while the screen is off or the phone is in a pocket during a long bus or train ride.
 */
public class SafarService extends Service {

  private static final String CHANNEL_ID = "vision_assistant_safar";
  private PowerManager.WakeLock wakeLock;

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel ch =
          new NotificationChannel(CHANNEL_ID, "Safar mode running", NotificationManager.IMPORTANCE_LOW);
      nm.createNotificationChannel(ch);
    }

    Intent open = new Intent(this, MainActivity.class);
    PendingIntent pi =
        PendingIntent.getActivity(
            this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

    Notification.Builder b =
        Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
    Notification n =
        b.setContentTitle("Safar mode")
            .setContentText("Journey tracking is on")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();

    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
    } else {
      startForeground(2, n);
    }

    if (wakeLock == null) {
      PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VisionAssistant:safar");
      wakeLock.acquire();
    }
    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
