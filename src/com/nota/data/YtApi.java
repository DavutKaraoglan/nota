package com.nota.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.nota.model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

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
    /** Where a song's release day is kept, so it is asked for once and not once a screen. */
    private static final String AGE_KEY = "yt_out_";
    private static final int TIMEOUT_MS = 20000;
    /** Results shift slowly; a search from this morning is still a fair answer tonight. */
    private static final long SEARCH_FRESH_MS = 6L * 60 * 60 * 1000;
    /** A mix is tied to one song rather than to a moment, so it keeps far longer than a search. */
    private static final long MIX_FRESH_MS = 7L * 24 * 60 * 60 * 1000;
    /** Nobody releases twice in a day, and the shelf is the first thing home draws. */
    private static final long RELEASE_FRESH_MS = 24L * 60 * 60 * 1000;
    /**
     * How recent a record has to be to count as new. An artist always has a newest record, and
     * without this the shelf would announce a song from last spring as though it had just landed.
     */
    private static final long FRESH_WINDOW_MS = 90L * 24 * 60 * 60 * 1000;
    private static final String SEARCH_URL =
            "https://music.youtube.com/youtubei/v1/search?prettyPrint=false";
    private static final String NEXT_URL =
            "https://music.youtube.com/youtubei/v1/next?prettyPrint=false";
    private static final String WATCH_NEXT_URL =
            "https://www.youtube.com/youtubei/v1/next?prettyPrint=false";
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
                    + "Chrome/123.0 Safari/537.36", "tr", "TR");
    /**
     * Plain YouTube, asked in English because the only thing wanted of it is a release date and
     * it states one as a phrase — "3 weeks ago" — that has to be read back.
     */
    private static final Client YT_WATCH = new Client("WEB", "2.20240403.01.00",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/123.0 Safari/537.36", "en", "US");

    private static class Client {
        final String name, version, ua, hl, gl;

        Client(String name, String version, String ua, String hl, String gl) {
            this.name = name;
            this.version = version;
            this.ua = ua;
            this.hl = hl;
            this.gl = gl;
        }

        JSONObject context() throws Exception {
            JSONObject c = new JSONObject();
            c.put("clientName", name);
            c.put("clientVersion", version);
            c.put("userAgent", ua);
            c.put("hl", hl);
            c.put("gl", gl);
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

    /**
     * The newest song of each of the given artists, in the order asked, leaving out the ones
     * whose newest is not recent. An artist the catalogue cannot answer for is left out too
     * rather than failing the row: one silent name should not cost the others their line.
     */
    public static void latestReleases(Context c, final List<String> artists,
                                      final TrackCallback cb) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                final List<Track> out = new ArrayList<Track>();
                for (String artist : artists) {
                    List<Track> songs = latestReleaseBlocking(app, artist);
                    if (songs != null && !songs.isEmpty()) out.add(songs.get(0));
                }
                MAIN.post(new Runnable() {
                    public void run() {
                        cb.onTracks(out);
                    }
                });
            }
        }, "nota-yt-release").start();
    }

    /**
     * Songs from the newest record an artist has out, and only while that record is still news.
     * Two questions rather than one: the record list is where the year lives, and the song list
     * is where the playable ids live, so the newest title found in the first is looked up again
     * in the second.
     *
     * <p>Blocking. Null means the catalogue could not be reached; empty means it had nothing
     * recent under that exact name.
     */
    private static List<Track> latestReleaseBlocking(Context app, String artist) {
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
        // A record list is sorted by nothing but what the artist has, so its newest entry can be
        // years old. The year alone cannot separate this month's single from last January's, and
        // that is the difference the shelf is about, so the year only rules out what is plainly
        // too old to be worth a second question.
        if (newestYear < thisYear() - 1) return new ArrayList<Track>();

        List<Track> songs = searchBlocking(app, artist + " " + newest);
        if (songs == null) return null;
        List<Track> out = new ArrayList<Track>();
        for (Track t : songs) {
            if (t.artist != null && t.artist.equalsIgnoreCase(artist)) out.add(t);
        }
        if (out.isEmpty()) return out;
        long age = ageMs(app, videoId(out.get(0)));
        if (age < 0 || age > FRESH_WINDOW_MS) return new ArrayList<Track>();
        return out;
    }

    private static int thisYear() {
        return Calendar.getInstance().get(Calendar.YEAR);
    }

    /**
     * How long ago the video went up, or -1 when YouTube would not say. The song rows carry no
     * date at all, and the record rows carry only a year, so the date is asked of the watch page
     * the way a browser would see it.
     *
     * <p>Kept once found, and kept for good: the shelf is rebuilt whenever anything is played or
     * favourited, and the day a song came out is the one thing about it that never changes.
     */
    private static long ageMs(Context app, String videoId) {
        if (!validId(videoId)) return -1;
        long known = Prefs.getLong(app, AGE_KEY + videoId, 0);
        if (known > 0) return System.currentTimeMillis() - known;
        String answer;
        try {
            String body = new JSONObject()
                    .put("videoId", videoId)
                    .put("context", YT_WATCH.context())
                    .toString();
            answer = Net.post(WATCH_NEXT_URL, body, YT_WATCH.ua, TIMEOUT_MS);
        } catch (Exception e) {
            return -1;
        }
        List<JSONObject> heads = new ArrayList<JSONObject>();
        try {
            collect(new JSONObject(answer), "videoPrimaryInfoRenderer", heads);
        } catch (Exception e) {
            return -1;
        }
        if (heads.isEmpty()) return -1;
        long age = spoken(text(heads.get(0).optJSONObject("relativeDateText")));
        if (age < 0) return -1;
        Prefs.setLong(app, AGE_KEY + videoId, System.currentTimeMillis() - age);
        return age;
    }

    /** "3 weeks ago" as milliseconds. The client asks in English so this has one wording to read. */
    private static long spoken(String said) {
        if (TextUtils.isEmpty(said)) return -1;
        String[] words = said.trim().split(" ");
        if (words.length < 2) return -1;
        long count;
        try {
            count = Long.parseLong(words[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
        long day = 24L * 60 * 60 * 1000;
        String unit = words[1];
        if (unit.startsWith("second")) return count * 1000;
        if (unit.startsWith("minute")) return count * 60 * 1000;
        if (unit.startsWith("hour")) return count * 60 * 60 * 1000;
        if (unit.startsWith("day")) return count * day;
        if (unit.startsWith("week")) return count * 7 * day;
        if (unit.startsWith("month")) return count * 30 * day;
        if (unit.startsWith("year")) return count * 365 * day;
        return -1;
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

    // ---- moods ----

    /** Enough rows to fill the screen a mood takes over, from either side of the merge. */
    private static final int MOOD_MAX = 40;

    public static void mood(Context c, final String mood, final String phrase,
                            final List<String> artists, final TrackCallback cb) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                final List<Track> out = moodBlocking(app, mood, phrase, artists);
                MAIN.post(new Runnable() {
                    public void run() {
                        if (out == null) cb.onError();
                        else cb.onTracks(out);
                    }
                });
            }
        }, "nota-yt-mood").start();
    }

    /**
     * A mood as this listener would hear it: the catalogue is asked for the mood beside the names
     * they play most, and the answers are dealt out in turn so no one name takes the shelf. The
     * mood on its own is not asked at all — that answer is the same chart for everybody, and a
     * shelf of strangers is not what a mood on this screen is for.
     *
     * <p>The bare phrase is the one thing left for a listener who has played nothing yet, since
     * there is no listening to narrow a mood down to.
     *
     * <p>Blocking. Null means nothing could be reached.
     */
    public static List<Track> moodBlocking(Context app, String mood, String phrase,
                                           List<String> artists) {
        List<Cursor> mine = new ArrayList<Cursor>();
        for (String artist : artists) {
            if (TextUtils.isEmpty(artist)) continue;
            List<Track> found = searchBlocking(app, artist + " " + mood);
            if (found != null && !found.isEmpty()) mine.add(new Cursor(found));
        }
        if (mine.isEmpty()) return searchBlocking(app, phrase);

        List<Track> out = new ArrayList<Track>();
        Set<String> seen = new HashSet<String>();
        while (out.size() < MOOD_MAX) {
            boolean grew = false;
            // One song from each answer per round, so the shelf reads as a mix of the names they
            // play rather than as one of them followed by another.
            for (Cursor c : mine) grew |= c.moveOne(out, seen);
            if (!grew) break;
        }
        return out;
    }

    /** One answer being merged with the others, and how far through it the merge has got. */
    private static final class Cursor {
        private final List<Track> list;
        private int at;

        Cursor(List<Track> list) {
            this.list = list;
        }

        boolean moveOne(List<Track> out, Set<String> seen) {
            while (at < list.size()) {
                Track t = list.get(at++);
                if (seen.add(t.key())) {
                    out.add(t);
                    return true;
                }
            }
            return false;
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
     * The address of the song's audio, asked of YouTube directly.
     *
     * <p>Blocking: call it from a worker thread. The answer is remembered until it expires, so
     * the cost is paid once per song rather than once per range the player asks for.
     */
    public static String streamUrl(Context app, String videoId) {
        return YtStream.url(app, videoId);
    }

    /** Settles who the app is with YouTube before a song needs the answer. Blocking. */
    public static void warmSession(Context app) {
        YtStream.warmSession(app);
    }
}
