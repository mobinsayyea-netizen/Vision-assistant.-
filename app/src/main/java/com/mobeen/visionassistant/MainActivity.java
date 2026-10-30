package com.mobeen.visionassistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Loads the Vision Assistant page (assets/index.html) in a WebView, grants it camera and
 * microphone access, and starts a foreground service so it keeps working with the screen locked.
 *
 * <p>Android's WebView does not implement the Web Speech Synthesis API, so Android's own
 * TextToSpeech engine is exposed to the pages as window.AndroidTTS.
 *
 * <p>Safar (journey) mode lives in assets/safar.html. It needs things a WebView cannot do alone,
 * so this class exposes them as window.AndroidSafar: GPS (works with no internet), voice
 * recognition (hi-IN, offline when the phone has the language pack), vibration, HTTP requests
 * (no CORS limits), and a foreground service for location.
 */
public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

  private static final int REQ = 100;
  private static final int REQ_LOC = 101;
  private static final String HOME = "file:///android_asset/index.html";

  private WebView webView;
  private boolean serviceStarted = false;
  private TextToSpeech tts;
  private boolean ttsReady = false;
  private String ttsEngine = ""; // "" = the phone's default engine
  private String lastStartedId = "";
  private int ttsFailures = 0;
  private int vaSeq = 0;
  private long ttsFailSince = 0;

  private LocationManager locationManager;
  private LocationListener locationListener;
  private boolean locRunning = false;
  private SpeechRecognizer recognizer;

  // Navigation-style audio: lets announcements mix with calls and music (ducking) where the
  // phone allows it, instead of fighting for the speaker.
  private static final AudioAttributes NAV_ATTRS =
      new AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build();
  private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {};
  private AudioManager audioManager;
  private AudioFocusRequest focusReq;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  // Sound cues (chime, guidance beeps, arrival tune) made in code, played natively so they keep
  // working with the screen off.
  private static final int SR = 22050;
  private final HashMap<String, short[]> cues = new HashMap<>();
  private final Object guideLock = new Object();
  private volatile String guideMode = "off";
  private Thread guideThread;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);

    SharedPreferences prefs = getSharedPreferences("va", MODE_PRIVATE);
    ttsEngine = prefs.getString("tts_engine", "");
    createTts();

    webView = new WebView(this);
    WebSettings s = webView.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true); // keeps the saved API key, trips and saved places
    s.setAllowFileAccess(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    webView.addJavascriptInterface(new TtsBridge(), "AndroidTTS");
    webView.addJavascriptInterface(new SafarBridge(), "AndroidSafar");
    webView.setWebViewClient(new WebViewClient());
    webView.setWebChromeClient(
        new WebChromeClient() {
          @Override
          public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> request.grant(request.getResources()));
          }
        });
    setContentView(webView);
    webView.loadUrl(HOME);

    askPermissions();
    mainHandler.postDelayed(this::checkForUpdate, 4000);
  }

  // ---------------------------------------------------------------- helpers

  /** Runs JavaScript in the page from any thread. */
  private void js(final String code) {
    runOnUiThread(
        () -> {
          if (webView != null) webView.evaluateJavascript(code, null);
        });
  }

  private boolean locGranted() {
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        == PackageManager.PERMISSION_GRANTED;
  }

  // ---------------------------------------------------------------- text to speech

  /** (Re)creates the speech engine. ttsEngine "" means the phone's default engine. */
  private void createTts() {
    ttsReady = false;
    try {
      if (tts != null) {
        try {
          tts.stop();
          tts.shutdown();
        } catch (Exception ignored) {
        }
      }
      tts = ttsEngine.isEmpty() ? new TextToSpeech(this, this) : new TextToSpeech(this, this, ttsEngine);
    } catch (Exception e) {
      tts = null;
    }
  }

  @Override
  public void onInit(int status) {
    if (status != TextToSpeech.SUCCESS) {
      // The chosen engine would not start (for example it was uninstalled): use the default one.
      if (!ttsEngine.isEmpty()) {
        ttsEngine = "";
        getSharedPreferences("va", MODE_PRIVATE).edit().putString("tts_engine", "").apply();
        mainHandler.post(this::createTts);
      }
      js("window.onTtsReady&&window.onTtsReady(false)");
      return;
    }
    ttsReady = true;
    setHindi();
    tts.setAudioAttributes(NAV_ATTRS);
    tts.setOnUtteranceProgressListener(
        new UtteranceProgressListener() {
          @Override
          public void onStart(String utteranceId) {
            lastStartedId = utteranceId;
            ttsFailures = 0;
          }

          @Override
          public void onDone(String utteranceId) {
            abandonFocus();
            js("window.onTtsDone&&window.onTtsDone(" + JSONObject.quote(utteranceId) + ")");
          }

          @Override
          public void onError(String utteranceId) {
            js("window.onTtsDone&&window.onTtsDone(" + JSONObject.quote(utteranceId) + ")");
            mainHandler.post(() -> recoverTts());
          }

          @Override
          public void onStop(String utteranceId, boolean interrupted) {
            js("window.onTtsDone&&window.onTtsDone(" + JSONObject.quote(utteranceId) + ")");
          }
        });
    js("window.onTtsReady&&window.onTtsReady(true)");
  }

  private int setHindi() {
    int r = tts.setLanguage(new Locale("hi", "IN"));
    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
      r = tts.setLanguage(new Locale("hi"));
    }
    return r;
  }

  /**
   * The speech engine sometimes stops answering until the phone is restarted. When a sentence
   * never starts (or fails), start the same engine again (it is never swapped for another one).
   * A double buzz tells the person it happened.
   */
  private void recoverTts() {
    long now = System.currentTimeMillis();
    if (now - ttsFailSince > 60000) ttsFailures = 0;
    ttsFailSince = now;
    ttsFailures++;
    doVibrate("0,300,120,300");
    // The chosen speech engine is never swapped for another one automatically:
    // the same engine is simply started again.
    createTts();
  }

  /**
   * lead: "" (nothing), "chime" (short musical chime) or "arrive" (arrival tune) is played before
   * the words so the person knows an announcement is coming.
   */
  private void speakText(
      final String text,
      final float rate,
      final boolean flush,
      final String id,
      final String lead) {
    final String uid = (id == null || id.isEmpty()) ? "va" : id;
    if (tts == null || !ttsReady || text == null || text.trim().isEmpty()) {
      js("window.onTtsDone&&window.onTtsDone(" + JSONObject.quote(uid) + ")");
      return;
    }
    runOnUiThread(
        () -> {
          if (flush && tts != null) tts.stop();
          if (lead != null && !lead.isEmpty()) {
            playAsync(lead);
            long wait = lead.equals("arrive") ? 1000 : 450;
            mainHandler.postDelayed(() -> doSpeak(text, rate, flush, uid), wait);
          } else {
            doSpeak(text, rate, flush, uid);
          }
        });
  }

  private void doSpeak(String text, float rate, boolean flush, String uid) {
    if (tts == null || !ttsReady) {
      js("window.onTtsDone&&window.onTtsDone(" + JSONObject.quote(uid) + ")");
      return;
    }
    requestFocus();
    // Devanagari text goes to the Hindi voice; text with no Devanagari at all reads in English.
    boolean hasDevanagari = text.chars().anyMatch(c -> c >= 0x0900 && c <= 0x097F);
    if (hasDevanagari) setHindi();
    else tts.setLanguage(new Locale("en", "IN"));
    tts.setSpeechRate(rate > 0 ? rate : 1f);
    int res = tts.speak(text, flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, uid);
    if (res != TextToSpeech.SUCCESS) {
      recoverTts();
      return;
    }
    // Watchdog: if this sentence has not started within 4 seconds the engine is stuck.
    mainHandler.postDelayed(
        () -> {
          if (!uid.equals(lastStartedId) && tts != null && !tts.isSpeaking()) recoverTts();
        },
        4000);
  }

  private void requestFocus() {
    try {
      if (audioManager == null) audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
      if (Build.VERSION.SDK_INT >= 26) {
        if (focusReq == null) {
          focusReq =
              new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                  .setAudioAttributes(NAV_ATTRS)
                  .setOnAudioFocusChangeListener(focusListener)
                  .build();
        }
        audioManager.requestAudioFocus(focusReq);
      } else {
        audioManager.requestAudioFocus(
            focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
      }
    } catch (Exception ignored) {
    }
  }

  private void abandonFocus() {
    try {
      if (audioManager == null) return;
      if (Build.VERSION.SDK_INT >= 26) {
        if (focusReq != null) audioManager.abandonAudioFocusRequest(focusReq);
      } else {
        audioManager.abandonAudioFocus(focusListener);
      }
    } catch (Exception ignored) {
    }
  }

  // ---------------------------------------------------------------- sound cues

  /** Builds stereo PCM: notes (Hz), lengths (ms), gaps after each note (ms), left/right level. */
  private short[] synth(int[] hz, int[] ms, int[] gap, float left, float right, double amp) {
    int total = 0;
    for (int i = 0; i < hz.length; i++) total += (ms[i] + gap[i]) * SR / 1000;
    short[] out = new short[total * 2];
    int pos = 0;
    for (int n = 0; n < hz.length; n++) {
      int len = ms[n] * SR / 1000;
      int fade = Math.min(len / 2, 8 * SR / 1000);
      for (int i = 0; i < len; i++) {
        double env = 1.0;
        if (i < fade) env = i / (double) fade;
        else if (i > len - fade) env = (len - i) / (double) fade;
        double v = Math.sin(2 * Math.PI * hz[n] * i / SR) * amp * env;
        out[(pos + i) * 2] = (short) (v * left * 32767);
        out[(pos + i) * 2 + 1] = (short) (v * right * 32767);
      }
      pos += len + gap[n] * SR / 1000;
    }
    return out;
  }

  private synchronized short[] cue(String name) {
    short[] c = cues.get(name);
    if (c != null) return c;
    switch (name) {
      case "chime":
        c = synth(new int[] {880, 1175}, new int[] {140, 220}, new int[] {20, 0}, 1f, 1f, 0.30);
        break;
      case "arrive":
        c =
            synth(
                new int[] {523, 659, 784, 1047},
                new int[] {150, 150, 150, 420},
                new int[] {30, 30, 30, 0},
                1f,
                1f,
                0.40);
        break;
      case "straight": // steady soft beep, centre
        c = synth(new int[] {700}, new int[] {70}, new int[] {0}, 1f, 1f, 0.22);
        break;
      case "left": // two low beeps, mostly in the left ear
        c = synth(new int[] {420, 420}, new int[] {90, 90}, new int[] {70, 0}, 1f, 0.15f, 0.45);
        break;
      case "right": // two high beeps, mostly in the right ear
        c = synth(new int[] {1000, 1000}, new int[] {90, 90}, new int[] {70, 0}, 0.15f, 1f, 0.45);
        break;
      case "back": // long heavy buzz: wrong way
        c = synth(new int[] {240}, new int[] {450}, new int[] {0}, 1f, 1f, 0.45);
        break;
      default:
        return null;
    }
    cues.put(name, c);
    return c;
  }

  private void playPcm(short[] pcm) {
    AudioTrack t = null;
    try {
      AudioAttributes attrs =
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
              .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
              .build();
      AudioFormat fmt =
          new AudioFormat.Builder()
              .setSampleRate(SR)
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
              .build();
      t =
          new AudioTrack(
              attrs, fmt, pcm.length * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
      t.write(pcm, 0, pcm.length);
      t.play();
      Thread.sleep(pcm.length / 2 * 1000L / SR + 40);
    } catch (Exception ignored) {
    } finally {
      if (t != null) {
        try {
          t.release();
        } catch (Exception ignored) {
        }
      }
    }
  }

  /** Plays one cue once, without blocking the caller. */
  private void playAsync(final String name) {
    final short[] pcm = cue(name);
    if (pcm == null) return;
    new Thread(() -> playPcm(pcm)).start();
  }

  private long period(String mode) {
    switch (mode) {
      case "left":
      case "right":
        return 700;
      case "back":
        return 800;
      default:
        return 1100;
    }
  }

  /** Keeps a guidance sound repeating: "straight", "left", "right", "back", or "off". */
  private void setGuide(String m) {
    synchronized (guideLock) {
      guideMode = m == null ? "off" : m;
      if (!guideMode.equals("off") && guideThread == null) {
        guideThread = new Thread(this::guideLoop);
        guideThread.start();
      }
    }
  }

  private void guideLoop() {
    while (true) {
      String m;
      synchronized (guideLock) {
        m = guideMode;
        if (m.equals("off")) {
          guideThread = null;
          return;
        }
      }
      short[] pcm = cue(m);
      if (pcm != null) playPcm(pcm);
      try {
        Thread.sleep(period(m));
      } catch (InterruptedException e) {
        synchronized (guideLock) {
          guideThread = null;
        }
        return;
      }
    }
  }

  /** Called from the main page's JavaScript (window.AndroidTTS.*) to speak with Android's TTS. */
  private class TtsBridge {
    @JavascriptInterface
    public void speak(String text, float rate) {
      // A fresh id for every sentence so the stuck-engine watchdog can tell them apart.
      speakText(text, rate, false, "va" + (++vaSeq), "");
    }

    @JavascriptInterface
    public void stop() {
      runOnUiThread(
          () -> {
            if (ttsReady && tts != null) tts.stop();
          });
    }
  }

  // ---------------------------------------------------------------- Safar bridge

  /** Everything Safar mode needs from the phone (window.AndroidSafar.*). */
  private class SafarBridge {
    @JavascriptInterface
    public void speak(String text, float rate, boolean flush, String id, String lead) {
      speakText(text, rate, flush, id, lead);
    }

    /** Installed speech engines as JSON: [{"name":"package","label":"Name"}, ...]. */
    @JavascriptInterface
    public String ttsEngines() {
      JSONArray arr = new JSONArray();
      try {
        if (tts != null) {
          for (TextToSpeech.EngineInfo e : tts.getEngines()) {
            JSONObject o = new JSONObject();
            o.put("name", e.name);
            o.put("label", e.label);
            arr.put(o);
          }
        }
      } catch (Exception ignored) {
      }
      return arr.toString();
    }

    /** The chosen speech engine package, or "" for the phone's default. */
    @JavascriptInterface
    public String currentTtsEngine() {
      return ttsEngine;
    }

    /** Switches the speech engine and remembers the choice; the page gets window.onTtsReady. */
    @JavascriptInterface
    public void setTtsEngine(String pkg) {
      runOnUiThread(
          () -> {
            ttsEngine = pkg == null ? "" : pkg;
            getSharedPreferences("va", MODE_PRIVATE).edit().putString("tts_engine", ttsEngine).apply();
            createTts();
          });
    }

    /** "straight", "left", "right", "back" repeat until changed; "off" stops the sound. */
    @JavascriptInterface
    public void guide(String mode) {
      setGuide(mode);
    }

    /** Plays one sound once: "chime", "arrive", "left", "right", "back" or "straight". */
    @JavascriptInterface
    public void cue(String name) {
      playAsync(name);
    }

    @JavascriptInterface
    public boolean hasLoc() {
      return locGranted();
    }

    /** Starts GPS updates. Returns false (and asks for permission) if not allowed yet. */
    @JavascriptInterface
    public boolean startLocation() {
      if (!locGranted()) {
        runOnUiThread(
            () -> requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC));
        return false;
      }
      runOnUiThread(() -> startLoc());
      return true;
    }

    @JavascriptInterface
    public void stopLocation() {
      runOnUiThread(() -> stopLoc());
    }

    @JavascriptInterface
    public void beginService() {
      runOnUiThread(
          () -> {
            if (!locGranted()) return;
            Intent i = new Intent(MainActivity.this, SafarService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
          });
    }

    @JavascriptInterface
    public void endService() {
      runOnUiThread(() -> stopService(new Intent(MainActivity.this, SafarService.class)));
    }

    @JavascriptInterface
    public void listen() {
      startListen();
    }

    @JavascriptInterface
    public void cancelListen() {
      runOnUiThread(
          () -> {
            if (recognizer != null) {
              try {
                recognizer.cancel();
              } catch (Exception ignored) {
              }
            }
          });
    }

    /** pattern: comma separated milliseconds, e.g. "0,200,100,200" (wait, buzz, wait, buzz). */
    @JavascriptInterface
    public void vibrate(String pattern) {
      doVibrate(pattern);
    }

    /** Async HTTP request; the result arrives as window.onHttp(id, status, text). */
    @JavascriptInterface
    public void http(String method, String url, String body, String id) {
      httpAsync(method, url, body, id);
    }
  }

  // ---------------------------------------------------------------- GPS

  private void startLoc() {
    if (locRunning) return;
    try {
      locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
      locationListener =
          new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
              pushLoc(l);
            }

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {
              js("window.onLocErr&&window.onLocErr('disabled')");
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}
          };
      locationManager.requestLocationUpdates(
          LocationManager.GPS_PROVIDER, 1500, 0f, locationListener, Looper.getMainLooper());
      locRunning = true;
      Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
      if (last != null) pushLoc(last);
    } catch (SecurityException e) {
      js("window.onLocErr&&window.onLocErr('perm')");
    } catch (Exception e) {
      js("window.onLocErr&&window.onLocErr('error')");
    }
  }

  private void stopLoc() {
    try {
      if (locationManager != null && locationListener != null) {
        locationManager.removeUpdates(locationListener);
      }
    } catch (Exception ignored) {
    }
    locRunning = false;
  }

  private void pushLoc(Location l) {
    float sp = l.hasSpeed() ? l.getSpeed() : -1f;
    js(
        "window.onLoc&&window.onLoc("
            + l.getLatitude()
            + ","
            + l.getLongitude()
            + ","
            + sp
            + ","
            + l.getAccuracy()
            + ","
            + l.getTime()
            + ","
            + (l.hasBearing() ? l.getBearing() : -1f)
            + ")");
  }

  // ---------------------------------------------------------------- voice recognition

  private void startListen() {
    runOnUiThread(
        () -> {
          if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
              != PackageManager.PERMISSION_GRANTED) {
            js("window.onHeardErr&&window.onHeardErr('perm')");
            return;
          }
          if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("window.onHeardErr&&window.onHeardErr('unavailable')");
            return;
          }
          try {
            if (recognizer != null) {
              try {
                recognizer.destroy();
              } catch (Exception ignored) {
              }
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(
                new RecognitionListener() {
                  @Override
                  public void onReadyForSpeech(Bundle params) {}

                  @Override
                  public void onBeginningOfSpeech() {}

                  @Override
                  public void onRmsChanged(float rmsdB) {}

                  @Override
                  public void onBufferReceived(byte[] buffer) {}

                  @Override
                  public void onEndOfSpeech() {}

                  @Override
                  public void onError(int error) {
                    js("window.onHeardErr&&window.onHeardErr('e" + error + "')");
                  }

                  @Override
                  public void onResults(Bundle results) {
                    ArrayList<String> r =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    JSONArray arr = new JSONArray();
                    if (r != null) for (String x : r) arr.put(x);
                    js("window.onHeard&&window.onHeard(" + arr.toString() + ")");
                  }

                  @Override
                  public void onPartialResults(Bundle partialResults) {}

                  @Override
                  public void onEvent(int eventType, Bundle params) {}
                });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            i.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
            recognizer.startListening(i);
          } catch (Exception e) {
            js("window.onHeardErr&&window.onHeardErr('exception')");
          }
        });
  }

  // ---------------------------------------------------------------- vibration

  private void doVibrate(String pattern) {
    try {
      String[] p = pattern.split(",");
      long[] w = new long[p.length];
      for (int i = 0; i < p.length; i++) w[i] = Long.parseLong(p[i].trim());
      Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
      if (v == null || !v.hasVibrator()) return;
      if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(w, -1));
      else v.vibrate(w, -1);
    } catch (Exception ignored) {
    }
  }

  // ---------------------------------------------------------------- HTTP

  private void httpAsync(
      final String method, final String url, final String body, final String id) {
    new Thread(
            () -> {
              int status;
              String text = "";
              HttpURLConnection c = null;
              try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod(method);
                c.setConnectTimeout(20000);
                c.setReadTimeout(90000);
                c.setRequestProperty(
                    "User-Agent", "VisionAssistant/1.0 (personal assistive app for a blind user)");
                c.setRequestProperty("Accept-Language", "hi,mr,en");
                if (body != null && !body.isEmpty()) {
                  c.setDoOutput(true);
                  c.setRequestProperty(
                      "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                  try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                  }
                }
                status = c.getResponseCode();
                InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
                if (is != null) {
                  ByteArrayOutputStream bo = new ByteArrayOutputStream();
                  byte[] buf = new byte[8192];
                  int n;
                  while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                  text = bo.toString("UTF-8");
                }
              } catch (Exception e) {
                status = -1;
                text = String.valueOf(e);
              } finally {
                if (c != null) c.disconnect();
              }
              js(
                  "window.onHttp&&window.onHttp("
                      + JSONObject.quote(id)
                      + ","
                      + status
                      + ","
                      + JSONObject.quote(text)
                      + ")");
            })
        .start();
  }

  // ---------------------------------------------------------------- permissions and lifecycle

  private void askPermissions() {
    List<String> need = new ArrayList<>();
    String[] all = {
      Manifest.permission.CAMERA,
      Manifest.permission.RECORD_AUDIO,
      Manifest.permission.ACCESS_FINE_LOCATION
    };
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
    if (code == REQ) {
      startKeepAlive();
      // Reload so the page can open the camera now that permission is granted.
      webView.reload();
    } else {
      boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
      js("window.onPerm&&window.onPerm(" + ok + ")");
    }
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

  /**
   * Back key: inside Safar mode the page decides (it asks for a second press while a trip is on);
   * on the home page it behaves normally.
   */
  @Override
  public void onBackPressed() {
    String url = webView != null ? webView.getUrl() : null;
    if (url != null && (url.endsWith("safar.html") || url.endsWith("walk.html"))) {
      webView.evaluateJavascript(
          "(window.onBackKey?window.onBackKey():'')",
          value -> {
            if ("\"exit\"".equals(value)) webView.loadUrl(HOME);
          });
      return;
    }
    super.onBackPressed();
  }

  // ---------------------------------------------------------------- in-app update

  private static final String UPDATE_API =
      "https://api.github.com/repos/mobinsayyea-netizen/Vision-assistant.-/releases/latest";
  private boolean updateAsked = false;

  /** Looks for a newer build on GitHub; if there is one, asks the person to update. */
  private void checkForUpdate() {
    if (updateAsked) return;
    new Thread(
            () -> {
              try {
                HttpURLConnection c = (HttpURLConnection) new URL(UPDATE_API).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) return;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                  byte[] b = new byte[4096];
                  int n;
                  while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                }
                JSONObject rel = new JSONObject(bo.toString("UTF-8"));
                String tag = rel.optString("tag_name", "");
                if (!tag.startsWith("build-")) return;
                final int latest = Integer.parseInt(tag.substring(6));
                @SuppressWarnings("deprecation")
                int current = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                if (latest <= current) return;
                String apkUrl = null;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                  for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) {
                      apkUrl = a.optString("browser_download_url");
                      break;
                    }
                  }
                }
                if (apkUrl == null || apkUrl.isEmpty()) return;
                final String url = apkUrl;
                runOnUiThread(() -> showUpdateDialog(latest, url));
              } catch (Exception ignored) {
              }
            })
        .start();
  }

  private void showUpdateDialog(int build, final String url) {
    if (isFinishing() || updateAsked) return;
    updateAsked = true;
    new AlertDialog.Builder(this)
        .setTitle("नया अपडेट उपलब्ध है")
        .setMessage("विज़न असिस्टेंट का नया संस्करण (" + build + ") आ गया है। अभी अपडेट करें?")
        .setPositiveButton("अपडेट करें", (d, w) -> downloadAndInstall(url))
        .setNegativeButton("बाद में", null)
        .show();
  }

  private void downloadAndInstall(final String url) {
    if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
      Toast.makeText(this, "इस ऐप को इंस्टॉल की अनुमति दें, फिर वापस आकर अपडेट दोबारा करें", Toast.LENGTH_LONG).show();
      startActivity(
          new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
      updateAsked = false;
      return;
    }
    Toast.makeText(this, "अपडेट डाउनलोड हो रहा है, कृपया रुकें", Toast.LENGTH_LONG).show();
    new Thread(
            () -> {
              try {
                File dir = new File(getCacheDir(), "updates");
                dir.mkdirs();
                final File apk = new File(dir, "update.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                try (InputStream in = c.getInputStream();
                    FileOutputStream out = new FileOutputStream(apk)) {
                  byte[] b = new byte[16384];
                  int n;
                  while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                runOnUiThread(
                    () -> {
                      try {
                        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(uri, "application/vnd.android.package-archive");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                      } catch (Exception e) {
                        updateAsked = false;
                        Toast.makeText(this, "अपडेट इंस्टॉल नहीं हो पाया", Toast.LENGTH_LONG).show();
                      }
                    });
              } catch (Exception e) {
                updateAsked = false;
                runOnUiThread(
                    () -> Toast.makeText(this, "अपडेट डाउनलोड नहीं हो पाया, नेट जाँचें", Toast.LENGTH_LONG).show());
              }
            })
        .start();
  }

  @Override
  protected void onDestroy() {
    stopLoc();
    setGuide("off");
    if (recognizer != null) {
      try {
        recognizer.destroy();
      } catch (Exception ignored) {
      }
    }
    if (tts != null) {
      try {
        tts.stop();
        tts.shutdown();
      } catch (Exception ignored) {
      }
    }
    if (isFinishing()) {
      stopService(new Intent(this, KeepAliveService.class));
      stopService(new Intent(this, SafarService.class));
    }
    super.onDestroy();
  }
}
