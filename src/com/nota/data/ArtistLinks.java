package com.nota.data;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where else an artist can be read about: their own site, their channel, the places they post.
 * MusicBrainz keeps these as relations on the artist, so one request buys the whole row.
 *
 * <p>Only the kinds a listener would recognise are kept, named after the site rather than the
 * relation, and the answer is written down for good: a homepage is not news that turns stale.
 */
public class ArtistLinks {

    private static final String KEY = "artist_links:";
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX = 6;
    private static final char FIELD = '\u0001';

    /** The relation kinds worth a chip, in the order they should be offered. */
    private static final String[] KINDS = {
            "official homepage", "youtube", "social network", "wikipedia",
            "free streaming", "streaming"};

    public static class Link {
        public final String label;
        public final String url;

        Link(String label, String url) {
            this.label = label;
            this.url = url;
        }
    }

    public interface Callback {
        void onLinks(List<Link> links);
    }

    public static void lookup(Context c, final String artist, final Callback cb) {
        final Context app = c.getApplicationContext();
        final String key = KEY + artist.toLowerCase(Locale.ROOT);
        String cached = Prefs.getString(app, key, null);
        if (cached != null) {
            List<Link> links = parse(cached);
            if (!links.isEmpty()) cb.onLinks(links);
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                final String stored;
                try {
                    stored = store(fetch(artist));
                } catch (Exception e) {
                    // An answer that never arrived is not "no links", so nothing is written down.
                    return;
                }
                Prefs.setString(app, key, stored);
                final List<Link> links = parse(stored);
                if (links.isEmpty()) return;
                main.post(new Runnable() {
                    public void run() {
                        cb.onLinks(links);
                    }
                });
            }
        }, "nota-artist-links").start();
    }

    private static List<Link> fetch(String artist) throws Exception {
        JSONObject found = ArtistInfo.search(artist);
        String mbid = found == null ? null : found.optString("id", null);
        List<Link> out = new ArrayList<Link>();
        if (mbid == null) return out;
        String json = Net.get("https://musicbrainz.org/ws/2/artist/"
                + URLEncoder.encode(mbid, "UTF-8") + "?fmt=json&inc=url-rels", TIMEOUT_MS);
        JSONArray rels = new JSONObject(json).optJSONArray("relations");
        if (rels == null) return out;

        Set<String> taken = new HashSet<String>();
        for (String kind : KINDS) {
            for (int i = 0; i < rels.length() && out.size() < MAX; i++) {
                JSONObject rel = rels.optJSONObject(i);
                if (rel == null || !kind.equals(rel.optString("type"))) continue;
                JSONObject url = rel.optJSONObject("url");
                String resource = url == null ? "" : url.optString("resource", "");
                if (!resource.startsWith("http")) continue;
                String label = label(resource);
                if (label == null || !taken.add(label)) continue;
                out.add(new Link(label, resource));
            }
        }
        return out;
    }

    /** The site behind a link, said the way its own users say it. */
    private static String label(String url) {
        String host = Uri.parse(url).getHost();
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);
        if (host.endsWith("wikipedia.org")) return "Wikipedia";
        if (host.equals("music.youtube.com")) return "YouTube Music";
        if (host.equals("youtube.com") || host.equals("youtu.be")) return "YouTube";
        if (host.equals("instagram.com")) return "Instagram";
        if (host.equals("twitter.com") || host.equals("x.com")) return "X";
        if (host.equals("facebook.com")) return "Facebook";
        if (host.equals("open.spotify.com")) return "Spotify";
        if (host.equals("music.apple.com")) return "Apple Music";
        if (host.equals("deezer.com")) return "Deezer";
        if (host.equals("tidal.com")) return "Tidal";
        if (host.equals("soundcloud.com")) return "SoundCloud";
        if (host.equals("bandcamp.com") || host.endsWith(".bandcamp.com")) return "Bandcamp";
        if (host.equals("tiktok.com")) return "TikTok";
        if (host.equals("vk.com")) return "VK";
        return host;
    }

    private static String store(List<Link> links) {
        StringBuilder sb = new StringBuilder();
        for (Link l : links) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l.label).append(FIELD).append(l.url);
        }
        return sb.toString();
    }

    private static List<Link> parse(String stored) {
        List<Link> out = new ArrayList<Link>();
        if (stored.length() == 0) return out;
        for (String line : stored.split("\n")) {
            int cut = line.indexOf(FIELD);
            if (cut > 0) out.add(new Link(line.substring(0, cut), line.substring(cut + 1)));
        }
        return out;
    }
}
