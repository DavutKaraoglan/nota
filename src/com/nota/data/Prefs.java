package com.nota.data;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private static SharedPreferences p;

    public static SharedPreferences get(Context c) {
        if (p == null) {
            p = c.getApplicationContext().getSharedPreferences("nota", Context.MODE_PRIVATE);
        }
        return p;
    }

    public static boolean getBool(Context c, String key, boolean def) {
        return get(c).getBoolean(key, def);
    }

    public static void setBool(Context c, String key, boolean value) {
        get(c).edit().putBoolean(key, value).apply();
    }

    public static int getInt(Context c, String key, int def) {
        return get(c).getInt(key, def);
    }

    public static void setInt(Context c, String key, int value) {
        get(c).edit().putInt(key, value).apply();
    }

    public static String getString(Context c, String key, String def) {
        return get(c).getString(key, def);
    }

    public static void setString(Context c, String key, String value) {
        get(c).edit().putString(key, value).apply();
    }
}
