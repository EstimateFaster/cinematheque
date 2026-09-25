package com.dsh.mediacenter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 缩略图提取：后台线程 + 内存 LRU 缓存 + 磁盘缓存。
 * 原生 MediaMetadataRetriever 取帧，输出 320px 宽的 JPEG data-URI 供 WebView 直接显示。
 */
public final class Thumbs {

    private static final int TARGET_W = 400;
    private static final int CACHE_KB = 12 * 1024;

    private static LruCache<String, String> mem;
    private static ConcurrentLinkedQueue<Long> queue;
    private static volatile boolean workerStarted = false;
    private static Context appCtx;

    private Thumbs() {}

    /** 静态嵌套类：避免非静态内部类（d8 兼容） */
    static final class ThumbCache extends LruCache<String, String> {
        ThumbCache(int maxKb) { super(maxKb); }

        @Override
        protected int sizeOf(String key, String value) {
            return value == null ? 0 : value.length() / 1024;
        }
    }

    /** 静态嵌套类：后台提取线程体 */
    static final class ThumbWorker implements Runnable {
        @Override public void run() {
            while (true) {
                Long id = queue.poll();
                if (id == null) {
                    try { Thread.sleep(120); } catch (InterruptedException e) { return; }
                    continue;
                }
                try {
                    if (mem.get(key(id.longValue())) != null) continue;
                    Bitmap bmp = extract(id.longValue());
                    if (bmp == null) continue;
                    String data = toDataUri(bmp);
                    bmp.recycle();
                    if (data != null) {
                        mem.put(key(id.longValue()), data);
                        writeDisk(id.longValue(), data);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    public static void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
        mem = new ThumbCache(CACHE_KB);
        queue = new ConcurrentLinkedQueue<Long>();
    }

    /** 立即返回：命中缓存给 data-URI，否则入队后台生成并返回空串 */
    public static String get(Context ctx, long id) {
        if (mem == null) init(ctx);
        String hit = mem.get(key(id));
        if (hit != null) return hit;

        // 磁盘缓存
        String disk = readDisk(id);
        if (disk != null) {
            mem.put(key(id), disk);
            return disk;
        }

        queue.offer(Long.valueOf(id));
        startWorker();
        return "";
    }

    private static String key(long id) { return "v" + id; }

    private static void startWorker() {
        if (workerStarted) return;
        workerStarted = true;
        Thread t = new Thread(new ThumbWorker(), "thumb-worker");
        t.setDaemon(true);
        t.start();
    }

    private static Bitmap extract(long id) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(appCtx, Uri.parse("content://media/external/video/media/" + id));
            Bitmap raw = r.getFrameAtTime(2_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (raw == null) raw = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (raw == null) return null;
            return scale(raw);
        } catch (Throwable t) {
            // 兜底：用 MediaStore 里已生成的缩略图
            try {
                return scale(MediaStore.Video.Thumbnails.getThumbnail(
                        appCtx.getContentResolver(), id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null));
            } catch (Throwable t2) {
                return null;
            }
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
    }

    private static Bitmap scale(Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        if (w <= TARGET_W) return src;
        int nh = Math.max(1, (int) (h * (TARGET_W / (float) w)));
        Bitmap out = Bitmap.createScaledBitmap(src, TARGET_W, nh, true);
        if (out != src) src.recycle();
        return out;
    }

    private static String toDataUri(Bitmap bmp) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 72, bos);
            byte[] bytes = bos.toByteArray();
            return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------- 磁盘缓存 ---------- */
    private static java.io.File cacheFile(long id) {
        java.io.File dir = new java.io.File(appCtx.getCacheDir(), "thumbs");
        if (!dir.exists()) dir.mkdirs();
        return new java.io.File(dir, "t" + id + ".txt");
    }

    private static String readDisk(long id) {
        try {
            java.io.File f = cacheFile(id);
            if (!f.exists() || f.length() < 32) return null;
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            in.close();
            return new String(buf, 0, off, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeDisk(long id, String data) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(cacheFile(id));
            out.write(data.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {}
    }

    /** 供预览图解码复用 */
    public static Bitmap decode(byte[] data) {
        return BitmapFactory.decodeByteArray(data, 0, data.length);
    }
}
