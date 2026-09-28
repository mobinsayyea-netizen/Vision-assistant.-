package com.mobeen.visionassistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the Vision Assistant page (assets/index.html) in a WebView, grants it camera and
 * microphone access, and starts a foreground service so it keeps working with the screen locked.
 */
public class MainActivity extends Activity {

  private static final int REQ = 100;
  private WebView webView;
  private boolean serviceStarted = false;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);

    webView = new WebView(this);
    WebSettings s = webView.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true); // keeps the saved API key
    s.setAllowFileAccess(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    webView.setWebViewClient(new WebViewClient());
    webView.setWebChromeClient(
        new WebChromeClient() {
          @Override
          public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> request.grant(request.getResources()));
          }
        });
    setContentView(webView);
    webView.loadUrl("file:///android_asset/index.html");

    askPermissions();
  }

  private void askPermissions() {
    List<String> need = new ArrayList<>();
    String[] all = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    for (String p : all) {
      if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      need.add(Manifest.permission.POST_NOTIFICATIONS);
    }
    if (need.isEmpty()) {
      startKeepAlive();
    } else {
      requestPermissions(need.toArray(new String[0]), REQ);
    }
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
    super.onRequestPermissionsResult(code, perms, results);
    startKeepAlive();
    // Reload so the page can open the camera now that permission is granted.
    webView.reload();
  }

  private void startKeepAlive() {
    if (serviceStarted) return;
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
      return; // foreground service with camera/mic type needs these first
    }
    Intent i = new Intent(this, KeepAliveService.class);
    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
    else startService(i);
    serviceStarted = true;
  }

  @Override
  protected void onDestroy() {
    if (isFinishing()) stopService(new Intent(this, KeepAliveService.class));
    super.onDestroy();
  }
}
