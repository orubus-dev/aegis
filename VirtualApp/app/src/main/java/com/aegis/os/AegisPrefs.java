package com.aegis.os;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Tiny persisted-preferences helper for Aegis OS switches.
 */
public final class AegisPrefs {

    private static final String FILE = "aegis_prefs";

    private AegisPrefs() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean getBoolean(Context ctx, String key, boolean def) {
        return prefs(ctx).getBoolean(key, def);
    }

    public static void putBoolean(Context ctx, String key, boolean value) {
        prefs(ctx).edit().putBoolean(key, value).apply();
    }

    public static String get(Context ctx, String key, String def) {
        return prefs(ctx).getString(key, def);
    }

    public static void put(Context ctx, String key, String value) {
        prefs(ctx).edit().putString(key, value).apply();
    }
}
