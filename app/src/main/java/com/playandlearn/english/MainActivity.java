// فایل: app/src/main/java/com/playandlearn/english/MainActivity.java
package com.playandlearn.english;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.VideoView;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;

public class MainActivity extends Activity implements RecognitionListener {
    private static final int REQUEST_RECORD_AUDIO = 41;
    /** اگر true باشد با لمس صفحه می‌شود فیلم ابتدایی را رد کرد. */
    private static final boolean ALLOW_SKIP_INTRO = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView webView;
    private VideoView videoView;
    private android.app.Dialog exitDialog;
    private boolean gameStarted = false, introRemoved = false, videoWasPlaying = false, pageReady = false;

    private Model model;
    private Recognizer recognizer;
    private SpeechService speechService;
    private String pendingWord = null, pendingGrammar = null, lastRecognizedText = "";
    private boolean modelReady = false, modelLoading = false, listening = false, partialSent = false;
    private String modelLoadError = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        applyImmersive();

        setupWebView();
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        loadOfflineModel();   // هم‌زمان با فیلم ابتدایی آماده می‌شود
        startIntro();
    }

    // ---------------- تمام‌صفحه ----------------
    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    // ---------------- فیلم ابتدایی ----------------
    private void startIntro() {
        int resId = getResources().getIdentifier("intro", "raw", getPackageName());
        if (resId == 0) { startGame(); return; }
        videoView = new VideoView(this);
        root.addView(videoView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        videoView.setOnPreparedListener(mp -> {
            mp.setLooping(false);
            try { fitCover(mp.getVideoWidth(), mp.getVideoHeight()); } catch (Exception ignored) { }
            videoView.start();
        });
        videoView.setOnCompletionListener(mp -> startGame());
        videoView.setOnErrorListener((mp, what, extra) -> { startGame(); return true; });
        if (ALLOW_SKIP_INTRO) videoView.setOnClickListener(v -> startGame());
        videoView.setVideoURI(Uri.parse("android.resource://" + getPackageName() + "/" + resId));
    }

    /** فیلم کل صفحه را پر می‌کند (بدون نوار سیاه؛ لبه‌ها کمی بریده می‌شود). */
    private void fitCover(int vw, int vh) {
        int pw = root.getWidth(), ph = root.getHeight();
        if (pw == 0 || ph == 0 || vw == 0 || vh == 0 || videoView == null) return;
        float s = Math.max(pw / (float) vw, ph / (float) vh);
        videoView.setLayoutParams(new FrameLayout.LayoutParams(
                Math.round(vw * s), Math.round(vh * s), Gravity.CENTER));
    }

    private void startGame() {
        if (gameStarted || webView == null) return;
        gameStarted = true;
        if (pageReady) {
            removeIntro();
        } else {
            // The page is already loading behind the intro; reveal it as soon as it finishes.
            handler.postDelayed(() -> {
                if (!pageReady) sendStatus("در حال آماده‌سازی بازی...");
            }, 1500);
        }
    }

    private void removeIntro() {
        if (introRemoved) return;
        introRemoved = true;
        if (videoView != null) {
            try { videoView.stopPlayback(); } catch (Exception ignored) { }
            root.removeView(videoView);
            videoView = null;
        }
    }

    // ---------------- وب‌ویو ----------------
    private void setupWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setLongClickable(false);
        webView.setOnLongClickListener(v -> true);
        webView.setHapticFeedbackEnabled(false);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);   // پخش صدا بدون ضربه
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                // Keep the intro visible until it finishes; if it already finished, reveal the game now.
                if (gameStarted) removeIntro();
                if (modelReady) sendStatus("موتور تشخیص گفتار آفلاین آماده است.");
                else if (modelLoadError != null) sendError(modelLoadError);
                else sendStatus("در حال آماده‌سازی مدل گفتار آفلاین؛ بار اول ممکن است کمی طول بکشد...");
            }
        });
        webView.addJavascriptInterface(new VoskBridge(), "VoskBridge");
        root.addView(webView, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // Load the game behind the intro video so HTML/JS initialization overlaps video playback.
        webView.loadUrl("file:///android_asset/index.html");
    }

    // ---------------- دکمه‌ی برگشت (مثلث) ----------------
    @Override
    public void onBackPressed() {
        if (exitDialog != null && exitDialog.isShowing()) return;
        if (webView != null && pageReady) {
            // زبان بازی را از تنظیمات خود بازی می‌خوانیم تا متن پنجره هم‌زبان باشد
            webView.evaluateJavascript(
                    "(function(){try{var s=JSON.parse(localStorage.getItem('ehsan_game_settings_v31'));return (s&&s.lang)||'fa';}catch(e){return 'fa';}})()",
                    value -> showExitDialog(value != null && value.contains("en")));
        } else {
            showExitDialog(false);
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private android.widget.TextView dialogButton(String text, int bg, int fg) {
        android.widget.TextView b = new android.widget.TextView(this);
        b.setText(text);
        b.setTextColor(fg);
        b.setTextSize(16);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), dp(12), dp(8), dp(12));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        return b;
    }

    /** پنجره‌ی خروج: تیره و هماهنگ با پس‌زمینه‌ی بازی. */
    private void showExitDialog(boolean en) {
        final android.app.Dialog d = new android.app.Dialog(this);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(22), dp(22), dp(18));
        if (!en && Build.VERSION.SDK_INT >= 17) box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#0B1020"));
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(2), Color.parseColor("#25E0C1"));
        box.setBackground(bg);

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText(en ? "Exit app" : "خروج از برنامه");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        android.widget.TextView msg = new android.widget.TextView(this);
        msg.setText(en ? "Do you want to exit the app?" : "می‌خوای از برنامه خارج بشی؟");
        msg.setTextColor(Color.parseColor("#B8C4E0"));
        msg.setTextSize(16);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, dp(10), 0, dp(20));
        box.addView(msg);

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.TextView stay = dialogButton(en ? "Keep playing" : "ادامه بازی", Color.parseColor("#25E0C1"), Color.parseColor("#06202A"));
        android.widget.TextView exit = dialogButton(en ? "Exit" : "خروج", Color.parseColor("#2A3350"), Color.WHITE);
        android.widget.LinearLayout.LayoutParams lp1 = new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.setMargins(0, 0, dp(6), 0);
        android.widget.LinearLayout.LayoutParams lp2 = new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.setMargins(dp(6), 0, 0, 0);
        row.addView(stay, lp1);
        row.addView(exit, lp2);
        box.addView(row);

        stay.setOnClickListener(v -> d.dismiss());
        exit.setOnClickListener(v -> { d.dismiss(); finish(); });
        d.setContentView(box);
        d.setOnDismissListener(x -> applyImmersive());
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.86f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        exitDialog = d;
        d.show();
    }

    // ---------------- تشخیص گفتار آفلاین (Vosk) ----------------
    private void loadOfflineModel() {
        if (modelReady || modelLoading) return;
        modelLoading = true;
        modelLoadError = null;
        sendStatus("در حال بارگذاری مدل گفتار آفلاین؛ بار اول ممکن است ۳۰ تا ۹۰ ثانیه طول بکشد...");
        try {
            // The model AAR exposes its assets under model-en-us. StorageService
            // copies it to app-private storage and uses its UUID to avoid copying
            // the model again on every launch.
            StorageService.unpack(this, "model-en-us", "vosk-model",
                    loadedModel -> {
                        model = loadedModel;
                        modelLoading = false;
                        modelReady = true;
                        modelLoadError = null;
                        sendStatus("موتور تشخیص گفتار آفلاین آماده است.");
                        if (pendingWord != null) startWhenReady();
                    },
                    exception -> {
                        modelLoading = false;
                        modelReady = false;
                        modelLoadError = "بارگذاری مدل گفتار ناموفق بود: "
                                + (exception == null ? "خطای نامشخص" : exception.getClass().getSimpleName()
                                + " - " + exception.getMessage());
                        sendError(modelLoadError);
                    });
        } catch (Exception exception) {
            modelLoading = false;
            modelReady = false;
            modelLoadError = "شروع بارگذاری مدل ممکن نشد: " + exception.getClass().getSimpleName()
                    + " - " + exception.getMessage();
            sendError(modelLoadError);
        }
    }

    private class VoskBridge {
        @JavascriptInterface
        public void startListening(String word) { begin(word, null); }

        @JavascriptInterface
        public void startListeningWithGrammar(String word, String grammarJson) { begin(word, grammarJson); }

        @JavascriptInterface
        public void stopListening() {
            runOnUiThread(() -> { pendingWord = null; listening = false; stopRecognition(); });
        }

        @JavascriptInterface
        public void retryModel() {
            runOnUiThread(() -> {
                if (model != null) { try { model.close(); } catch (Exception ignored) { } model = null; }
                modelReady = false;
                modelLoading = false;
                modelLoadError = null;
                loadOfflineModel();
            });
        }

        private void begin(String word, String grammar) {
            pendingWord = word == null ? "" : word.trim();
            pendingGrammar = (grammar == null || grammar.trim().isEmpty()) ? null : grammar;
            runOnUiThread(() -> {
                if (pendingWord.isEmpty()) { sendError("واژه‌ای برای تشخیص دریافت نشد."); return; }
                if (Build.VERSION.SDK_INT >= 23
                        && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
                    sendStatus("برای استفاده از میکروفون، اجازه ضبط صدا را تأیید کن.");
                    return;
                }
                startWhenReady();
            });
        }
    }

    private void startWhenReady() {
        if (pendingWord == null) return;
        if (modelLoadError != null) {
            sendError(modelLoadError + " برنامه را یک‌بار ببند و دوباره باز کن؛ اگر تکرار شد، متن خطا را بفرست.");
            return;
        }
        if (!modelReady || model == null) {
            sendStatus(modelLoading
                    ? "مدل گفتار هنوز در حال بارگذاری است؛ بار اول ممکن است کمی طول بکشد..."
                    : "بارگذاری مدل شروع نشده؛ در حال تلاش دوباره...");
            if (!modelLoading) loadOfflineModel();
            return;
        }
        stopRecognition();
        lastRecognizedText = "";
        partialSent = false;
        try {
            // اگر فهرست واژه‌های درس رسیده باشد، تشخیص فقط بین همان واژه‌ها انجام می‌شود (دقت بیشتر).
            String g = neighborGrammar(pendingWord);
            recognizer = (g != null)
                    ? new Recognizer(model, 16000.0f, g)
                    : new Recognizer(model, 16000.0f);
            recognizer.setMaxAlternatives(ACCEPT_TOP_N);
            speechService = new SpeechService(recognizer, 16000.0f);
            listening = true;
            setMediaMuted(true);
            sendStatus("در حال گوش دادن؛ حالا واژه را تلفظ کن...");
            speechService.startListening(this, 5000);
        } catch (IOException | RuntimeException e) {
            listening = false;
            stopRecognition();
            sendError("شروع میکروفون ممکن نشد: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startWhenReady();
            } else {
                sendError("اجازه میکروفون داده نشد. برای بازی تلفظ باید اجازه ضبط صدا را فعال کنی.");
            }
        }
    }

    @Override
    public void onResult(String hypothesis) {
        String text = extractText(hypothesis);
        if (!text.isEmpty()) finishWithResult(text);   // بلافاصله بعد از تشخیص، نتیجه را بده
    }

    @Override
    public void onPartialResult(String hypothesis) {
        if (listening && !partialSent) { partialSent = true; sendStatus("صدایت دریافت شد؛ کمی صبر کن..."); }
    }

    @Override
    public void onFinalResult(String hypothesis) {
        String text = extractText(hypothesis);
        finishWithResult(text.isEmpty() ? lastRecognizedText : text);
    }

    @Override
    public void onTimeout() {
        String text = lastRecognizedText;
        try {
            if (recognizer != null) {
                String t = extractText(recognizer.getFinalResult());
                if (!t.isEmpty()) text = t;
            }
        } catch (Exception ignored) { }
        finishWithResult(text);
    }

    @Override
    public void onError(Exception e) {
        if (!listening) return;
        listening = false;
        stopRecognition();
        sendError("خطا در تشخیص صدا: " + (e == null ? "نامشخص" : e.getMessage()));
    }

    // خروجی‌هایی که فقط «پر‌کننده» هستند (مثل the که موتور برای نویز می‌دهد) نادیده گرفته می‌شوند.
    private static final java.util.Set<String> FILLERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "the", "a", "uh", "um", "huh", "oh", "hmm", "mm", "eh", "ah"));

    /** 1 = خیلی سخت‌گیر (فقط بهترین حدس)، 2 = متعادل، 3 = راحت‌تر، 4 = خیلی راحت، 5 = بسیار راحت (فعلی). */
    private static final int ACCEPT_TOP_N = 5;
    /** تعداد واژه‌های شبیه در فهرست تشخیص: بیشتر = سخت‌گیرتر. */
    private static final int MAX_NEIGHBORS = 6;

    private String extractText(String json) {
        try {
            JSONObject o = new JSONObject(json);
            org.json.JSONArray alts = o.optJSONArray("alternatives");
            if (alts == null) return cleanFiller(o.optString("text", "").trim());
            String first = "";
            for (int i = 0; i < alts.length() && i < ACCEPT_TOP_N; i++) {
                String t = cleanFiller(alts.getJSONObject(i).optString("text", "").trim());
                if (t.isEmpty()) continue;
                if (first.isEmpty()) first = t;
                if (matchesTarget(t)) return pendingWord;   // واژه‌ی هدف بین حدس‌های برتر است
            }
            return first;
        } catch (Exception e) { return ""; }
    }

    private String cleanFiller(String t) {
        boolean onlyFiller = true;
        for (String p : t.toLowerCase().split("\\s+")) {
            if (!p.isEmpty() && !FILLERS.contains(p)) onlyFiller = false;
        }
        return onlyFiller ? "" : t;
    }

    private boolean matchesTarget(String heard) {
        if (pendingWord == null) return false;
        java.util.List<String> h = java.util.Arrays.asList(
                heard.toLowerCase().replaceAll("[^a-z' ]", " ").trim().split("\\s+"));
        for (String part : pendingWord.split("/")) {
            String[] w = part.toLowerCase().replaceAll("[^a-z' ]", " ").trim().split("\\s+");
            boolean all = w.length > 0 && !w[0].isEmpty();
            for (String t : w) if (!h.contains(t)) { all = false; break; }
            if (all) return true;
            // کلمه‌های بلند (۵ حرف به بالا): یک حرف اختلاف هم قبول است
            if (w.length == 1 && w[0].length() >= 5) {
                for (String x : h) if (editDistance(x, w[0]) <= 1) return true;
            }
        }
        return false;
    }

    private java.io.File findWordsFile() {
        java.io.File base = new java.io.File(getExternalFilesDir(null), "vosk-model");
        java.io.File[] cands = {
                new java.io.File(base, "model-en-us/graph/words.txt"),
                new java.io.File(base, "graph/words.txt")};
        for (java.io.File c : cands) if (c.exists()) return c;
        return findFile(base, "words.txt", 4);
    }

    private java.io.File findFile(java.io.File dir, String name, int depth) {
        if (dir == null || depth < 0) return null;
        java.io.File[] list = dir.listFiles();
        if (list == null) return null;
        for (java.io.File f : list) if (f.isFile() && f.getName().equals(name)) return f;
        for (java.io.File f : list) {
            if (f.isDirectory()) { java.io.File r = findFile(f, name, depth - 1); if (r != null) return r; }
        }
        return null;
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** گرامر محدود: واژه‌ی هدف + تا ۶ واژه‌ی شبیه به آن از فهرست خود مدل + [unk]. */
    private String neighborGrammar(String word) {
        try {
            if (word == null || word.contains(" ") || word.contains("/")) return null;
            final String w = word.toLowerCase().replaceAll("[^a-z']", "");
            if (w.length() < 2) return null;
            java.io.File f = findWordsFile();
            if (f == null) return null;
            java.util.List<String[]> found = new java.util.ArrayList<>();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int sp = line.indexOf(' ');
                    String c = sp > 0 ? line.substring(0, sp) : line;
                    if (c.equals(w) || !c.matches("[a-z']{2,}") || Math.abs(c.length() - w.length()) > 2) continue;
                    int d = editDistance(c, w);
                    if (d <= 2) found.add(new String[]{c, String.valueOf(d)});
                }
            }
            java.util.Collections.sort(found, (x, y) -> x[1].compareTo(y[1]));
            org.json.JSONArray arr = new org.json.JSONArray();
            arr.put(w);
            for (int i = 0; i < found.size() && i < MAX_NEIGHBORS; i++) arr.put(found.get(i)[0]);
            arr.put("[unk]");
            return arr.toString();
        } catch (Exception e) { return null; }
    }

    private void finishWithResult(String text) {
        if (!listening) return;   // هر نشست فقط یک بار نتیجه می‌دهد
        listening = false;
        stopRecognition();
        pendingWord = null;
        if (text == null || text.trim().isEmpty()) {
            sendError("صدایی تشخیص داده نشد. دوباره تلاش کن.");
        } else {
            sendResult(text.trim());
        }
    }

    private boolean mediaMuted = false;

    private void setMediaMuted(boolean mute) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null || mute == mediaMuted) return;
            if (Build.VERSION.SDK_INT >= 23) {
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        mute ? android.media.AudioManager.ADJUST_MUTE : android.media.AudioManager.ADJUST_UNMUTE, 0);
            } else {
                am.setStreamMute(android.media.AudioManager.STREAM_MUSIC, mute);
            }
            mediaMuted = mute;
        } catch (Exception ignored) { }
    }

    private void stopRecognition() {
        setMediaMuted(false);
        if (speechService != null) {
            try { speechService.stop(); speechService.shutdown(); } catch (Exception ignored) { }
            speechService = null;
        }
        if (recognizer != null) {
            try { recognizer.close(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private void runJs(String js) {
        runOnUiThread(() -> { if (webView != null && pageReady) webView.evaluateJavascript(js, null); });
    }
    private void sendStatus(String m) { runJs("if(window.onVoskStatus)window.onVoskStatus(" + JSONObject.quote(m) + ");"); }
    private void sendError(String m)  { runJs("if(window.onVoskError)window.onVoskError(" + JSONObject.quote(m) + ");"); }
    private void sendResult(String t) { runJs("if(window.onVoskResult)window.onVoskResult(" + JSONObject.quote(t) + ");"); }

    // ---------------- چرخه‌ی عمر ----------------
    @Override
    protected void onPause() {
        if (videoView != null && videoView.isPlaying()) { videoView.pause(); videoWasPlaying = true; }
        if (webView != null) webView.onPause();
        listening = false;
        stopRecognition();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (videoWasPlaying && videoView != null) { videoView.start(); videoWasPlaying = false; }
        applyImmersive();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        listening = false;
        stopRecognition();
        if (model != null) { try { model.close(); } catch (Exception ignored) { } model = null; }
        if (videoView != null) { try { videoView.stopPlayback(); } catch (Exception ignored) { } }
        if (webView != null) {
            webView.removeJavascriptInterface("VoskBridge");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
