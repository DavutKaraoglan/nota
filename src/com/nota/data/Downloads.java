package com.nota.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Remote songs the listener asked to keep.
 *
 * <p>The stream cache already holds the last handful of songs, but it throws the oldest away to
 * make room and cannot be relied on for a flight or a tunnel. A download is the listener saying
 * this one stays, so it lives in the app's own files instead of the cache and is never pruned.
 * {@link StreamProxy} looks here first, which is what makes a kept song play with no network.
 */
public class Downloads {

    /** The same ceiling the proxy uses: past this it is not a song, and a bad id could fill disk. */
    private static final long MAX_BYTES = 24L * 1024 * 1024;

    public interface Listener {
        void onDownloadChanged(String videoId, boolean done);
    }

    private static Downloads instance;

    public static synchronized Downloads get(Context c) {
        if (instance == null) instance = new Downloads(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final File dir;
    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> running = new HashSet<String>();
    private final Set<Listener> listeners = new HashSet<Listener>();

    private Downloads(Context app) {
        this.app = app;
        this.dir = new File(app.getFilesDir(), "downloads");
        dir.mkdirs();
    }

    public void addListener(Listener l) {
        synchronized (listeners) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    public File file(String videoId) {
        return new File(dir, videoId + ".m4a");
    }

    public boolean has(String videoId) {
        File f = file(videoId);
        return f.isFile() && f.length() > 0;
    }

    public synchronized boolean running(String videoId) {
        return running.contains(videoId);
    }

    /** Total bytes kept, for the settings screen to show what the downloads cost. */
    public long bytes() {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long total = 0;
        for (File f : files) total += f.length();
        return total;
    }

    public void remove(String videoId) {
        file(videoId).delete();
        announce(videoId, false);
    }

    public void removeAll() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) f.delete();
        announce(null, false);
    }

    /** Starts a fetch, unless the song is already kept or already on its way. */
    public void start(final String videoId) {
        if (!YtApi.validId(videoId) || has(videoId)) return;
        synchronized (this) {
            if (!running.add(videoId)) return;
        }
        announce(videoId, false);
        workers.execute(new Runnable() {
            public void run() {
                boolean ok = false;
                try {
                    ok = fetch(videoId);
                } catch (Exception e) {
                    CrashLog.note(app, "download failed " + videoId + ": " + e);
                }
                synchronized (Downloads.this) {
                    running.remove(videoId);
                }
                announce(videoId, ok);
            }
        });
    }

    /**
     * Written under a temporary name and renamed only once every promised byte arrived, so a
     * fetch cut off halfway is never mistaken afterwards for a complete song.
     */
    private boolean fetch(String videoId) throws Exception {
        String url = YtApi.streamUrl(videoId);
        if (url == null) return false;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        // The helper extracts before it can send, so the wait for the first byte is long.
        c.setReadTimeout(120000);
        c.setRequestProperty("Accept-Encoding", "identity");
        File part = new File(dir, videoId + ".part");
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            long promised = c.getHeaderFieldInt("Content-Length", -1);
            if (promised < 0 || promised > MAX_BYTES) return false;
            InputStream from = c.getInputStream();
            OutputStream to = new FileOutputStream(part);
            long written = 0;
            try {
                byte[] chunk = new byte[65536];
                int n;
                while ((n = from.read(chunk)) > 0) {
                    written += n;
                    if (written > MAX_BYTES) return false;
                    to.write(chunk, 0, n);
                }
            } finally {
                to.close();
                from.close();
            }
            DataSaver.get(app).addDownloaded(written);
            return written == promised && part.renameTo(file(videoId));
        } finally {
            c.disconnect();
            part.delete();
        }
    }

    private void announce(final String videoId, final boolean done) {
        main.post(new Runnable() {
            public void run() {
                Listener[] copy;
                synchronized (listeners) {
                    copy = listeners.toArray(new Listener[listeners.size()]);
                }
                for (Listener l : copy) l.onDownloadChanged(videoId, done);
            }
        });
    }
}
