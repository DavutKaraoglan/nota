package com.nota.data;

import android.content.Context;

import com.nota.model.Track;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Asks the helper to get the next songs ready.
 *
 * <p>The helper has to pull a song apart before it can send any of it, and that is the seconds
 * a listener feels on the first note. It keeps what it extracted, so a question asked while the
 * previous song is still playing costs nothing on the phone and leaves the next one answered at
 * once. The bytes themselves are not fetched here: they arrive through {@link StreamProxy} as
 * the song plays.
 */
public class Prefetch {

    /** Far enough ahead to cover a skip, close enough that browsing does not warm an album. */
    private static final int AHEAD = 2;

    private static Prefetch instance;

    public static synchronized Prefetch get(Context c) {
        if (instance == null) instance = new Prefetch(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final LinkedBlockingQueue<String> pending = new LinkedBlockingQueue<String>();
    /**
     * Every song asked for since launch, not just the ones still queued. Warming happens on each
     * track change for the songs after the current one, so a song two away is reached twice; the
     * helper keeps what it extracted, which makes the second ask work the box charges for and
     * nothing the listener gets.
     */
    private final Set<String> asked = new HashSet<String>();

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

    /** Queues the upcoming tracks worth warming. Safe to call on every track change. */
    public void warm(List<Track> upcoming) {
        if (!Connectivity.isOnline(app)) return;
        int queued = 0;
        for (Track t : upcoming) {
            if (queued >= AHEAD) return;
            String id = YtApi.videoId(t);
            if (id == null) continue;
            queued++;
            if (StreamProxy.get(app).cached(id)) continue;
            synchronized (this) {
                if (!asked.add(id)) continue;
                pending.add(id);
            }
        }
    }

    private void loop() {
        while (true) {
            String id;
            try {
                id = pending.take();
            } catch (InterruptedException e) {
                return;
            }
            boolean ready;
            try {
                ready = warmOne(id);
            } catch (Exception e) {
                CrashLog.note(app, "warm failed: " + e);
                ready = false;
            }
            // Only an answered warm is worth remembering. A refused one left the helper with
            // nothing, and its reason expires: the hourly cap it ran into will have moved on by
            // the time this song comes round again.
            if (!ready) synchronized (this) {
                asked.remove(id);
            }
        }
    }

    /**
     * A HEAD costs the helper the whole extraction and costs the phone nothing but the headers,
     * because the helper does the work either way and answers a HEAD without a body.
     *
     * @return whether the helper now holds the song
     */
    private boolean warmOne(String id) throws Exception {
        String url = YtApi.streamUrl(id);
        if (url == null) return false;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("HEAD");
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        try {
            long started = System.currentTimeMillis();
            int code = c.getResponseCode();
            boolean held = code >= 200 && code < 300;
            // A warm that worked is the normal case and says nothing; a refusal is the one that
            // explains a silence later, so only that is kept.
            if (!held) {
                CrashLog.note(app, "warm " + code + " "
                        + (System.currentTimeMillis() - started) + "ms");
            }
            return held;
        } finally {
            c.disconnect();
        }
    }
}
