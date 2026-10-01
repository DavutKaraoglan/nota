package com.nota.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;

import com.nota.R;
import com.nota.data.ArtLoader;
import com.nota.data.Prefs;
import com.nota.model.Track;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class Ui {

    /**
     * The context a screen is built on, carrying the theme and the language the listener chose
     * where the system would otherwise have decided both. Every screen and the notification are
     * attached to it, so one choice reaches all of them.
     */
    public static Context chosen(Context base) {
        Configuration c = new Configuration(base.getResources().getConfiguration());
        boolean changed = false;

        int mode = Prefs.getInt(base, SettingsPage.KEY_THEME, SettingsPage.THEME_SYSTEM);
        if (mode != SettingsPage.THEME_SYSTEM) {
            c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | (mode == SettingsPage.THEME_DARK
                    ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            changed = true;
        }

        String language = Prefs.getString(base, SettingsPage.KEY_LANGUAGE, "");
        if (language.length() > 0) {
            Locale picked = new Locale(language);
            // Set as the default too: a date or a number formatted away from a view has no
            // context to read the choice from, and would otherwise follow the phone instead.
            Locale.setDefault(picked);
            c.setLocale(picked);
            changed = true;
        }

        return changed ? base.createConfigurationContext(c) : base;
    }

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

    /**
     * The band across the top of a page, which a background stretches whatever it is given to
     * fill. A photograph of a person is taller than the band, so it is cut down to its shape
     * from the top, where a face is, rather than squashed into it.
     */
    public static Bitmap band(Bitmap bmp, int w, int h) {
        int want = bmp.getWidth() * h / w;
        if (want <= 0 || bmp.getHeight() <= want) return bmp;
        return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), want);
    }

    /**
     * What keeps a page readable over a photograph: the page colour, absent where the picture
     * should be seen and whole where the songs begin. It is the background colour rather than
     * black so the picture dissolves into the page in either theme.
     */
    public static Drawable scrim(Context c) {
        int rgb = c.getResources().getColor(R.color.bg) & 0xFFFFFF;
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x1A000000 | rgb, 0x80000000 | rgb, 0xFF000000 | rgb});
    }

    private static Locale dayLocale;
    private static SimpleDateFormat withinYear;
    private static SimpleDateFormat beforeThatYear;

    /** The day a moment fell on, named the way it would be said out loud. */
    public static String day(Context c, long at) {
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(at);
        Calendar today = Calendar.getInstance();
        int days = daysApart(then, today);
        if (days == 0) return c.getString(R.string.today);
        if (days == 1) return c.getString(R.string.yesterday);
        Locale locale = c.getResources().getConfiguration().getLocales().get(0);
        if (!locale.equals(dayLocale)) {
            dayLocale = locale;
            withinYear = dayFormat(locale, "dMMMM");
            beforeThatYear = dayFormat(locale, "dMMMMy");
        }
        boolean sameYear = then.get(Calendar.YEAR) == today.get(Calendar.YEAR);
        return (sameYear ? withinYear : beforeThatYear).format(then.getTime());
    }

    private static SimpleDateFormat dayFormat(Locale locale, String pattern) {
        return new SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, pattern), locale);
    }

    private static int daysApart(Calendar then, Calendar now) {
        Calendar a = midnight(then), b = midnight(now);
        return (int) ((b.getTimeInMillis() - a.getTimeInMillis()) / (24L * 60 * 60 * 1000));
    }

    private static Calendar midnight(Calendar source) {
        Calendar c = (Calendar) source.clone();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }
}
