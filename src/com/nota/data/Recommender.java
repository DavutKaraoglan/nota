package com.nota.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.nota.model.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Answers "what comes after this song". Everything it knows is behavioural — this catalogue
 * offers no genre, tempo or mood — so a candidate earns its place by having followed the seed
 * before, by sitting next to it on the device, or by being in the radio YouTube Music builds
 * around it.
 */
public class Recommender {

    public interface Callback {
        void onTracks(List<Track> tracks);
    }

    /** A pairing the listener made themselves outranks anything guessed for them. */
    private static final float W_PAIRING = 0.45f;
    private static final float W_MIX = 0.32f;
    /** Same artist or same folder on the device. */
    private static final float W_SHELF = 0.26f;
    private static final float W_LOVED = 0.20f;
    /** Nothing on the device relates to the seed, so anything playable beats silence. */
    private static final float W_LAST_RESORT = 0.05f;
    /** Applied on top of a candidate's own claim, never in place of one. */
    private static final float W_AFFINITY = 0.30f;
    private static final float W_EXPLORE = 0.15f;
    /** Heard lately, so it would feel like a loop rather than a continuation. */
    private static final float P_RECENT = 0.5f;
    /** Enough favourites to describe a taste, few enough that each one still costs one mix. */
    private static final int TASTE_SEEDS = 4;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Random RND = new Random();

    /**
     * @param inQueue tracks already lined up, which must not be offered again
     * @param want    how many to return
     */
    public static void nextFor(Context c, final Track seed, List<Track> inQueue, final int want,
                               final Callback cb) {
        final Context app = c.getApplicationContext();
        final Set<String> avoid = new HashSet<String>();
        avoid.add(seed.key());
        if (inQueue != null) for (Track t : inQueue) avoid.add(t.key());

        new Thread(new Runnable() {
            public void run() {
                List<Track> found;
                try {
                    found = build(app, seed, avoid, want);
                } catch (Exception e) {
                    // The caller holds a "request in flight" flag, so silence would strand it.
                    found = new ArrayList<Track>();
                }
                final List<Track> out = found;
                MAIN.post(new Runnable() {
                    public void run() {
                        cb.onTracks(out);
                    }
                });
            }
        }, "nota-recommend").start();
    }

    /**
     * Suggestions for the listener rather than for a moment: instead of following one song it
     * pools what surrounds the handful of tracks they return to, so a single late-night detour
     * cannot speak for the whole shelf.
     */
    public static void forTaste(Context c, final int want, final Callback cb) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                List<Track> found;
                try {
                    found = buildTaste(app, want);
                } catch (Exception e) {
                    found = new ArrayList<Track>();
                }
                final List<Track> out = found;
                MAIN.post(new Runnable() {
                    public void run() {
                        cb.onTracks(out);
                    }
                });
            }
        }, "nota-taste").start();
    }

    private static class Candidate {
        final Track track;
        float score;

        Candidate(Track track, float score) {
            this.track = track;
            this.score = score;
        }
    }

    private static List<Track> build(Context app, Track seed, Set<String> avoid, int want) {
        Db db = Db.get(app);
        // Offline the device library is not a fallback but the whole catalogue, and a streamed
        // candidate would only queue up a song that cannot be played.
        boolean online = Connectivity.isOnline(app);
        Map<String, Candidate> pool = new LinkedHashMap<String, Candidate>();
        collect(app, db, seed, pool, avoid, online);
        if (pool.size() < want) loved(app, db, pool, avoid, online);

        if (pool.isEmpty() && !online) {
            // Nothing on the device relates to this song, so any of it beats silence.
            for (Track t : MediaLibrary.get().tracks()) offer(pool, avoid, false, t, W_LAST_RESORT);
        }
        return rank(app, db, pool, want, false);
    }

    private static List<Track> buildTaste(Context app, int want) {
        Db db = Db.get(app);
        boolean online = Connectivity.isOnline(app);
        List<Track> seeds = tasteSeeds(app, db);
        // The favourites speak for themselves elsewhere on the screen; here they are only the
        // question, so they are kept out of the answer.
        Set<String> avoid = new HashSet<String>();
        for (Track t : seeds) avoid.add(t.key());

        Map<String, Candidate> pool = new LinkedHashMap<String, Candidate>();
        for (Track seed : seeds) collect(app, db, seed, pool, avoid, online);
        // A shelf is read as a list of names, so a second song by an artist already on it reads
        // as an album track rather than as a suggestion. The queue can repeat an artist; this
        // cannot.
        return rank(app, db, pool, want, true);
    }

    /**
     * A last line so the queue never runs dry. These songs answer nothing about the seed, so they
     * are asked for only once what does relate to it has run out.
     */
    private static void loved(Context app, Db db, Map<String, Candidate> pool, Set<String> avoid,
                              boolean online) {
        LinkedHashMap<String, Integer> loved = db.topScored(24);
        if (loved.isEmpty()) return;
        float top = Collections.max(loved.values());
        for (Track t : MediaLibrary.resolve(app, new ArrayList<String>(loved.keySet()))) {
            Integer s = loved.get(t.key());
            offer(pool, avoid, online, t, W_LOVED * (s == null ? 0f : s / top));
        }
    }

    /** The songs the listener keeps coming back to, one per artist so no favourite crowds it. */
    private static List<Track> tasteSeeds(Context app, Db db) {
        List<Track> out = new ArrayList<Track>();
        Set<String> artists = new HashSet<String>();
        for (Track t : MediaLibrary.resolve(app, new ArrayList<String>(db.topScored(40).keySet()))) {
            String artist = artistKey(t);
            if (artist != null && !artists.add(artist)) continue;
            out.add(t);
            if (out.size() >= TASTE_SEEDS) break;
        }
        return out;
    }

    private static void collect(Context app, Db db, Track seed, Map<String, Candidate> pool,
                                Set<String> avoid, boolean online) {
        LinkedHashMap<String, Integer> pairs = db.nextKeys(seed.key(), 12);
        if (!pairs.isEmpty()) {
            float top = Collections.max(pairs.values());
            for (Track t : MediaLibrary.resolve(app, new ArrayList<String>(pairs.keySet()))) {
                Integer w = pairs.get(t.key());
                offer(pool, avoid, online, t, W_PAIRING * (w == null ? 0f : w / top));
            }
        }

        for (Track t : shelf(seed)) offer(pool, avoid, online, t, W_SHELF);

        // Online the seed itself gets to say what follows it, whether it came from the device or
        // from the catalogue. Offline the question cannot be asked at all.
        if (online) {
            List<Track> mix = mixFor(app, seed);
            for (int i = 0; mix != null && i < mix.size(); i++) {
                // The radio is already ordered by relevance; the tail is the loose end of it.
                offer(pool, avoid, true, mix.get(i), W_MIX * (1f - 0.3f * i / mix.size()));
            }
        }
    }

    private static List<Track> rank(Context app, Db db, Map<String, Candidate> pool, int want,
                                    boolean onePerArtist) {
        Map<String, Float> affinity = artistAffinity(app, db);
        Set<String> recent = new HashSet<String>(db.recentKeys(20));
        Set<String> muted = Muted.keys(app);
        List<Candidate> ranked = new ArrayList<Candidate>(pool.values());
        // Dropped here rather than where they were gathered: a muted name is a claim about what
        // may be suggested, and this is the one place everything suggested passes through.
        for (Iterator<Candidate> it = ranked.iterator(); it.hasNext(); ) {
            if (muted.contains(artistKey(it.next().track))) it.remove();
        }
        for (Candidate c : ranked) {
            Float a = affinity.get(artistKey(c.track));
            // Taste only tilts the order among songs that already answer the seed. Added on top
            // it used to outweigh the relation itself, which is how a favourite from somewhere
            // else entirely ended up next in the queue.
            float tilt = 1f + W_AFFINITY * (a == null ? 0f : a) + W_EXPLORE * RND.nextFloat();
            if (recent.contains(c.track.key())) tilt -= P_RECENT;
            c.score *= tilt;
        }
        Collections.sort(ranked, new Comparator<Candidate>() {
            public int compare(Candidate x, Candidate y) {
                return Float.compare(y.score, x.score);
            }
        });
        return spread(ranked, want, onePerArtist);
    }

    private static void offer(Map<String, Candidate> pool, Set<String> avoid, boolean online,
                              Track t, float score) {
        if (t == null || TextUtils.isEmpty(t.title)) return;
        if (!online && t.isRemote()) return;
        String key = t.key();
        if (avoid.contains(key)) return;
        Candidate had = pool.get(key);
        // A track vouched for by two sources keeps the better claim rather than the later one.
        if (had == null) pool.put(key, new Candidate(t, score));
        else if (score > had.score) had.score = score;
    }

    /** Tracks sitting next to the seed on the device: same artist first, then same folder. */
    private static List<Track> shelf(Track seed) {
        List<Track> out = new ArrayList<Track>();
        if (seed.type != Track.TYPE_LOCAL) return out;
        String artist = artistKey(seed);
        for (Track t : MediaLibrary.get().tracks()) {
            if (t.key().equals(seed.key())) continue;
            boolean sameArtist = artist != null && artist.equals(artistKey(t));
            boolean sameFolder = seed.folder != null && seed.folder.equals(t.folder);
            if (sameArtist || sameFolder) out.add(t);
        }
        Collections.shuffle(out, RND);
        return out.size() > 40 ? out.subList(0, 40) : out;
    }

    /**
     * A device track has no video id of its own, so it is matched to its catalogue twin first.
     * The search is cached, so the detour is paid once per song.
     */
    private static List<Track> mixFor(Context app, Track seed) {
        String id = YtApi.videoId(seed);
        if (id == null) {
            String term = TextUtils.isEmpty(seed.artist) ? seed.title : seed.artist + " " + seed.title;
            List<Track> hits = YtApi.searchBlocking(app, term);
            if (hits == null || hits.isEmpty()) return null;
            id = YtApi.videoId(hits.get(0));
        }
        return id == null ? null : YtApi.mixBlocking(app, id);
    }

    /** How warmly the listener has taken to each artist, scaled against their favourite. */
    private static Map<String, Float> artistAffinity(Context app, Db db) {
        Map<String, Float> out = new LinkedHashMap<String, Float>();
        LinkedHashMap<String, Integer> scored = db.topScored(60);
        if (scored.isEmpty()) return out;
        float top = 0f;
        for (Track t : MediaLibrary.resolve(app, new ArrayList<String>(scored.keySet()))) {
            String artist = artistKey(t);
            Integer s = scored.get(t.key());
            if (artist == null || s == null) continue;
            Float had = out.get(artist);
            float total = (had == null ? 0f : had) + s;
            out.put(artist, total);
            if (total > top) top = total;
        }
        if (top > 0f) {
            for (Map.Entry<String, Float> e : out.entrySet()) e.setValue(e.getValue() / top);
        }
        return out;
    }

    private static String artistKey(Track t) {
        return TextUtils.isEmpty(t.artist) ? null : t.artist.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Takes the best entries while keeping one artist from filling the run, or from appearing
     * twice at all where {@code onePerArtist} asks for it. A held-back candidate is only put back
     * once the pool has nothing else to give, so the count asked for still arrives.
     */
    private static List<Track> spread(List<Candidate> ranked, int want, boolean onePerArtist) {
        List<Track> out = new ArrayList<Track>(want);
        List<Candidate> parked = new ArrayList<Candidate>();
        Set<String> taken = new HashSet<String>();
        for (Candidate c : ranked) {
            if (out.size() >= want) break;
            String artist = artistKey(c.track);
            boolean repeat = onePerArtist && artist != null && !taken.add(artist);
            if (repeat || crowded(out, c.track)) parked.add(c);
            else out.add(c.track);
        }
        for (Candidate c : parked) {
            if (out.size() >= want) break;
            out.add(c.track);
        }
        return out;
    }

    private static boolean crowded(List<Track> chosen, Track t) {
        String artist = artistKey(t);
        if (artist == null || chosen.size() < 2) return false;
        return artist.equals(artistKey(chosen.get(chosen.size() - 1)))
                && artist.equals(artistKey(chosen.get(chosen.size() - 2)));
    }
}
