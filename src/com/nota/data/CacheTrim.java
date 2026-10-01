package com.nota.data;

import android.content.Context;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/** Keeps the kept answers from growing into the phone's storage. */
public class CacheTrim {

    private static final long ART_BUDGET = 48L * 1024 * 1024;
    private static final long JSON_BUDGET = 2L * 1024 * 1024;

    public static void run(Context context) {
        final File cache = context.getApplicationContext().getCacheDir();
        Thread t = new Thread(new Runnable() {
            public void run() {
                trim(new File(cache, "art"), ART_BUDGET);
                trim(new File(cache, "yt"), JSON_BUDGET);
                trim(new File(cache, "lyrics"), JSON_BUDGET);
            }
        }, "nota-trim");
        t.setDaemon(true);
        t.start();
    }

    private static void trim(File dir, long budget) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= budget) return;
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : files) {
            if (total <= budget) return;
            long size = f.length();
            if (f.delete()) total -= size;
        }
    }
}
