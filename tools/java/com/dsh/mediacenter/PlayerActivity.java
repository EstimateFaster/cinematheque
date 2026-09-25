package com.dsh.mediacenter;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 原生全屏播放器（硬解）。画面在本 Activity，控制层为 WebView 自研 UI 覆盖其上。
 * 全部内部类命名化 —— 规避本机 d8 对匿名类的崩溃。
 */
public class PlayerActivity extends Activity implements SurfaceHolder.Callback {

    private static PlayerActivity inst;

    private SurfaceView surface;
    private TextView loading;
    private MediaPlayer mp;
    private boolean prepared = false;

    private long videoId = -1;
    private String title = "";
    private long pendingStartMs = 0;

    private int curPos = 0;
    private int dur = 0;
    private int bufPct = 0;
    private float speed = 1.0f;
    private boolean muted = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private GestureDetector gestures;

    /* ==================== 命名内部类 ==================== */

    static final class Ticker implements Runnable {
        private final PlayerActivity act;

        Ticker(PlayerActivity a) { this.act = a; }

        @Override public void run() {
            try {
                if (act.mp != null && act.prepared) {
                    act.curPos = act.mp.getCurrentPosition();
                    act.dur = act.mp.getDuration();
                }
            } catch (Throwable ignored) {}
            act.h.postDelayed(new Ticker(act), 400);
        }
    }

    static final class Prepared implements MediaPlayer.OnPreparedListener {
        private final PlayerActivity act;

        Prepared(PlayerActivity a) { this.act = a; }

        @Override public void onPrepared(MediaPlayer m) {
            act.prepared = true;
            act.dur = m.getDuration();
            if (act.pendingStartMs > 0 && act.pendingStartMs < act.dur - 2000) {
                m.seekTo((int) act.pendingStartMs);
            }
            m.start();
            act.loading.setVisibility(View.GONE);
            act.startTicker();
        }
    }

    static final class Completed implements MediaPlayer.OnCompletionListener {
        private final PlayerActivity act;

        Completed(PlayerActivity a) { this.act = a; }

        @Override public void onCompletion(MediaPlayer m) { act.finishAndReport(); }
    }

    static final class Errored implements MediaPlayer.OnErrorListener {
        private final PlayerActivity act;

        Errored(PlayerActivity a) { this.act = a; }

        @Override public boolean onError(MediaPlayer m, int what, int extra) {
            act.loading.setVisibility(View.VISIBLE);
            act.loading.setText("无法播放该文件\n(编码不支持或文件损坏)");
            return true;
        }
    }

    static final class Buffering implements MediaPlayer.OnBufferingUpdateListener {
        private final PlayerActivity act;

        Buffering(PlayerActivity a) { this.act = a; }

        @Override public void onBufferingUpdate(MediaPlayer m, int percent) {
            act.bufPct = percent;
        }
    }

    static final class TouchRouter implements View.OnTouchListener {
        private final PlayerActivity act;

        TouchRouter(PlayerActivity a) { this.act = a; }

        @Override public boolean onTouch(View v, MotionEvent ev) {
            return act.gestures != null && act.gestures.onTouchEvent(ev);
        }
    }

    static final class TapGestures extends GestureDetector.SimpleOnGestureListener {
        private final PlayerActivity act;

        TapGestures(PlayerActivity a) { this.act = a; }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            act.toggleControls();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            float w = act.surface == null ? 1f : act.surface.getWidth();
            if (e.getX() < w * 0.35f) {
                ctrlSeekBy(-10000);
            } else if (e.getX() > w * 0.65f) {
                ctrlSeekBy(10000);
            } else {
                ctrlToggle();
            }
            return true;
        }
    }

    /* ==================== 生命周期 ==================== */

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        videoId = getIntent().getLongExtra("videoId", -1);
        pendingStartMs = getIntent().getLongExtra("startMs", 0);
        String t = getIntent().getStringExtra("title");
        title = t == null ? "" : t;

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loading = new TextView(this);
        loading.setTextColor(0xFFD8DEF0);
        loading.setTextSize(14f);
        loading.setText("正在打开影片…");
        loading.setPadding(64, 64, 64, 64);
        root.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        gestures = new GestureDetector(this, new TapGestures(this));
        surface.setOnTouchListener(new TouchRouter(this));

        goImmersive();
    }

    private void toggleControls() {
        MainActivity.evalJs(
                "(function(){var c=document.getElementById('playerCtl');"
                        + "if(c)c.classList.toggle('hide');})();");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) { prepare(holder); }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int f, int w, int hh) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        try { if (mp != null && mp.isPlaying()) mp.pause(); } catch (Throwable ignored) {}
    }

    private void prepare(SurfaceHolder holder) {
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDisplay(holder);
            mp.setDataSource(this, Uri.parse(
                    "content://com.dsh.mediacenter.media/video/" + videoId));
            mp.setOnPreparedListener(new Prepared(this));
            mp.setOnCompletionListener(new Completed(this));
            mp.setOnErrorListener(new Errored(this));
            mp.setOnBufferingUpdateListener(new Buffering(this));
            mp.prepareAsync();
        } catch (Throwable t) {
            loading.setVisibility(View.VISIBLE);
            loading.setText("打开失败：" + t.getClass().getSimpleName() + "\n" + t.getMessage());
        }
    }

    private void startTicker() {
        h.removeCallbacksAndMessages(null);
        h.postDelayed(new Ticker(this), 400);
    }

    private void goImmersive() {
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
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) goImmersive();
    }

    private void finishAndReport() {
        saveProgress();
        try { if (mp != null) mp.stop(); } catch (Throwable ignored) {}
        MainActivity.evalJs("if(window.MC&&MC.onPlayerClosed)MC.onPlayerClosed();");
        finish();
    }

    private void saveProgress() {
        try { ProgressStore.save(this, videoId, curPos, dur); } catch (Throwable ignored) {}
    }

    @Override
    public void onBackPressed() { finishAndReport(); }

    @Override
    protected void onPause() {
        super.onPause();
        try { if (mp != null && mp.isPlaying()) mp.pause(); } catch (Throwable ignored) {}
        saveProgress();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        saveProgress();
        try { if (mp != null) { mp.release(); mp = null; } } catch (Throwable ignored) {}
        if (inst == this) inst = null;
    }

    /* ==================== 前端可调用的静态入口 ==================== */

    static String stateJson() {
        PlayerActivity a = inst;
        JSONObject o = new JSONObject();
        try {
            if (a == null) {
                o.put("active", false);
                return o.toString();
            }
            boolean playing = false;
            try { playing = a.mp != null && a.mp.isPlaying(); } catch (Throwable ignored) {}
            o.put("active", true);
            o.put("id", a.videoId);
            o.put("title", a.title);
            o.put("pos", a.curPos);
            o.put("dur", a.dur);
            o.put("buf", a.dur > 0 ? (long) a.dur * a.bufPct / 100L : 0L);
            o.put("playing", playing);
            o.put("speed", a.speed);
            o.put("muted", a.muted);
            o.put("prepared", a.prepared);
        } catch (Throwable ignored) {}
        return o.toString();
    }

    static void ctrlToggle() {
        PlayerActivity a = inst;
        if (a == null || a.mp == null) return;
        try {
            if (a.mp.isPlaying()) mpPause(a); else mpStart(a);
        } catch (Throwable ignored) {}
    }

    private static void mpStart(PlayerActivity a) {
        try { a.mp.start(); } catch (Throwable ignored) {}
    }

    private static void mpPause(PlayerActivity a) {
        try { a.mp.pause(); } catch (Throwable ignored) {}
    }

    static void ctrlSeekBy(long delta) {
        PlayerActivity a = inst;
        if (a == null || a.mp == null) return;
        try {
            int np = a.mp.getCurrentPosition() + (int) delta;
            if (np < 0) np = 0;
            int d = a.mp.getDuration();
            if (d > 0 && np > d - 500) np = Math.max(0, d - 500);
            a.mp.seekTo(np);
            a.curPos = np;
        } catch (Throwable ignored) {}
    }

    static void ctrlSeekToFraction(double f) {
        PlayerActivity a = inst;
        if (a == null || a.mp == null) return;
        try {
            int d = a.mp.getDuration();
            int np = (int) (d * Math.max(0.0, Math.min(1.0, f)));
            a.mp.seekTo(np);
            a.curPos = np;
        } catch (Throwable ignored) {}
    }

    static void ctrlToggleMute() {
        PlayerActivity a = inst;
        if (a == null || a.mp == null) return;
        try {
            a.muted = !a.muted;
            a.mp.setVolume(a.muted ? 0f : 1f, a.muted ? 0f : 1f);
        } catch (Throwable ignored) {}
    }

    static String ctrlCycleSpeed() {
        PlayerActivity a = inst;
        if (a == null || a.mp == null) return "1.0";
        try {
            float[] opts = {1.0f, 1.25f, 1.5f, 2.0f, 0.75f};
            int idx = 0;
            for (int i = 0; i < opts.length; i++) {
                if (Math.abs(opts[i] - a.speed) < 0.01f) { idx = i; break; }
            }
            a.speed = opts[(idx + 1) % opts.length];
            if (Build.VERSION.SDK_INT >= 23) {
                a.mp.setPlaybackParams(a.mp.getPlaybackParams().setSpeed(a.speed));
                if (!a.mp.isPlaying()) a.mp.start();
            }
            return String.format(Locale.US, "%.2f", a.speed);
        } catch (Throwable t) {
            return String.format(Locale.US, "%.2f", a.speed);
        }
    }

    static void ctrlRotate() {
        PlayerActivity a = inst;
        if (a == null) return;
        int cur = a.getRequestedOrientation();
        if (cur == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        } else {
            a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    static void ctrlStop() {
        PlayerActivity a = inst;
        if (a == null) return;
        a.runOnUiThread(new StopNow(a));
    }

    /** 静态嵌套类（d8 兼容） */
    static final class StopNow implements Runnable {
        private final PlayerActivity act;

        StopNow(PlayerActivity a) { this.act = a; }

        @Override public void run() { act.finishAndReport(); }
    }
}
