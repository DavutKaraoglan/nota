package com.nota.data;

import android.content.Context;

import com.nota.model.Track;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pulls the next song down before it is needed, so skipping lands on a file rather than on a
 * new stream. The helper has to extract a track before it can serve it, which is the pause the
 * listener actually feels; asking for it early moves that wait off the skip.
 */
public class Prefetch {

    /** Far enough ahead to cover a skip, close enough that browsing does not download an album. */
    private static final int AHEAD = 2;
    /** A song this large is not a song; refusing it keeps a bad id from filling the cache. */
    private static final long MAX_BYTES = 24L * 1024 * 1024;
    private static final int KEEP = 6;

    private static Prefetch instance;

    public static synchronized Prefetch get(Context c) {
        if (instance == null) instance = new Prefetch(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final File dir;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Set<String> inFlight = new HashSet<String>();

    private Prefetch(Context app) {
        this.app = app;
        this.dir = new File(app.getCacheDir(), "stream");
        dir.mkdirs();
    }

    /** The finished download for a track, or null when it still has to be streamed. */
    public File ready(Track t) {
        String id = YtApi.videoId(t);
        if (id == null) return null;
        File f = new File(dir, id + ".m4a");
        if (!f.isFile() || f.length() == 0) return null;
        f.setLastModified(System.currentTimeMillis());
        return f;
    }

    /** Queues the upcoming tracks that are worth fetching. Safe to call on every track change. */
    public void warm(List<Track> upcoming) {
        if (!Connectivity.isOnline(app)) return;
        int queued = 0;
        for (Track t : upcoming) {
            if (queued >= AHEAD) return;
            final String id = YtApi.videoId(t);
            if (id == null) continue;
            queued++;
            if (ready(t) != null) continue;
            synchronized (inFlight) {
                if (!inFlight.add(id)) continue;
            }
            final String url = YtApi.streamUrl(t);
            pool.execute(new Runnable() {
                public void run() {
                    try {
                        download(id, url);
                    } catch (Exception ignored) {
                    } finally {
                        synchronized (inFlight) {
                            inFlight.remove(id);
                        }
                    }
                }
            });
        }
    }

    private void download(String id, String url) throws Exception {
        File part = new File(dir, id + ".part");
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            // The helper may still be extracting, and that wait is the whole point of asking early.
            conn.setReadTimeout(60000);
            if (conn.getResponseCode() != 200) return;
            in = conn.getInputStream();
            out = new FileOutputStream(part);
            byte[] chunk = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(chunk)) > 0) {
                out.write(chunk, 0, n);
                total += n;
                if (total > MAX_BYTES) return;
            }
            out.close();
            out = null;
            DataSaver.get(app).addDownloaded(total);
            // Renamed only once whole, so a half-written file can never be played as a track.
            part.renameTo(new File(dir, id + ".m4a"));
            prune();
        } finally {
            if (out != null) try {
                out.close();
            } catch (Exception ignored) {
            }
            if (in != null) try {
                in.close();
            } catch (Exception ignored) {
            }
            if (conn != null) conn.disconnect();
            part.delete();
        }
    }

    private void prune() {
        File[] files = dir.listFiles();
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (int i = KEEP; i < files.length; i++) files[i].delete();
    }
}
