package com.nota.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;

import com.nota.R;
import com.nota.data.ArtLoader;
import com.nota.model.Track;

public class Ui {

    public static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                c.getResources().getDisplayMetrics()));
    }

    public static void round(View view, final float radiusPx) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
            }
        });
        view.setClipToOutline(true);
    }

    public static int themeColor(Context c, int colorRes) {
        return c.getResources().getColor(colorRes, c.getTheme());
    }

    public interface ColorCallback {
        void onColor(int colour);
    }

    /**
     * The colour a cover should tint its screen with. A cached bitmap answers at once; anything
     * else answers zero first and the real colour later, so no screen waits on a decode.
     */
    public static void coverColor(Context c, final Track track, final ColorCallback cb) {
        final ArtLoader loader = ArtLoader.get(c);
        final int size = dp(c, 320);
        Bitmap hit = loader.cached(track, size);
        if (hit != null) {
            cb.onColor(dominantColor(hit));
            return;
        }
        cb.onColor(0);
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                Bitmap full = loader.load(track, size);
                final int colour = full == null ? 0 : dominantColor(full);
                main.post(new Runnable() {
                    public void run() {
                        cb.onColor(colour);
                    }
                });
            }
        }, "nota-tint").start();
    }

    /** Picks the most vivid colour in the cover, which reads better than an average. */
    public static int dominantColor(Bitmap source) {
        Bitmap small = Bitmap.createScaledBitmap(source, 16, 16, true);
        float[] hsv = new float[3];
        int best = 0;
        float bestScore = -1;
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int pixel = small.getPixel(x, y);
                Color.colorToHSV(pixel, hsv);
                float score = hsv[1] * (1 - Math.abs(hsv[2] - 0.65f));
                if (score > bestScore) {
                    bestScore = score;
                    best = pixel;
                }
            }
        }
        return best;
    }

    public static String duration(long ms) {
        if (ms <= 0) return "0:00";
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    public static String songCount(Resources res, int n) {
        return n == 1 ? res.getString(R.string.one_song) : res.getString(R.string.n_songs, n);
    }

    public static String albumCount(Resources res, int n) {
        return n == 1 ? res.getString(R.string.one_album) : res.getString(R.string.n_albums, n);
    }

    public static String artistOr(Context c, String artist) {
        return artist == null || artist.length() == 0
                ? c.getString(R.string.unknown_artist) : artist;
    }

    public static String albumOr(Context c, String album) {
        return album == null || album.length() == 0
                ? c.getString(R.string.unknown_album) : album;
    }
}
