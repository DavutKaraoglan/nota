package com.nota.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.nota.model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * On-demand music from the Internet Archive's open audio collection. A search returns items
 * (a single song or a whole concert); the file list of an item is fetched only when opened.
 */
public class ArchiveApi {

    /** A search hit. Its playable files are unknown until {@link #tracks} resolves them. */
    public static class Item {
        public String identifier;
        public String title;
        public String creator;
        public int year;

        public String artUrl() {
            return "https://archive.org/services/img/" + identifier;
        }

        /** A stand-in Track so search hits can be drawn by the ordinary track row. */
        public Track card() {
            Track t = new Track();
            t.type = Track.TYPE_ONLINE;
            t.sourceId = identifier;
            t.title = title;
            t.artist = creator;
            t.artUrl = artUrl();
            t.year = year;
            return t;
        }
    }

    public interface ItemCallback {
        void onItems(List<Item> items);

        void onError();
    }

    public interface TrackCallback {
        void onTracks(List<Track> tracks);

        void onError();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The catalogue grows, but a search from this morning is still a good answer tonight. */
    private static final long SEARCH_FRESH_MS = 12L * 60 * 60 * 1000;
    /** File lists of an uploaded item practically never change. */
    private static final long ITEM_FRESH_MS = 30L * 24 * 60 * 60 * 1000;

    /** Default browse list: the curated music collection instead of every uploaded audio file. */
    public static void popular(Context c, int rows, ItemCallback cb) {
        query(c, BASE + " AND collection:(audio_music)", rows, cb);
    }

    public static void search(Context c, String term, int rows, ItemCallback cb) {
        String clean = escape(term);
        if (clean.length() == 0) {
            popular(c, rows, cb);
            return;
        }
        query(c, BASE + " AND (title:(" + clean + ") OR creator:(" + clean + "))", rows, cb);
    }

    /** Restricted items answer the download URL with 401, so they must never reach the list. */
    private static final String BASE = "mediatype:(audio) AND format:(MP3)"
            + " AND NOT access-restricted-item:true";

    private static void query(Context c, final String query, final int rows,
                              final ItemCallback cb) {
        final Context app = c.getApplicationContext();
        final String url = "https://archive.org/advancedsearch.php?q=" + encode(query)
                + "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=year"
                + "&sort[]=downloads+desc&rows=" + rows + "&page=1&output=json";

        new Thread(new Runnable() {
            public void run() {
                String body = DiskCache.load(app, "archive", url, SEARCH_FRESH_MS, url);
                final List<Item> out = body == null ? null : parseItems(body);
                MAIN.post(new Runnable() {
                    public void run() {
                        if (out == null) cb.onError();
                        else cb.onItems(out);
                    }
                });
            }
        }, "nota-archive").start();
    }

    public static void tracks(Context c, final Item item, final TrackCallback cb) {
        final Context app = c.getApplicationContext();
        final String url = "https://archive.org/metadata/" + encode(item.identifier);
        new Thread(new Runnable() {
            public void run() {
                String body = DiskCache.load(app, "archive", url, ITEM_FRESH_MS, url);
                final List<Track> out = body == null ? null : parseTracks(item, body);
                MAIN.post(new Runnable() {
                    public void run() {
                        if (out == null || out.isEmpty()) cb.onError();
                        else cb.onTracks(out);
                    }
                });
            }
        }, "nota-archive").start();
    }

    private static List<Item> parseItems(String body) {
        JSONArray docs;
        try {
            docs = new JSONObject(body).getJSONObject("response").getJSONArray("docs");
        } catch (Exception e) {
            return null;
        }
        List<Item> out = new ArrayList<Item>();
        for (int i = 0; i < docs.length(); i++) {
            JSONObject o = docs.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("identifier", "");
            if (TextUtils.isEmpty(id)) continue;
            Item it = new Item();
            it.identifier = id;
            it.title = o.optString("title", id).trim();
            it.creator = firstOf(o.opt("creator"));
            it.year = o.optInt("year", 0);
            out.add(it);
        }
        return out;
    }

    private static List<Track> parseTracks(Item item, String body) {
        JSONArray files;
        try {
            files = new JSONObject(body).getJSONArray("files");
        } catch (Exception e) {
            return null;
        }
        List<Track> out = new ArrayList<Track>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            if (!f.optString("format", "").contains("MP3")) continue;
            if ("true".equals(f.optString("private", ""))) continue;
            String name = f.optString("name", "");
            if (TextUtils.isEmpty(name)) continue;

            Track t = new Track();
            t.type = Track.TYPE_ONLINE;
            t.sourceId = item.identifier + "/" + name;
            t.data = "https://archive.org/download/" + encode(item.identifier) + "/"
                    + encodePath(name);
            t.title = fileTitle(f, name);
            t.artist = item.creator;
            t.album = item.title;
            t.artUrl = item.artUrl();
            t.year = item.year;
            t.durationMs = seconds(f.optString("length", ""));
            t.sizeBytes = parseLong(f.optString("size", ""));
            t.trackNo = (int) parseLong(f.optString("track", ""));
            out.add(t);
        }
        return out;
    }

    private static String fileTitle(JSONObject f, String name) {
        String title = f.optString("title", "");
        if (!TextUtils.isEmpty(title)) return title;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Archive reports durations either as seconds or as "mm:ss" / "h:mm:ss". */
    private static long seconds(String s) {
        if (TextUtils.isEmpty(s)) return 0;
        try {
            if (s.indexOf(':') < 0) return (long) (Double.parseDouble(s) * 1000);
            long total = 0;
            for (String part : s.split(":")) total = total * 60 + (long) Double.parseDouble(part);
            return total * 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String firstOf(Object creator) {
        if (creator instanceof JSONArray) {
            JSONArray a = (JSONArray) creator;
            return a.length() == 0 ? null : a.optString(0, null);
        }
        String s = creator == null ? null : creator.toString();
        return TextUtils.isEmpty(s) ? null : s;
    }

    /** Lucene treats these as syntax; a stray quote or colon turns a search into an error. */
    private static String escape(String term) {
        return term.replaceAll("[\\\\+\\-!(){}\\[\\]^\"~*?:/]", " ").trim();
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** Slashes must survive, so a file name is escaped segment by segment. */
    private static String encodePath(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("/")) {
            if (sb.length() > 0) sb.append('/');
            sb.append(encode(part).replace("+", "%20"));
        }
        return sb.toString();
    }
}
