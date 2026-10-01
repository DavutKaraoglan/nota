package com.nota.data;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.widget.ImageView;

import com.nota.model.Track;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads local and remote album art off the UI thread, with a memory cache. */
public class ArtLoader {

    private static ArtLoader instance;

    public static synchronized ArtLoader get(Context context) {
        if (instance == null) instance = new ArtLoader(context.getApplicationContext());
        return instance;
    }

    private static final Uri LEGACY_ALBUM_ART = Uri.parse("content://media/external/audio/albumart");

    /** A cover is a few hundred kilobytes; the whole body is held in memory before decoding. */
    private static final int MAX_ART_BYTES = 4 * 1024 * 1024;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService local = Executors.newFixedThreadPool(2);
    private final ExecutorService remote = Executors.newFixedThreadPool(4);
    private final LruCache<String, Bitmap> cache;
    private final File diskDir;

    private ArtLoader(Context app) {
        this.app = app;
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        cache = new LruCache<String, Bitmap>(maxKb / 6) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        diskDir = new File(app.getCacheDir(), "art");
        diskDir.mkdirs();
    }

    /**
     * Identity of the artwork, ignoring size. Tracks MediaStore could not file under a real
     * album would otherwise all collide on "a:0" and show the first one's embedded picture.
     */
    public static String keyFor(Track t) {
        if (t == null) return null;
        if (t.isRemote()) return t.artUrl == null ? null : "u:" + t.artUrl;
        return t.albumId > 0 ? "a:" + t.albumId : "t:" + t.id;
    }

    /** Bitmaps are decoded per surface, so the row thumbnail must not satisfy a 320dp request. */
    private static String cacheKey(String key, int sizePx) {
        if (key == null) return null;
        return key + "@" + (key.startsWith("u:") ? bucket(sizePx) : sizePx);
    }

    private static int bucket(int px) {
        return px <= 160 ? 160 : px <= 320 ? 320 : 544;
    }

    public Bitmap cached(Track t, int sizePx) {
        String k = cacheKey(keyFor(t), sizePx);
        return k == null ? null : cache.get(k);
    }

    /**
     * Binds art to an ImageView. The key is stored as the view tag so a recycled row
     * discards results from its previous, still-running load.
     */
    public void bind(final ImageView view, final Track track, final int sizePx,
                     final int placeholderRes, final int placeholderPadPx) {
        final String key = cacheKey(keyFor(track), sizePx);
        view.setTag(key);
        if (key == null) {
            showPlaceholder(view, placeholderRes, placeholderPadPx);
            return;
        }
        Bitmap hit = cache.get(key);
        if (hit != null) {
            view.setPadding(0, 0, 0, 0);
            view.setImageBitmap(hit);
            return;
        }
        showPlaceholder(view, placeholderRes, placeholderPadPx);

        final String path = track.isRemote() ? track.artUrl : track.data;
        final long albumId = track.albumId;
        final boolean stream = track.isRemote();
        (stream ? remote : local).execute(new Runnable() {
            public void run() {
                final Bitmap bmp = stream ? loadRemote(path, sizePx)
                        : loadAlbum(albumId, path, sizePx);
                if (bmp != null) cache.put(key, bmp);
                main.post(new Runnable() {
                    public void run() {
                        if (bmp != null && key.equals(view.getTag())) {
                            view.setPadding(0, 0, 0, 0);
                            view.setImageBitmap(bmp);
                        }
                    }
                });
            }
        });
    }

    private static void showPlaceholder(ImageView view, int res, int pad) {
        view.setPadding(pad, pad, pad, pad);
        view.setImageResource(res);
    }

    /**
     * The cover size the full-screen player binds at. Kept here because a cover fetched ahead of
     * time only spares the player a wait when it was fetched at the size the player asks for.
     */
    public int playerSize() {
        return Math.round(320 * app.getResources().getDisplayMetrics().density);
    }

    /** Synchronous load for the notification and the full-screen player. */
    public Bitmap load(Track track, int sizePx) {
        String key = cacheKey(keyFor(track), sizePx);
        if (key == null) return null;
        Bitmap hit = cache.get(key);
        if (hit != null) return hit;
        Bitmap bmp = track.isRemote()
                ? loadRemote(track.artUrl, sizePx)
                : loadAlbum(track.albumId, track.data, sizePx);
        if (bmp != null) cache.put(key, bmp);
        return bmp;
    }

    private Bitmap loadAlbum(long albumId, String filePath, int size) {
        ContentResolver cr = app.getContentResolver();
        if (Build.VERSION.SDK_INT >= 29 && albumId > 0) {
            try {
                Uri u = ContentUris.withAppendedId(
                        MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId);
                return cr.loadThumbnail(u, new android.util.Size(size, size), null);
            } catch (Throwable ignored) {
            }
        }
        if (albumId > 0) {
            InputStream in = null;
            try {
                in = cr.openInputStream(ContentUris.withAppendedId(LEGACY_ALBUM_ART, albumId));
                Bitmap b = decodeScaled(in, size);
                if (b != null) return b;
            } catch (Throwable ignored) {
            } finally {
                close(in);
            }
        }
        if (filePath != null) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(filePath);
                byte[] raw = mmr.getEmbeddedPicture();
                if (raw != null) return decodeScaled(raw, size);
            } catch (Throwable ignored) {
            } finally {
                try {
                    mmr.release();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /**
     * YouTube hands out cover art at any size: the suffix after '=' is the resize request, so a
     * list row and the player each fetch the picture at their own scale. The cap matters more
     * than the buckets do — asking for the player's full 320dp would pull a 348 KB picture.
     */
    private static String sized(String url, int px) {
        if (url == null || !url.contains("googleusercontent.com")) return url;
        int side = bucket(px);
        int eq = url.lastIndexOf('=');
        return (eq > 0 ? url.substring(0, eq) : url) + "=w" + side + "-h" + side + "-l90-rj";
    }

    /** A picture that is not square and belongs to no track: an artist's banner. */
    private static String sizedWide(String url, int w, int h) {
        if (url == null || !url.contains("googleusercontent.com")) return url;
        int eq = url.lastIndexOf('=');
        // "-p" is the crop that fills the box, so the shape asked for is the shape that arrives
        // and the view never has to stretch it.
        return (eq > 0 ? url.substring(0, eq) : url) + "=w" + w + "-h" + h + "-p-l90-rj";
    }

    public interface BitmapCallback {
        void onBitmap(Bitmap bmp);
    }

    public void loadWide(final String rawUrl, final int w, final int h, final BitmapCallback cb) {
        final String key = cacheKey("w:" + rawUrl, w * h);
        if (key == null || rawUrl == null) return;
        Bitmap hit = cache.get(key);
        if (hit != null) {
            cb.onBitmap(hit);
            return;
        }
        remote.execute(new Runnable() {
            public void run() {
                final Bitmap bmp = fetch(sizedWide(rawUrl, w, h), w);
                if (bmp != null) cache.put(key, bmp);
                main.post(new Runnable() {
                    public void run() {
                        if (bmp != null) cb.onBitmap(bmp);
                    }
                });
            }
        });
    }

    private Bitmap loadRemote(String rawUrl, int size) {
        if (rawUrl == null || rawUrl.length() == 0) return null;
        return fetch(sized(rawUrl, size), size);
    }

    private Bitmap fetch(String url, int size) {
        if (url == null || url.length() == 0) return null;
        File f = new File(diskDir, DiskCache.digest(url) + "_" + bucket(size));
        if (f.exists()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b != null) {
                f.setLastModified(System.currentTimeMillis());
                DataSaver.get(app).hit(f);
                return b;
            }
            f.delete();
        }
        NetPriority.yieldToAudio();
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", Net.USER_AGENT);
            if (conn.getResponseCode() != 200) return null;
            in = conn.getInputStream();
            Bitmap b = decodeScaled(in, size);
            DataSaver.get(app).addDownloaded(Math.max(conn.getContentLength(), 0));
            if (b != null) {
                FileOutputStream out = new FileOutputStream(f);
                b.compress(Bitmap.CompressFormat.JPEG, 90, out);
                out.close();
            }
            return b;
        } catch (Throwable ignored) {
            return null;
        } finally {
            close(in);
            if (conn != null) conn.disconnect();
        }
    }

    private static Bitmap decodeScaled(InputStream in, int size) throws Exception {
        if (in == null) return null;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_ART_BYTES) return null;
        }
        return decodeScaled(buf.toByteArray(), size);
    }

    private static Bitmap decodeScaled(byte[] raw, int size) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= size && o.outHeight / (sample * 2) >= size) {
            sample *= 2;
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
    }

    private static void close(InputStream in) {
        if (in != null) try {
            in.close();
        } catch (Exception ignored) {
        }
    }
}
