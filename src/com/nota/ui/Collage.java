package com.nota.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.nota.data.ArtLoader;
import com.nota.data.NetPriority;
import com.nota.data.YtApi;
import com.nota.model.Track;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Collage {

    private static final int TILES = 4;

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> made = new LruCache<String, Bitmap>(24);

    public static void bindMix(final ImageView view, final Track seed, final int sizePx) {
        final String videoId = YtApi.videoId(seed);
        if (videoId == null) return;
        final String key = "mix:" + videoId + ":" + sizePx;
        Bitmap hit = made.get(key);
        if (hit != null) {
            show(view, hit, key);
            return;
        }
        final Object boundTo = view.getTag();
        final Context app = view.getContext().getApplicationContext();
        worker.execute(new Runnable() {
            public void run() {
                NetPriority.yieldToAudio();
                List<Track> mix = YtApi.mixBlocking(app, videoId);
                if (mix == null) return;
                final Bitmap bmp = compose(app, pick(seed, mix), sizePx);
                if (bmp == null) return;
                made.put(key, bmp);
                main.post(new Runnable() {
                    public void run() {
                        Object tag = view.getTag();
                        if (tag == boundTo || (tag != null && tag.equals(boundTo))) {
                            show(view, bmp, key);
                        }
                    }
                });
            }
        });
    }

    private static void show(ImageView view, Bitmap bmp, String key) {
        view.setTag(key);
        view.setPadding(0, 0, 0, 0);
        view.setImageBitmap(bmp);
    }

    private static List<Track> pick(Track seed, List<Track> mix) {
        List<Track> all = new ArrayList<Track>();
        all.add(seed);
        all.addAll(mix);
        List<Track> out = new ArrayList<Track>();
        Set<String> arts = new HashSet<String>();
        Set<String> artists = new HashSet<String>();
        for (int pass = 0; pass < 2 && out.size() < TILES; pass++) {
            for (Track t : all) {
                if (out.size() == TILES) break;
                if (t.artUrl == null || arts.contains(t.artUrl)) continue;
                String who = t.artist == null ? "" : t.artist.toLowerCase(Locale.ROOT);
                if (pass == 0 && !artists.add(who)) continue;
                arts.add(t.artUrl);
                out.add(t);
            }
        }
        return out;
    }

    private static Bitmap compose(Context app, List<Track> tiles, int size) {
        if (tiles.size() < TILES) return null;
        int half = size / 2;
        ArtLoader loader = ArtLoader.get(app);
        Bitmap out = Bitmap.createBitmap(half * 2, half * 2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        for (int i = 0; i < TILES; i++) {
            Bitmap src = loader.load(tiles.get(i), half);
            if (src == null) return null;
            int side = Math.min(src.getWidth(), src.getHeight());
            int x = (src.getWidth() - side) / 2;
            int y = (src.getHeight() - side) / 2;
            int left = (i % 2) * half;
            int top = (i / 2) * half;
            canvas.drawBitmap(src, new Rect(x, y, x + side, y + side),
                    new Rect(left, top, left + half, top + half), paint);
        }
        return out;
    }
}
