package com.dsh.mediacenter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 影厅主界面：承载自研 WebView UI，并提供 MediaStore / 播放器的 JS 桥。
 * 注意：全部内部类均为命名类 —— 本机 d8 对匿名内部类会崩溃。
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 1001;
    private static final String ASSET_URL = "file:///android_asset/index.html";

    private WebView web;
    private TextView hint;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 媒体库快照（后台线程写，JS 线程读） */
    private volatile String libJson = "[]";

    static MainActivity instance;

    /* ---------------- 命名 Runnable / 回调 ---------------- */

    static final class ScanTask implements Runnable {
        private final MainActivity act;

        ScanTask(MainActivity act) { this.act = act; }

        @Override public void run() {
            String json = "[]";
            try { json = act.queryVideos(); } catch (Throwable ignored) {}
            act.libJson = json;
        }
    }

    static final class LibraryRefresh implements Runnable {
        private final MainActivity act;

        LibraryRefresh(MainActivity act) { this.act = act; }

        @Override public void run() {
            act.scanLibrary();
            act.ui.postDelayed(new LibraryRefresh(act), 1500);
        }
    }

    static final class ProgressRefresh implements Runnable {
        private final MainActivity act;

        ProgressRefresh(MainActivity act) { this.act = act; }

        @Override public void run() {
            if (act.web != null) {
                act.web.evaluateJavascript(
                        "if(window.MC&&MC.onProgress)MC.onProgress();", null);
            }
        }
    }

    static final class PageReady extends WebViewClient {
        private final MainActivity act;

        PageReady(MainActivity act) { this.act = act; }

        @Override
        public void onPageFinished(WebView v, String url) {
            act.hint.setVisibility(View.GONE);
            v.evaluateJavascript(
                    "if(window.MC&&MC.onLibraryChanged)MC.onLibraryChanged();", null);
        }
    }

    static final class JsEval implements Runnable {
        private final MainActivity act;
        private final String js;

        JsEval(MainActivity act, String js) { this.act = act; this.js = js; }

        @Override public void run() {
            try { act.web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
        }
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        Thumbs.init(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080D);

        web = new WebView(this);
        web.setBackgroundColor(0xFF07080D);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadsImagesAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        try { s.setAllowFileAccessFromFileURLs(true); } catch (Throwable ignored) {}
        try { s.setAllowUniversalAccessFromFileURLs(true); } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new PageReady(this));
        web.setVerticalScrollBarEnabled(false);
        web.addJavascriptInterface(new Bridge(this), "Native");

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setTextColor(0xFF8A93AD);
        hint.setTextSize(13f);
        hint.setText("正在扫描本地影片…");
        hint.setPadding(dp(24), dp(96), dp(24), dp(24));
        root.addView(hint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        goImmersive();

        web.loadUrl(ASSET_URL);

        if (Build.VERSION.SDK_INT >= 23 && !hasStoragePerm()) {
            hint.setText("需要存储权限才能读取本地影片\n请在系统弹窗中点击「允许」");
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        }

        ui.post(new LibraryRefresh(this));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean hasStoragePerm() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_PERM) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                hint.setVisibility(View.GONE);
                scanLibrary();
            } else {
                hint.setText("未授予存储权限，无法读取影片。\n请到「设置 → 应用 → 影厅 → 权限」中开启后重进。");
            }
        }
    }

    /* ==================== 媒体库扫描 ==================== */

    void scanLibrary() {
        Thread t = new Thread(new ScanTask(this), "media-scan");
        t.setDaemon(true);
        t.start();
    }

    private String queryVideos() {
        Uri uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        };
        Cursor c = null;
        JSONArray arr = new JSONArray();
        try {
            c = getContentResolver().query(uri, proj, null, null,
                    MediaStore.Video.Media.DATE_MODIFIED + " DESC");
            if (c == null) return "[]";
            int iId = c.getColumnIndex(MediaStore.Video.Media._ID);
            int iTitle = c.getColumnIndex(MediaStore.Video.Media.TITLE);
            int iName = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
            int iDur = c.getColumnIndex(MediaStore.Video.Media.DURATION);
            int iSize = c.getColumnIndex(MediaStore.Video.Media.SIZE);
            int iW = c.getColumnIndex(MediaStore.Video.Media.WIDTH);
            int iH = c.getColumnIndex(MediaStore.Video.Media.HEIGHT);
            int iDate = c.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED);
            int iData = c.getColumnIndex(MediaStore.Video.Media.DATA);
            int iBucket = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);

            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                long id = c.getLong(iId);
                String title = iTitle >= 0 ? c.getString(iTitle) : null;
                if (title == null || title.trim().length() == 0) {
                    title = iName >= 0 ? c.getString(iName) : ("视频 " + id);
                }
                o.put("id", id);
                o.put("title", title);
                o.put("dur", iDur >= 0 ? c.getLong(iDur) : 0L);
                o.put("size", iSize >= 0 ? c.getLong(iSize) : 0L);
                o.put("w", iW >= 0 ? c.getInt(iW) : 0);
                o.put("h", iH >= 0 ? c.getInt(iH) : 0);
                long mod = iDate >= 0 ? c.getLong(iDate) : 0L;
                o.put("date", mod > 0 ? mod * 1000L : 0L);
                String path = iData >= 0 ? c.getString(iData) : "";
                o.put("path", path == null ? "" : path);
                String bucket = iBucket >= 0 ? c.getString(iBucket) : null;
                if (bucket == null || bucket.length() == 0) bucket = guessFolder(path);
                o.put("folder", bucket);
                arr.put(o);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return arr.toString();
    }

    private static String guessFolder(String path) {
        if (path == null) return "根目录";
        int i = path.lastIndexOf('/');
        if (i <= 0) return "根目录";
        String parent = path.substring(0, i);
        int j = parent.lastIndexOf('/');
        String leaf = j >= 0 ? parent.substring(j + 1) : parent;
        return leaf.length() == 0 ? "根目录" : leaf;
    }

    /* ==================== 全屏 ==================== */

    private void goImmersive() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(
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
        if (hasFocus) goImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        goImmersive();
        ui.postDelayed(new ProgressRefresh(this), 400);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /* ==================== JS 桥 ==================== */

    /** 静态嵌套类 JS 桥：显式持有 Activity 引用，避免非静态内部类（d8 兼容） */
    public static final class Bridge {
        private final MainActivity act;

        public Bridge(MainActivity act) { this.act = act; }

        @JavascriptInterface
        public String listVideos() { return act.libJson; }

        @JavascriptInterface
        public void rescan() { act.scanLibrary(); }

        @JavascriptInterface
        public String thumb(String id) {
            try {
                return Thumbs.get(act, Long.parseLong(id));
            } catch (Throwable t) {
                return "";
            }
        }

        @JavascriptInterface
        public String progress() { return ProgressStore.all(act); }

        @JavascriptInterface
        public void play(String id, String startMs) {
            long vid;
            long start;
            try { vid = Long.parseLong(id); } catch (Throwable t) { return; }
            try { start = Long.parseLong(startMs); } catch (Throwable t) { start = 0L; }
            Intent it = new Intent(act, PlayerActivity.class);
            it.putExtra("videoId", vid);
            it.putExtra("startMs", start);
            it.putExtra("title", act.titleOf(vid));
            act.startActivity(it);
        }

        @JavascriptInterface
        public String playerState() { return PlayerActivity.stateJson(); }

        @JavascriptInterface
        public void toggle() { PlayerActivity.ctrlToggle(); }

        @JavascriptInterface
        public void seekBy(String deltaMs) {
            try { PlayerActivity.ctrlSeekBy(Long.parseLong(deltaMs)); } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public void seekTo(String fraction) {
            try { PlayerActivity.ctrlSeekToFraction(Double.parseDouble(fraction)); } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public void mute() { PlayerActivity.ctrlToggleMute(); }

        @JavascriptInterface
        public String cycleSpeed() { return PlayerActivity.ctrlCycleSpeed(); }

        @JavascriptInterface
        public void rotate() { PlayerActivity.ctrlRotate(); }

        @JavascriptInterface
        public void stop() { PlayerActivity.ctrlStop(); }
    }

    private String titleOf(long id) {
        try {
            JSONArray a = new JSONArray(libJson);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.optLong("id") == id) return o.optString("title");
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** 供 PlayerActivity 回传事件给前端 */
    static void evalJs(String js) {
        MainActivity a = instance;
        if (a == null || a.web == null) return;
        a.ui.post(new JsEval(a, js));
    }
}
