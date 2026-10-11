package com.nota.data;

import com.nota.model.Track;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SearchRank {

    private static final String[] VARIANTS = {
            "remix", "cover", "extended", "loop", "hour", "hours", "sped", "slowed", "nightcore",
            "reverb", "8d", "karaoke", "mashup", "edit", "bootleg", "phonk", "x", "speed",
            "tutorial", "lesson", "react", "reacts", "reaction", "piano", "guitar", "ukulele",
            "violin", "lofi"
    };
    private static final String[] LONG_WANTED = {"hour", "hours", "saat", "loop", "full", "mix"};
    private static final long LONG_MS = 15L * 60 * 1000;
    private static final float VARIANT_PENALTY = 0.3f;
    private static final float LONG_PENALTY = 0.3f;

    private static class Ranked {
        final Track track;
        final float score;
        final boolean video;
        final int order;

        Ranked(Track track, float score, boolean video, int order) {
            this.track = track;
            this.score = score;
            this.video = video;
            this.order = order;
        }
    }

    public static List<Track> merge(String query, List<Track> songs, List<Track> videos) {
        Set<String> wanted = new LinkedHashSet<String>(tokens(query));
        List<Ranked> all = new ArrayList<Ranked>();
        Set<String> seen = new HashSet<String>();
        add(all, seen, wanted, songs, false);
        add(all, seen, wanted, videos, true);
        Collections.sort(all, new Comparator<Ranked>() {
            public int compare(Ranked a, Ranked b) {
                if (a.score != b.score) return Float.compare(b.score, a.score);
                if (a.video != b.video) return a.video ? 1 : -1;
                return a.order - b.order;
            }
        });
        List<Track> out = new ArrayList<Track>(all.size());
        for (Ranked r : all) out.add(r.track);
        return out;
    }

    private static void add(List<Ranked> all, Set<String> seen, Set<String> wanted,
                            List<Track> tracks, boolean video) {
        if (tracks == null) return;
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            if (!seen.add(t.key())) continue;
            if (!seen.add(tokens(t.title) + "|" + tokens(t.artist))) continue;
            all.add(new Ranked(t, score(wanted, t), video, i));
        }
    }

    static float score(Set<String> wanted, Track t) {
        if (wanted.isEmpty()) return 0f;
        Set<String> have = new HashSet<String>();
        have.addAll(tokens(t.title));
        have.addAll(tokens(t.artist));
        have.addAll(tokens(t.album));
        int hit = 0;
        for (String w : wanted) if (have.contains(w)) hit++;
        float score = (float) hit / wanted.size();
        Set<String> titled = new HashSet<String>(tokens(t.title));
        for (String v : VARIANTS) {
            if (titled.contains(v) && !wanted.contains(v)) {
                score -= VARIANT_PENALTY;
                break;
            }
        }
        if (t.durationMs > LONG_MS && !wantsLong(wanted)) score -= LONG_PENALTY;
        return score;
    }

    private static boolean wantsLong(Set<String> wanted) {
        for (String w : LONG_WANTED) if (wanted.contains(w)) return true;
        return false;
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        String flat = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("['’´`]", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        if (flat.length() == 0) return out;
        for (String w : flat.split(" ")) out.add(w);
        return out;
    }
}
