package com.dsh.mediacenter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 ContentProvider：把 MediaStore 的 video id 映射为可读文件描述符，
 * 供 MediaPlayer 以 content://com.dsh.mediacenter.media/video/<id> 直接硬解播放。
 */
public class MediaProvider extends ContentProvider {

    public static final String AUTHORITY = "com.dsh.mediacenter.media";

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        long id;
        try {
            id = Long.parseLong(uri.getLastPathSegment());
        } catch (Throwable t) {
            throw new FileNotFoundException("bad id: " + uri);
        }
        Uri media = Uri.parse("content://media/external/video/media/" + id);
        try {
            ParcelFileDescriptor src = getContext().getContentResolver()
                    .openFileDescriptor(media, "r");
            if (src == null) throw new FileNotFoundException("cannot open media " + id);

            // 为 MediaPlayer 提供可 seek 的真实 fd：优先直接复用
            return src;
        } catch (Throwable t) {
            // 兜底：复制到缓存文件（仅当直接打开失败）
            try {
                File out = new File(getContext().getCacheDir(), "v" + id + ".mp4");
                if (!out.exists() || out.length() == 0) {
                    java.io.InputStream in = getContext().getContentResolver().openInputStream(media);
                    if (in == null) throw new FileNotFoundException("no stream " + id);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    byte[] buf = new byte[262144];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    fos.close();
                    in.close();
                }
                return ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY);
            } catch (Throwable t2) {
                throw new FileNotFoundException("open failed for " + id + ": " + t2);
            }
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) { return null; }

    @Override
    public String getType(Uri uri) { return "video/*"; }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
