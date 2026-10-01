package com.nota.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * What an artist is called besides their stage name: the latin reading of a Japanese or Chinese
 * name, the legal name behind an alias, and the one-line description MusicBrainz keeps.
 */
public class ArtistInfo {

    /** Facts about a person do not change, so an answer is kept for good — including "nothing". */
    private static final String KEY = "artist_info:";
    private static final int TIMEOUT_MS = 8000;
    /** Below this the search engine is guessing, and a wrong artist is worse than no line. */
    private static final int MIN_SCORE = 90;

    public interface Callback {
        void onInfo(String line);
    }

    public static void lookup(final Context c, final String artist, final Callback cb) {
        final Context app = c.getApplicationContext();
        String cached = Prefs.getString(app, KEY + artist.toLowerCase(Locale.ROOT), null);
        if (cached != null) {
            cb.onInfo(cached);
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                String line;
                try {
                    line = fetch(artist);
                } catch (Exception e) {
                    return;
                }
                Prefs.setString(app, KEY + artist.toLowerCase(Locale.ROOT), line);
                final String result = line;
                main.post(new Runnable() {
                    public void run() {
                        cb.onInfo(result);
                    }
                });
            }
        }, "nota-artist-info").start();
    }

    /** The record behind a name, or null where the search is only guessing at it. */
    static JSONObject search(String artist) throws Exception {
        // The name comes from a file tag, and a quote or a backslash in it would end the
        // quoted term and let the rest of the tag act as search syntax.
        String term = artist.replaceAll("[\"\\\\]", " ").trim();
        if (term.length() == 0) return null;
        String url = "https://musicbrainz.org/ws/2/artist?fmt=json&limit=1&query="
                + java.net.URLEncoder.encode("artist:\"" + term + "\"", "UTF-8");
        JSONArray found = new JSONObject(Net.get(url, TIMEOUT_MS)).optJSONArray("artists");
        if (found == null || found.length() == 0) return null;
        JSONObject a = found.getJSONObject(0);
        return a.optInt("score") < MIN_SCORE ? null : a;
    }

    private static String fetch(String artist) throws Exception {
        JSONObject a = search(artist);
        if (a == null) return "";

        StringBuilder out = new StringBuilder();
        String name = a.optString("name", artist);
        if (!isLatin(name)) {
            String latin = latinAlias(a.optJSONArray("aliases"));
            if (latin == null) latin = readable(a.optString("sort-name", ""));
            if (latin != null && latin.length() > 0) out.append(latin);
        }
        String legal = aliasOfType(a.optJSONArray("aliases"), "Legal name");
        if (legal != null && !legal.equalsIgnoreCase(name) && !legal.equalsIgnoreCase(artist)) {
            if (out.length() > 0) out.append(" · ");
            out.append(legal);
        }
        String about = a.optString("disambiguation", "");
        if (about.length() > 0) {
            if (out.length() > 0) out.append(" · ");
            out.append(about);
        }
        return out.toString();
    }

    /** MusicBrainz sorts people as "Yonezu, Kenshi"; a header wants it the way it is spoken. */
    private static String readable(String sortName) {
        int comma = sortName.indexOf(", ");
        return comma < 0 ? sortName
                : sortName.substring(comma + 2) + " " + sortName.substring(0, comma);
    }

    private static String latinAlias(JSONArray aliases) {
        if (aliases == null) return null;
        String fallback = null;
        for (int i = 0; i < aliases.length(); i++) {
            JSONObject al = aliases.optJSONObject(i);
            if (al == null) continue;
            String locale = al.optString("locale", "");
            String value = al.optString("name", "");
            if (value.length() == 0 || !isLatin(value)) continue;
            if (locale.startsWith("en") && al.optBoolean("primary")) return value;
            if (fallback == null && (locale.startsWith("en") || locale.contains("Latn"))) {
                fallback = value;
            }
        }
        return fallback;
    }

    private static String aliasOfType(JSONArray aliases, String type) {
        if (aliases == null) return null;
        for (int i = 0; i < aliases.length(); i++) {
            JSONObject al = aliases.optJSONObject(i);
            if (al != null && type.equals(al.optString("type"))) return al.optString("name");
        }
        return null;
    }

    /** Everything past the CJK radicals block is kana, hangul or han — a name that needs a reading. */
    private static boolean isLatin(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x2E7F) return false;
        }
        return true;
    }
}
