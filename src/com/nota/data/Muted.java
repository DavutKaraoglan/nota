package com.nota.data;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

/**
 * Artists the listener asked not to be suggested. Their songs stay searchable and playable —
 * this is about what the app offers unasked, not about what it will let them hear.
 */
public class Muted {

    private static final String KEY = "muted_artists";

    public static List<String> all(Context c) {
        List<String> out = new ArrayList<String>();
        String raw = Prefs.getString(c, KEY, "");
        for (String line : raw.split("\n")) if (line.length() > 0) out.add(line);
        return out;
    }

    /** Lower-cased, which is how a name off a track is compared against the list. */
    public static Set<String> keys(Context c) {
        Set<String> out = new HashSet<String>();
        for (String a : all(c)) out.add(a.toLowerCase(Locale.ROOT).trim());
        return out;
    }

    public static boolean has(Context c, String artist) {
        return artist != null && keys(c).contains(artist.toLowerCase(Locale.ROOT).trim());
    }

    /** Returns whether the artist is muted after the toggle. */
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
