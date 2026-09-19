package com.nota.data;

import android.os.Build;

/**
 * Lyrics in letters a Latin reader can sing along to. LRCLIB carries a romanised upload for some
 * songs and not for others, so whatever arrives in kana or kanji is handed to ICU, which ships with
 * the system from Android 10 on.
 *
 * <p>ICU reads kana exactly and knows kanji only by their Chinese sound, so what comes back is a
 * pronunciation guide with a Mandarin accent rather than proper romaji. It is still the difference
 * between following the song and looking at a wall, and it only ever replaces text that could not
 * be read at all.
 */
final class Romaji {

    private Romaji() {
    }

    /** True when the text is already mostly readable; a stray kanji in a Latin line is not worth it. */
    static boolean readable(String text) {
        int letters = 0, east = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetter(c)) continue;
            letters++;
            if (isEastAsian(c)) east++;
        }
        return letters == 0 || east * 4 < letters;
    }

    static String apply(String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return text;
        try {
            return Icu.latin(text);
        } catch (Throwable t) {
            // A missing transliterator is not worth losing the words over.
            return text;
        }
    }

    private static boolean isEastAsian(char c) {
        return (c >= 0x3040 && c <= 0x30FF)      // hiragana and katakana
                || (c >= 0x3400 && c <= 0x4DBF)  // rarer kanji
                || (c >= 0x4E00 && c <= 0x9FFF); // kanji
    }

    /** Kept apart so a device below Android 10 never loads a class it has no ICU for. */
    private static final class Icu {
        private static final android.icu.text.Transliterator T =
                android.icu.text.Transliterator.getInstance("Any-Latn; Latin-ASCII");

        static synchronized String latin(String s) {
            return T.transliterate(s);
        }
    }
}
