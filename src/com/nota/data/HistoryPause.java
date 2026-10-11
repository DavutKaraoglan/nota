package com.nota.data;

import android.content.Context;

public class HistoryPause {

    public static final String KEY = "history_paused";

    public static boolean on(Context c) {
        return Prefs.getBool(c, KEY, false);
    }

    public static void set(Context c, boolean value) {
        Prefs.setBool(c, KEY, value);
    }
}
