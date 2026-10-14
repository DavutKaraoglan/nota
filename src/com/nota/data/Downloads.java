package com.nota.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import com.nota.model.Track;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
    /** How much is asked for at once, matching the proxy. */
    private static final int CHUNK_BYTES = 1024 * 1024;

    public static final String KEY_TO_MUSIC = "downloads_to_music";
    public static final String MUSIC_PATH = Environment.DIRECTORY_MUSIC + "/Nota/";

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

    public boolean toMusic() {
        return Prefs.getBool(app, KEY_TO_MUSIC, false);
    }

    public void setToMusic(boolean on) {
        Prefs.setBool(app, KEY_TO_MUSIC, on);
        if (!on) return;
        workers.execute(new Runnable() {
            public void run() {
                String prefix = "o:" + YtApi.PREFIX;
                for (String key : keptKeys()) copyToMusic(key.substring(prefix.length()));
            }
        });
    }

    public synchronized boolean running(String videoId) {
        return running.contains(videoId);
    }

    /** The kept songs as track keys, newest first, for the library to list them. */
    public List<String> keptKeys() {
        File[] files = dir.listFiles();
        List<String> out = new ArrayList<String>();
        if (files == null) return out;
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".m4a") || f.length() == 0) continue;
            out.add("o:" + YtApi.PREFIX + name.substring(0, name.length() - 4));
        }
        return out;
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
                    if (ok && toMusic()) copyToMusic(videoId);
                } catch (Exception e) {
                    CrashLog.note(app, "download failed: " + e);
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
        String url = YtApi.streamUrl(app, videoId);
        if (url == null) return false;
        File part = new File(dir, videoId + ".part");
        long total = -1;
        long written = 0;
        try {
            OutputStream to = new FileOutputStream(part);
            try {
                byte[] buffer = new byte[65536];
                while (total < 0 || written < total) {
                    HttpURLConnection c = chunk(url, written);
                    try {
                        if (c.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) return false;
                        if (total < 0) {
                            total = totalOf(c);
                            if (total <= 0 || total > MAX_BYTES) return false;
                        }
                        long before = written;
                        InputStream from = c.getInputStream();
                        try {
                            int n;
                            while (written < total && (n = from.read(buffer)) > 0) {
                                to.write(buffer, 0, n);
                                written += n;
                            }
                        } finally {
                            from.close();
                        }
                        if (written == before) return false;
                    } finally {
                        c.disconnect();
                    }
                }
            } finally {
                to.close();
            }
            DataSaver.get(app).addDownloaded(written);
            return written == total && part.renameTo(file(videoId));
        } finally {
            part.delete();
        }
    }

    private void copyToMusic(String videoId) {
        File source = file(videoId);
        if (!source.isFile()) return;
        String id = YtApi.PREFIX + videoId;
        String name = musicName(Db.get(app).online(Collections.singleton(id)).get(id), videoId);
        try {
            if (Build.VERSION.SDK_INT >= 29) copyToStore(source, name);
            else copyToFolder(source, name);
        } catch (Exception e) {
            CrashLog.note(app, "music copy failed: " + e);
        }
    }

    private void copyToStore(File source, String name) throws Exception {
        ContentResolver cr = app.getContentResolver();
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Cursor c = cr.query(collection, new String[]{MediaStore.Audio.Media._ID},
                MediaStore.Audio.Media.RELATIVE_PATH + "=? AND "
                        + MediaStore.Audio.Media.DISPLAY_NAME + "=?",
                new String[]{MUSIC_PATH, name}, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) return;
            } finally {
                c.close();
            }
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        v.put(MediaStore.Audio.Media.RELATIVE_PATH, MUSIC_PATH);
        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri item = cr.insert(collection, v);
        if (item == null) return;
        boolean done = false;
        try {
            OutputStream to = cr.openOutputStream(item);
            if (to == null) return;
            copy(source, to);
            done = true;
        } finally {
            if (!done) cr.delete(item, null, null);
        }
        v.clear();
        v.put(MediaStore.Audio.Media.IS_PENDING, 0);
        cr.update(item, v, null, null);
    }

    private void copyToFolder(File source, String name) throws Exception {
        File folder = new File(Environment.getExternalStorageDirectory(), MUSIC_PATH);
        if (!folder.isDirectory() && !folder.mkdirs()) return;
        File target = new File(folder, name);
        if (target.exists()) return;
        File part = new File(folder, name + ".part");
        copy(source, new FileOutputStream(part));
        if (part.renameTo(target)) {
            MediaScannerConnection.scanFile(app, new String[]{target.getPath()}, null, null);
        } else {
            part.delete();
        }
    }

    private static void copy(File source, OutputStream to) throws Exception {
        try {
            InputStream from = new FileInputStream(source);
            try {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = from.read(buffer)) > 0) to.write(buffer, 0, n);
            } finally {
                from.close();
            }
        } finally {
            to.close();
        }
    }

    static String musicName(Track t, String videoId) {
        String name = "";
        if (t != null && t.title != null) {
            name = t.artist == null || t.artist.trim().isEmpty() || t.title.contains(t.artist)
                    ? t.title : t.artist + " - " + t.title;
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", " ").trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.length() > 120) name = name.substring(0, 120).trim();
        if (name.isEmpty()) name = videoId;
        return name + ".m4a";
    }

    /**
     * One stretch of the song. Asked for whole in a single request, YouTube serves a song at
     * about the speed it plays; asked for in pieces, at whatever the line can carry.
     */
    private static HttpURLConnection chunk(String url, long from) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        // Generous, because this is read over whatever line the phone happens to be on.
        c.setReadTimeout(120000);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("User-Agent", YtStream.USER_AGENT);
        c.setRequestProperty("Range", "bytes=" + from + "-" + (from + CHUNK_BYTES - 1));
        return c;
    }

    /** The length of the whole song, as the answer to a ranged request states it. */
    private static long totalOf(HttpURLConnection c) {
        String contentRange = c.getHeaderField("Content-Range");
        if (contentRange == null) return -1;
        int slash = contentRange.indexOf('/');
        if (slash < 0) return -1;
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
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
