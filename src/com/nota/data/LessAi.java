package com.nota.data;

import android.content.Context;

import com.nota.model.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class LessAi {

    public static final String KEY = "less_ai";

    private static final Pattern MARK = Pattern.compile(
            "\\b(ai|a\\.i\\.)[\\s-]+(cover|covers|version|song|songs|music|remix|generated|voice|vocals?|made)\\b"
                    + "|\\b(cover|version|remix|voice|sung|made)\\s+(by|with)?\\s*(an\\s+)?ai\\b"
                    + "|[(\\[]\\s*ai\\s*[)\\]]"
                    + "|#ai\\b"
                    + "|\\bsuno\\b|\\budio\\b"
                    + "|artificial intelligence"
                    + "|yapay zek[aâ]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static boolean on(Context c) {
        return Prefs.getBool(c, KEY, false);
    }

    public static void set(Context c, boolean value) {
        Prefs.setBool(c, KEY, value);
    }

    public static boolean looksAi(Track t) {
        return matches(t.title) || matches(t.artist) || matches(t.album);
    }

    private static boolean matches(String s) {
        return s != null && MARK.matcher(s).find();
    }

    public static List<Track> filter(Context c, List<Track> tracks) {
        if (tracks == null || !on(c)) return tracks;
        List<Track> out = new ArrayList<Track>(tracks.size());
        for (Track t : tracks) if (!t.isRemote() || !looksAi(t)) out.add(t);
        return out;
    }
}
