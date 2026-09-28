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
 * Foreground service that keeps the app's camera and microphone alive when the screen is locked
 * or the app is in the background. It also holds a partial wake lock so the CPU (and the
 * WebView's JavaScript) keeps running.
 */
public class KeepAliveService extends Service {

  private static final String CHANNEL_ID = "vision_assistant_live";
  private PowerManager.WakeLock wakeLock;

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel ch =
          new NotificationChannel(
              CHANNEL_ID, "Vision Assistant running", NotificationManager.IMPORTANCE_LOW);
      nm.createNotificationChannel(ch);
    }

    Intent open = new Intent(this, MainActivity.class);
    PendingIntent pi =
        PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

    Notification.Builder b =
        Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
    Notification n =
        b.setContentTitle("Vision Assistant")
            .setContentText("Running - camera and microphone active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();

    if (Build.VERSION.SDK_INT >= 29) {
      int types =
          ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
              | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
      startForeground(1, n, types);
    } else {
      startForeground(1, n);
    }

    if (wakeLock == null) {
      PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VisionAssistant:live");
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
