package com.dsh.mediacenter;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;

/**
 * 观看进度持久化：videoId -> {ms, dur, at}
 * 存成 JSON 字符串，直接交给前端使用。
 */
public final class ProgressStore {

    private static final String PREF = "ct_progress";
    private static final String KEY = "data";

    private ProgressStore() {}

    public static synchronized void save(Context ctx, long id, int ms, int dur) {
        if (id <= 0) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONObject root = read(sp);
            JSONObject o = new JSONObject();
            o.put("ms", ms);
            o.put("dur", dur);
            o.put("at", System.currentTimeMillis());
            root.put(String.valueOf(id), o);
            sp.edit().putString(KEY, root.toString()).apply();
        } catch (Throwable ignored) {}
    }

    public static synchronized String all(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            return read(sp).toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    public static synchronized void clear(Context ctx, long id) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONObject root = read(sp);
            root.remove(String.valueOf(id));
            sp.edit().putString(KEY, root.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private static JSONObject read(SharedPreferences sp) {
        try {
            String s = sp.getString(KEY, "{}");
            return new JSONObject(s == null ? "{}" : s);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }
}
