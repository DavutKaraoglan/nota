package com.nota.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.nota.model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * YouTube as an on-demand catalogue, over the InnerTube endpoints its own apps use.
 *
 * <p>A search hit carries no stream URL: those are signed, expire within hours and are bound to
 * the address that asked for them, so they are resolved right before playback instead.
 */
public class YtApi {

    /** Marks a Track whose audio is pulled from YouTube rather than read from a local file. */
    public static final String PREFIX = "yt:";

    /**
     * An id reaches a URL query and a cache file name unescaped, and it arrives from a parsed
     * answer rather than from the app, so anything but the eleven characters YouTube uses is
     * treated as a broken parse.
     */
    static boolean validId(String id) {
        if (id == null || id.length() != 11) return false;
        for (int i = 0; i < id.length(); i++) {
            char ch = id.charAt(i);
            if ((ch < 'a' || ch > 'z') && (ch < 'A' || ch > 'Z') && (ch < '0' || ch > '9')
                    && ch != '-' && ch != '_') {
                return false;
            }
        }
        return true;
    }

    public interface TrackCallback {
        void onTracks(List<Track> tracks);

        void onError();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int TIMEOUT_MS = 20000;
    /** Results shift slowly; a search from this morning is still a fair answer tonight. */
    private static final long SEARCH_FRESH_MS = 6L * 60 * 60 * 1000;
    /** A mix is tied to one song rather than to a moment, so it keeps far longer than a search. */
    private static final long MIX_FRESH_MS = 7L * 24 * 60 * 60 * 1000;
    /** Nobody releases twice in a day, and the shelf is the first thing home draws. */
    private static final long RELEASE_FRESH_MS = 24L * 60 * 60 * 1000;
    private static final String SEARCH_URL =
            "https://music.youtube.com/youtubei/v1/search?prettyPrint=false";
    private static final String NEXT_URL =
            "https://music.youtube.com/youtubei/v1/next?prettyPrint=false";
    /** Restricts search to songs, so albums, artists and playlist rows stay out of the list. */
    private static final String SONGS_ONLY = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D";
    /**
     * The same search restricted to records rather than songs: albums, EPs and singles. Songs
     * carry no release date anywhere in the answer, and these rows carry the year, which is the
     * only way to tell what an artist put out this season from what they are known for.
     */
    private static final String RELEASES_ONLY = "EgWKAQIYAWoKEAkQBRAKEAMQBA%3D%3D";
    /**
     * YouTube Music's own web client. It answers with square cover art and with title, artist,
     * album and length as separate fields, where the plain YouTube clients only offer a frame
     * grabbed from the video and a title like "TARKAN - Kuzu Kuzu (Official Music Video)".
     *
     * <p>The full browser user agent is load-bearing: with a short one the server skips gzip and
     * the same answer arrives as 480 KB instead of 12 KB.
     */
    private static final Client YT_MUSIC = new Client("WEB_REMIX", "1.20240403.01.00",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/123.0 Safari/537.36");

    private static class Client {
        final String name, version, ua;

        Client(String name, String version, String ua) {
            this.name = name;
            this.version = version;
            this.ua = ua;
        }

        JSONObject context() throws Exception {
            JSONObject c = new JSONObject();
            c.put("clientName", name);
            c.put("clientVersion", version);
            c.put("userAgent", ua);
            c.put("hl", "tr");
            c.put("gl", "TR");
            return new JSONObject().put("client", c);
        }
    }

    // ---- search ----

    public static void search(Context c, final String term, final TrackCallback cb) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                final List<Track> out = searchBlocking(app, term);
                MAIN.post(new Runnable() {
                    public void run() {
                        if (out == null) cb.onError();
                        else cb.onTracks(out);
                    }
                });
            }
        }, "nota-yt-search").start();
    }

    /** Blocking: call it from a worker thread. Null means the search could not be answered. */
    public static List<Track> searchBlocking(Context app, String term) {
        Client c = YT_MUSIC;
        String body;
        try {
            body = new JSONObject()
                    .put("query", term)
                    .put("params", SONGS_ONLY)
                    .put("context", c.context())
                    .toString();
        } catch (Exception e) {
            return null;
        }
        String json = DiskCache.loadPost(app, "yt", "m:" + term, SEARCH_FRESH_MS,
                SEARCH_URL, body, c.ua);
        return json == null ? null : parseSearch(json);
    }

    private static List<Track> parseSearch(String json) {
        List<JSONObject> rows = new ArrayList<JSONObject>();
        try {
            collect(new JSONObject(json), "musicResponsiveListItemRenderer", rows);
        } catch (Exception e) {
            return null;
        }
        List<Track> out = new ArrayList<Track>(rows.size());
        for (JSONObject row : rows) {
            Track t = toTrack(row);
            if (t != null) out.add(t);
        }
        return out;
    }

    private static Track toTrack(JSONObject row) {
        JSONObject data = row.optJSONObject("playlistItemData");
        String id = data == null ? null : data.optString("videoId", "");
        if (!validId(id)) return null;
        String title = column(row, 0);
        if (TextUtils.isEmpty(title)) return null;

        Track t = new Track();
        t.type = Track.TYPE_ONLINE;
        t.sourceId = PREFIX + id;
        t.title = title;
        // The second column packs artist, album and length into one bulleted line.
        String[] meta = bullets(column(row, 1));
        if (meta.length > 0) t.artist = meta[0];
        if (meta.length >= 2) t.durationMs = parseLength(meta[meta.length - 1]);
        if (meta.length >= 3) t.album = meta[meta.length - 2];
        t.artUrl = thumbnail(row);
        return t;
    }

    // ---- new releases ----

    public static void latestRelease(Context c, final String artist, final TrackCallback cb) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                final List<Track> out = latestReleaseBlocking(app, artist);
                MAIN.post(new Runnable() {
                    public void run() {
                        if (out == null) cb.onError();
                        else cb.onTracks(out);
                    }
                });
            }
        }, "nota-yt-release").start();
    }

    /**
     * Songs from the newest record an artist has out. Two questions rather than one: the record
     * list is where the year lives, and the song list is where the playable ids live, so the
     * newest title found in the first is looked up again in the second.
     *
     * <p>Blocking. Null means the catalogue could not be reached; empty means it had nothing
     * recent under that exact name.
     */
    public static List<Track> latestReleaseBlocking(Context app, String artist) {
        if (TextUtils.isEmpty(artist)) return null;
        String body;
        try {
            body = new JSONObject()
                    .put("query", artist)
                    .put("params", RELEASES_ONLY)
                    .put("context", YT_MUSIC.context())
                    .toString();
        } catch (Exception e) {
            return null;
        }
        String json = DiskCache.loadPost(app, "yt", "rel:" + artist, RELEASE_FRESH_MS,
                SEARCH_URL, body, YT_MUSIC.ua);
        if (json == null) return null;

        List<JSONObject> rows = new ArrayList<JSONObject>();
        try {
            collect(new JSONObject(json), "musicResponsiveListItemRenderer", rows);
        } catch (Exception e) {
            return null;
        }

        String newest = null;
        int newestYear = 0;
        for (JSONObject row : rows) {
            String title = column(row, 0);
            if (TextUtils.isEmpty(title)) continue;
            // "Single • OZ Tarkan • 2026" is a different artist with the asked-for name inside
            // theirs, and it is always the newest thing in the list. Only an exact name counts.
            String[] meta = bullets(column(row, 1));
            if (!names(meta, artist)) continue;
            int year = year(meta);
            if (year > newestYear) {
                newestYear = year;
                newest = title;
            }
        }
        if (newest == null) return new ArrayList<Track>();

        List<Track> songs = searchBlocking(app, artist + " " + newest);
        if (songs == null) return null;
        List<Track> out = new ArrayList<Track>();
        for (Track t : songs) {
            if (t.artist != null && t.artist.equalsIgnoreCase(artist)) out.add(t);
        }
        return out;
    }

    private static boolean names(String[] meta, String artist) {
        for (String part : meta) {
            if (part.equalsIgnoreCase(artist)) return true;
        }
        return false;
    }

    /** The year sits last on the line, where a length would sit on a song row. */
    private static int year(String[] meta) {
        if (meta.length == 0) return 0;
        String last = meta[meta.length - 1];
        if (last.length() != 4) return 0;
        try {
            return Integer.parseInt(last);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- artist banner ----

    /** The same search restricted to artists, so the answer is a channel rather than a song. */
    private static final String ARTISTS_ONLY = "EgWKAQIgAWoKEAkQBRAKEAMQBA%3D%3D";
    private static final String BROWSE_URL =
            "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false";
    /** A banner outlives a record: an artist changes theirs about as often as they change label. */
    private static final long BANNER_FRESH_MS = 30L * 24 * 60 * 60 * 1000;

    public interface UrlCallback {
        /** Null when there is no picture to be had. */
        void onUrl(String url);
    }

    public static void artistBanner(Context c, final String artist, final UrlCallback cb) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                final String url = artistBannerBlocking(app, artist);
                MAIN.post(new Runnable() {
                    public void run() {
                        cb.onUrl(url);
                    }
                });
            }
        }, "nota-yt-banner").start();
    }

    /**
     * The wide picture from the top of an artist's own page. Two questions again: the search
     * knows which channel the name belongs to, and only the channel carries the banner.
     *
     * <p>Blocking. Null means the artist has none, or could not be reached.
     */
    public static String artistBannerBlocking(Context app, String artist) {
        String channel = artistChannel(app, artist);
        if (channel == null) return null;
        String body;
        try {
            body = new JSONObject()
                    .put("browseId", channel)
                    .put("context", YT_MUSIC.context())
                    .toString();
        } catch (Exception e) {
            return null;
        }
        String json = DiskCache.loadPost(app, "yt", "chan:" + channel, BANNER_FRESH_MS,
                BROWSE_URL, body, YT_MUSIC.ua);
        if (json == null) return null;
        List<JSONObject> heads = new ArrayList<JSONObject>();
        try {
            collect(new JSONObject(json), "musicImmersiveHeaderRenderer", heads);
        } catch (Exception e) {
            return null;
        }
        return heads.isEmpty() ? null : thumbnail(heads.get(0));
    }

    private static String artistChannel(Context app, String artist) {
        if (TextUtils.isEmpty(artist)) return null;
        String body;
        try {
            body = new JSONObject()
                    .put("query", artist)
                    .put("params", ARTISTS_ONLY)
                    .put("context", YT_MUSIC.context())
                    .toString();
        } catch (Exception e) {
            return null;
        }
        String json = DiskCache.loadPost(app, "yt", "who:" + artist, BANNER_FRESH_MS,
                SEARCH_URL, body, YT_MUSIC.ua);
        if (json == null) return null;
        List<JSONObject> rows = new ArrayList<JSONObject>();
        try {
            collect(new JSONObject(json), "musicResponsiveListItemRenderer", rows);
        } catch (Exception e) {
            return null;
        }
        for (JSONObject row : rows) {
            // Searching "Tarkan" also answers with the tribute channels; wearing the name
            // exactly is the whole test, as it is for a new release.
            if (!artist.equalsIgnoreCase(column(row, 0))) continue;
            String id = browseId(row);
            if (id != null) return id;
        }
        return null;
    }

    private static String browseId(JSONObject row) {
        JSONObject nav = row.optJSONObject("navigationEndpoint");
        JSONObject browse = nav == null ? null : nav.optJSONObject("browseEndpoint");
        String id = browse == null ? null : browse.optString("browseId", "");
        return id != null && id.startsWith("UC") ? id : null;
    }

    // ---- mix ----

    public static void mix(Context c, final String videoId, final TrackCallback cb) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                final List<Track> out = mixBlocking(app, videoId);
                MAIN.post(new Runnable() {
                    public void run() {
                        if (out == null) cb.onError();
                        else cb.onTracks(out);
                    }
                });
            }
        }, "nota-yt-mix").start();
    }

    /**
     * The radio YouTube Music builds around one song. It is the only outside opinion the app can
     * ask for, so it carries the cold start: a song nobody has played next to anything yet still
     * has fifty neighbours here.
     *
     * <p>Blocking. The seed itself comes back as the first entry and is dropped.
     */
    public static List<Track> mixBlocking(Context app, String videoId) {
        String body;
        try {
            body = new JSONObject()
                    .put("videoId", videoId)
                    .put("playlistId", "RDAMVM" + videoId)
                    .put("isAudioOnly", true)
                    .put("context", YT_MUSIC.context())
                    .toString();
        } catch (Exception e) {
            return null;
        }
        String json = DiskCache.loadPost(app, "yt", "mix:" + videoId, MIX_FRESH_MS,
                NEXT_URL, body, YT_MUSIC.ua);
        if (json == null) return null;

        List<JSONObject> rows = new ArrayList<JSONObject>();
        try {
            collect(new JSONObject(json), "playlistPanelVideoRenderer", rows);
        } catch (Exception e) {
            return null;
        }
        List<Track> out = new ArrayList<Track>(rows.size());
        for (JSONObject row : rows) {
            String id = row.optString("videoId", "");
            if (!validId(id) || id.equals(videoId)) continue;
            String title = text(row.optJSONObject("title"));
            if (TextUtils.isEmpty(title)) continue;
            Track t = new Track();
            t.type = Track.TYPE_ONLINE;
            t.sourceId = PREFIX + id;
            t.title = title;
            // shortBylineText is the artist alone; the long one appends view and like counts.
            t.artist = text(row.optJSONObject("shortBylineText"));
            t.durationMs = parseLength(text(row.optJSONObject("lengthText")));
            t.artUrl = thumbnail(row);
            out.add(t);
        }
        return out;
    }

    private static String column(JSONObject row, int index) {
        JSONArray cols = row.optJSONArray("flexColumns");
        JSONObject col = cols == null ? null : cols.optJSONObject(index);
        JSONObject r = col == null ? null
                : col.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        return r == null ? null : text(r.optJSONObject("text"));
    }

    private static String[] bullets(String line) {
        if (TextUtils.isEmpty(line)) return new String[0];
        String[] parts = line.split("•");
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts;
    }

    /** Renderer layouts differ per client, so hits are picked out of the tree by name. */
    private static void collect(Object node, String key, List<JSONObject> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject hit = o.optJSONObject(key);
            if (hit != null) {
                out.add(hit);
                return;
            }
            for (Iterator<String> it = o.keys(); it.hasNext(); ) collect(o.opt(it.next()), key, out);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), key, out);
        }
    }

    private static String text(JSONObject node) {
        if (node == null) return null;
        String simple = node.optString("simpleText", "");
        if (!TextUtils.isEmpty(simple)) return simple;
        JSONArray runs = node.optJSONArray("runs");
        if (runs == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r != null) sb.append(r.optString("text", ""));
        }
        return sb.toString();
    }

    /** Thumbnails run small to large; the last one carries the suffix ArtLoader rescales. */
    private static String thumbnail(JSONObject row) {
        JSONObject box = row.optJSONObject("thumbnail");
        JSONObject inner = box == null ? null : box.optJSONObject("musicThumbnailRenderer");
        if (inner != null) box = inner.optJSONObject("thumbnail");
        JSONArray list = box == null ? null : box.optJSONArray("thumbnails");
        if (list == null || list.length() == 0) return null;
        JSONObject best = list.optJSONObject(list.length() - 1);
        return best == null ? null : best.optString("url", null);
    }

    /** "4:02" or "1:02:30" to milliseconds. */
    private static long parseLength(String s) {
        if (TextUtils.isEmpty(s)) return 0;
        long total = 0;
        for (String part : s.trim().split(":")) {
            try {
                total = total * 60 + Long.parseLong(part.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return total * 1000;
    }

    public static boolean isYouTube(Track t) {
        return t != null && t.sourceId != null && t.sourceId.startsWith(PREFIX);
    }

    public static String videoId(Track t) {
        if (!isYouTube(t)) return null;
        String id = t.sourceId.substring(PREFIX.length());
        return validId(id) ? id : null;
    }

    // ---- streams ----

    /**
     * Long enough that a link signed when a queue was built still works when the listener comes
     * back to it, short enough that one copied out of the app stops working the same day.
     */
    private static final long LINK_LIFE_SEC = 12 * 60 * 60;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * A signed address for the helper that turns a video id into audio. The helper checks the
     * signature before it fetches anything, so knowing where it lives is not enough to hand it
     * ids of your own and make it work for you.
     */
    public static String streamUrl(String videoId) {
        if (!validId(videoId)) return null;
        String query = "v=" + videoId + "&e=" + (System.currentTimeMillis() / 1000
                + LINK_LIFE_SEC);
        return Backend.HOST + "/stream?" + query + "&s=" + sign(query);
    }

    private static String sign(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Backend.secret().getBytes("UTF-8"), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return out.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e);
        }
    }
}
