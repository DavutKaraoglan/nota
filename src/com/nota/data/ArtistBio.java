package com.nota.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Locale;

/**
 * Who the artist is, in the few sentences an encyclopedia opens with. MusicBrainz itself keeps
 * only a one-line disambiguation, but it points at the Wikidata entry, which names the article
 * written about them in every language it exists in — and Wikipedia hands out that opening on
 * its own.
 *
 * <p>The reader's own language is asked for first, English after it, and the answer is kept for
 * good: a life already lived does not read differently tomorrow.
 */
public class ArtistBio {

    private static final String KEY = "artist_bio:";
    private static final int TIMEOUT_MS = 8000;

    public interface Callback {
        void onBio(String text);
    }

    public static void lookup(Context c, final String artist, final Callback cb) {
        final Context app = c.getApplicationContext();
        final String key = KEY + artist.toLowerCase(Locale.ROOT);
        final String lang = language();
        String cached = Prefs.getString(app, key, null);
        if (cached != null) {
            if (cached.length() > 0) cb.onBio(cached);
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                final String text;
                try {
                    text = fetch(artist, lang);
                } catch (Exception e) {
                    // An answer that never arrived is not "no life", so nothing is written down.
                    return;
                }
                Prefs.setString(app, key, text);
                if (text.length() == 0) return;
                main.post(new Runnable() {
                    public void run() {
                        cb.onBio(text);
                    }
                });
            }
        }, "nota-artist-bio").start();
    }

    private static String fetch(String artist, String lang) throws Exception {
        JSONObject found = ArtistInfo.search(artist);
        String mbid = found == null ? null : found.optString("id", null);
        if (mbid == null) return "";
        String entity = ArtistPhoto.wikidataId(mbid);
        if (entity == null) return "";

        JSONObject links = sitelinks(entity, lang);
        if (links == null) return "";
        String text = summary(lang, links.optJSONObject(lang + "wiki"));
        if (text.length() == 0 && !lang.equals("en")) {
            text = summary("en", links.optJSONObject("enwiki"));
        }
        return text;
    }

    private static JSONObject sitelinks(String entity, String lang) throws Exception {
        String json = Net.get("https://www.wikidata.org/w/api.php?action=wbgetentities"
                + "&format=json&props=sitelinks&sitefilter=" + lang + "wiki%7Cenwiki"
                + "&ids=" + entity, TIMEOUT_MS);
        JSONObject entities = new JSONObject(json).optJSONObject("entities");
        JSONObject item = entities == null ? null : entities.optJSONObject(entity);
        return item == null ? null : item.optJSONObject("sitelinks");
    }

    /** What the article opens with: a paragraph at most, and the plain text of it. */
    private static String summary(String lang, JSONObject sitelink) throws Exception {
        String title = sitelink == null ? "" : sitelink.optString("title", "");
        if (title.length() == 0) return "";
        String json = Net.get("https://" + lang + ".wikipedia.org/api/rest_v1/page/summary/"
                + URLEncoder.encode(title, "UTF-8").replace("+", "%20"), TIMEOUT_MS);
        return new JSONObject(json).optString("extract", "");
    }

    /** The reader's language, where it is one a wiki could be named after. */
    private static String language() {
        String lang = Locale.getDefault().getLanguage();
        // It is going straight into a hostname, and the platform allows odd tags in a locale.
        return lang != null && lang.matches("[a-z]{2,3}") ? lang : "en";
    }
}
