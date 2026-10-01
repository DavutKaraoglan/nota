package com.nota.data;

import android.content.Context;

import com.nota.model.Track;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Gets the songs around the current one ready before they are asked for.
 *
 * <p>Two depths of readiness. The songs on either side of the one playing are fetched whole —
 * audio, cover and words — because a swipe reaches them at once and a swipe should not wait.
 * Everything further ahead is only warmed: an address has to be asked of YouTube before a byte
 * of the song can be, and that lookup is the wait a listener feels on the first note, so it is
 * done while there is still a song playing. The answer is kept until it expires, so the later
 * fetch opens a connection straight away without the phone having spent anything on audio.
 */
public class Prefetch {

    /** Far enough ahead to cover a skip, close enough that browsing does not warm an album. */
    private static final int AHEAD = 2;

    private static Prefetch instance;

    public static synchronized Prefetch get(Context c) {
        if (instance == null) instance = new Prefetch(c.getApplicationContext());
        return instance;
    }

    /** A song to fetch whole carries its track; a song only to be warmed is an id alone. */
    private static final class Job {
        final Track track;
        final String id;

        Job(Track track, String id) {
            this.track = track;
            this.id = id;
        }

        boolean whole() {
            return track != null;
        }
    }

    private final Context app;
    private final LinkedBlockingDeque<Job> pending = new LinkedBlockingDeque<Job>();
    /**
     * Every song warmed since launch, not just the ones still queued. Warming happens on each
     * track change for the songs after the current one, so a song two away is reached twice; the
     * address found the first time is still held, so the second ask is a request that never
     * leaves the phone.
     */
    private final Set<String> asked = new HashSet<String>();
    /** Songs being fetched whole right now, by track key, so neither kind asks for them twice. */
    private final Set<String> inFlight = new HashSet<String>();

    private Prefetch(Context app) {
        this.app = app;
        Thread worker = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "nota-warm");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Readies the songs a swipe can reach and warms the ones past them. Safe to call on every
     * track change: the whole-song work queued for the song before this one is dropped, since a
     * swipe no longer lands there and the one that does should not wait behind it.
     */
    public void around(Track next, Track previous, List<Track> upcoming) {
        if (!Connectivity.isOnline(app)) return;
        synchronized (this) {
            for (Iterator<Job> it = pending.iterator(); it.hasNext(); ) {
                Job job = it.next();
                if (!job.whole()) continue;
                it.remove();
                inFlight.remove(job.track.key());
            }
            // The song behind goes in first so the one about to play comes off the front.
            offer(previous);
            offer(next);
        }
        warm(upcoming);
    }

    /** Caller holds the lock. */
    private void offer(Track t) {
        if (t == null || !inFlight.add(t.key())) return;
        pending.addFirst(new Job(t, YtApi.videoId(t)));
    }

    /** Queues the upcoming tracks worth warming. */
    private void warm(List<Track> upcoming) {
        int queued = 0;
        for (Track t : upcoming) {
            if (queued >= AHEAD) return;
            String id = YtApi.videoId(t);
            if (id == null) continue;
            queued++;
            if (StreamProxy.get(app).cached(id)) continue;
            synchronized (this) {
                if (inFlight.contains(t.key())) continue;
                if (!asked.add(id)) continue;
                pending.add(new Job(null, id));
            }
        }
    }

    private void loop() {
        while (true) {
            Job job;
            try {
                job = pending.take();
            } catch (InterruptedException e) {
                return;
            }
            if (job.whole()) fetch(job);
            else warmOne(job);
        }
    }

    /**
     * Leaves the song on the phone: its cover at the size the player shows, its words, its
     * opening, then the rest of it. In that order, because a swipe puts the cover and the name
     * on screen in the frame it begins — and the whole song waits for a quiet line, which the
     * cover should never have been queued behind.
     */
    private void fetch(Job job) {
        try {
            ArtLoader loader = ArtLoader.get(app);
            loader.load(job.track, loader.playerSize());
            Lyrics.loadBlocking(app, job.track);
            if (job.id != null) {
                StreamProxy proxy = StreamProxy.get(app);
                proxy.preloadHead(job.id);
                proxy.preload(job.id);
            }
        } catch (Exception e) {
            CrashLog.note(app, "ready failed: " + e);
        } finally {
            synchronized (this) {
                inFlight.remove(job.track.key());
            }
        }
    }

    /**
     * Looks the song's address up ahead of time. Resolving it is the one slow step before the
     * first byte, and the answer is kept until it expires, so the song that follows this one
     * starts on a lookup that has already happened.
     */
    private void warmOne(Job job) {
        boolean ready;
        try {
            ready = YtApi.streamUrl(app, job.id) != null;
        } catch (Exception e) {
            CrashLog.note(app, "warm failed: " + e);
            ready = false;
        }
        // Only an answered warm is worth remembering. A song whose address could not be found
        // is worth asking after again: a line that was down while the queue was built is the
        // usual reason, and it need not still be down when the song comes round.
        if (!ready) synchronized (this) {
            asked.remove(job.id);
        }
    }
}
