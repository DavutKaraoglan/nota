package com.nota.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Locale;

/**
 * A picture of an artist the catalogue keeps no banner for. MusicBrainz knows which person or
 * band a name stands for and points at their Wikidata entry, which names a photograph on
 * Wikimedia Commons — freely licensed, so it can simply be shown.
 *
 * <p>Three questions for one picture, so the answer is kept for good, including the empty one:
 * plenty of artists have no free photograph, and that does not change from one day to the next.
 */
public class ArtistPhoto {

    private static final String KEY = "artist_photo:";
    private static final int TIMEOUT_MS = 8000;

    public interface Callback {
        void onUrl(String url);
    }

    /**
     * @param width how wide the picture is wanted; Commons resizes before sending it
     */
    public static void lookup(Context c, final String artist, final int width,
                              final Callback cb) {
        final Context app = c.getApplicationContext();
        final String key = KEY + artist.toLowerCase(Locale.ROOT);
        String cached = Prefs.getString(app, key, null);
        if (cached != null) {
            cb.onUrl(cached.length() == 0 ? null : filePath(cached, width));
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                final String file;
                try {
                    file = fetch(artist);
                } catch (Exception e) {
                    // Nothing is written down: an answer that never arrived is not "no picture".
                    return;
                }
                Prefs.setString(app, key, file);
                if (file.length() == 0) return;
                main.post(new Runnable() {
                    public void run() {
                        cb.onUrl(filePath(file, width));
                    }
                });
            }
        }, "nota-artist-photo").start();
    }

    /** The name of the file on Commons, or empty where the trail ends. */
    private static String fetch(String artist) throws Exception {
        JSONObject found = ArtistInfo.search(artist);
        String mbid = found == null ? null : found.optString("id", null);
        if (mbid == null) return "";
        String entity = wikidataId(mbid);
        if (entity == null) return "";
        String file = image(entity);
        return file == null ? "" : file;
    }

    static String wikidataId(String mbid) throws Exception {
        String json = Net.get("https://musicbrainz.org/ws/2/artist/"
                + URLEncoder.encode(mbid, "UTF-8") + "?fmt=json&inc=url-rels", TIMEOUT_MS);
        JSONArray rels = new JSONObject(json).optJSONArray("relations");
        if (rels == null) return null;
        for (int i = 0; i < rels.length(); i++) {
            JSONObject rel = rels.optJSONObject(i);
            if (rel == null || !"wikidata".equals(rel.optString("type"))) continue;
            JSONObject url = rel.optJSONObject("url");
            String resource = url == null ? "" : url.optString("resource", "");
            String id = resource.substring(resource.lastIndexOf('/') + 1);
            // The entry is going into a query of ours next, and it arrived from elsewhere.
            if (id.matches("Q[0-9]+")) return id;
        }
        return null;
    }

    /** P18 is the property Wikidata files an entry's photograph under. */
    private static String image(String entity) throws Exception {
        String json = Net.get("https://www.wikidata.org/w/api.php?action=wbgetclaims"
                + "&format=json&property=P18&entity=" + entity, TIMEOUT_MS);
        JSONObject claims = new JSONObject(json).optJSONObject("claims");
        JSONArray p18 = claims == null ? null : claims.optJSONArray("P18");
        JSONObject first = p18 == null || p18.length() == 0 ? null : p18.optJSONObject(0);
        JSONObject snak = first == null ? null : first.optJSONObject("mainsnak");
        JSONObject value = snak == null ? null : snak.optJSONObject("datavalue");
        String file = value == null ? null : value.optString("value", "");
        return file == null || file.length() == 0 ? null : file;
    }

    private static String filePath(String file, int width) {
        try {
            return "https://commons.wikimedia.org/wiki/Special:FilePath/"
                    + URLEncoder.encode(file, "UTF-8").replace("+", "%20")
                    + "?width=" + width;
        } catch (Exception e) {
            return null;
        }
    }
}
