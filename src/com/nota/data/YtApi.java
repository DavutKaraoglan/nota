package com.nota.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.nota.model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * YouTube as an on-demand catalogue, over the InnerTube endpoints its own apps use.
 *
 * <p>A search hit carries no stream URL: those are signed, expire within hours and are bound to
 * the address that asked for them, so they are resolved right before playback instead.
 */
public class YtApi {

    /** Marks a Track whose stream comes from the helper rather than from a local file. */
    public static final String PREFIX = "yt:";

    /**
     * Extraction runs on a server instead of the phone: YouTube stops feeding an app-built
     * request after about a minute, while the same fetch from a plain host still returns the
     * whole track. The helper answers ranged GETs and keeps what it fetched.
     *
     * <p>Its certificate is self-signed and pinned in the network security config, so this
     * address cannot move without a new APK carrying the new certificate. The address itself
     * lives in {@link Backend}, which is not part of the repository.
     */

    /**
     * Long enough that a link handed to MediaPlayer still works when the track is resumed after
     * a lunch break, short enough that one scraped from a build stops being useful.
     */
    private static final long LINK_LIFE_SEC = 12 * 60 * 60;

    /**
     * A playable URL for a YouTube track, or null when the id is not one. The query is signed
     * so that the helper answers this build and not every crawler that finds the address.
     */
    public static String streamUrl(Track track) {
        String id = videoId(track);
        if (id == null) return null;
        String query = "v=" + id + "&e=" + (System.currentTimeMillis() / 1000 + LINK_LIFE_SEC);
        return Backend.HOST + "/stream?" + query + "&s=" + sign(query);
    }

    private static String sign(String query) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    Backend.secret().getBytes("UTF-8"), "HmacSHA256"));
            byte[] sum = mac.doFinal(query.getBytes("UTF-8"));
            char[] hex = new char[sum.length * 2];
            for (int i = 0; i < sum.length; i++) {
                hex[i * 2] = HEX[(sum[i] >> 4) & 0xF];
                hex[i * 2 + 1] = HEX[sum[i] & 0xF];
            }
            return new String(hex);
        } catch (Exception e) {
            return "";
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * An id reaches a URL query and a cache file name unescaped, and it arrives from a parsed
     * answer rather than from the app, so anything but the eleven characters YouTube uses is
     * treated as a broken parse.
     */
    private static boolean validId(String id) {
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
    private static final String SEARCH_URL =
            "https://music.youtube.com/youtubei/v1/search?prettyPrint=false";
    private static final String NEXT_URL =
            "https://music.youtube.com/youtubei/v1/next?prettyPrint=false";
    /** Restricts search to songs, so albums, artists and playlist rows stay out of the list. */
    private static final String SONGS_ONLY = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D";
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

    // ---- mix ----

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

    // ---- streams ----

    public static boolean isYouTube(Track t) {
        return t != null && t.sourceId != null && t.sourceId.startsWith(PREFIX);
    }

    public static String videoId(Track t) {
        if (!isYouTube(t)) return null;
        String id = t.sourceId.substring(PREFIX.length());
        return validId(id) ? id : null;
    }

}
