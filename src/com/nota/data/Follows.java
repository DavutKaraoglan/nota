package com.nota.data;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Artists the listener asked to keep an eye on, newest first. */
public class Follows {

    private static final String KEY = "followed_artists";

    public static List<String> all(Context c) {
        List<String> out = new ArrayList<String>();
        String raw = Prefs.getString(c, KEY, "");
        for (String line : raw.split("\n")) if (line.length() > 0) out.add(line);
        return out;
    }

    public static boolean has(Context c, String artist) {
        for (String a : all(c)) if (a.equalsIgnoreCase(artist)) return true;
        return false;
    }

    /** Returns the state after the toggle. */
    public static boolean toggle(Context c, String artist) {
        List<String> kept = new ArrayList<String>();
        boolean removed = false;
        for (String a : all(c)) {
            if (a.equalsIgnoreCase(artist)) removed = true;
            else kept.add(a);
        }
        if (!removed) kept.add(0, artist);
        StringBuilder sb = new StringBuilder();
        for (String a : kept) sb.append(a).append('\n');
        Prefs.setString(c, KEY, sb.toString());
        return !removed;
    }
}
