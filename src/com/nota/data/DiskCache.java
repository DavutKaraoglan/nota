package com.nota.data;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;

/** Disk-backed JSON responses, so a repeated screen costs nothing on the wire. */
public class DiskCache {

    /**
     * Returns the stored body when it is still fresh, otherwise tries each URL in order and
     * keeps the first success. A stale copy is served when every URL fails, because an old
     * list beats an error screen.
     */
    public static String load(Context app, String bucket, String key, long freshMs,
                              String... urls) {
        File f = file(app, bucket, key);
        String body = read(f);
        if (body != null && System.currentTimeMillis() - f.lastModified() < freshMs) {
            DataSaver.get(app).hit(f);
            return body;
        }
        boolean nothingThere = false;
        for (String url : urls) {
            String fresh;
            try {
                fresh = Net.get(url, 15000);
            } catch (Net.NotFound e) {
                nothingThere = true;
                continue;
            } catch (Exception e) {
                continue;
            }
            if (fresh != null) {
                write(f, fresh);
                return fresh;
            }
        }
        if (body == null && nothingThere) {
            write(f, "");
            return "";
        }
        return body;
    }

    /** Same contract as {@link #load}, for endpoints that only answer a JSON POST. */
    public static String loadPost(Context app, String bucket, String key, long freshMs,
                                  String url, String json, String userAgent) {
        File f = file(app, bucket, key);
        String body = read(f);
        if (body != null && System.currentTimeMillis() - f.lastModified() < freshMs) {
            DataSaver.get(app).hit(f);
            return body;
        }
        try {
            String fresh = Net.post(url, json, userAgent, 15000);
            write(f, fresh);
            return fresh;
        } catch (Exception e) {
            return body;
        }
    }

    public static File file(Context app, String bucket, String key) {
        File dir = new File(app.getCacheDir(), bucket);
        dir.mkdirs();
        return new File(dir, digest(key) + ".json");
    }

    /**
     * A name has to stand for the whole key: with a 32-bit hash two different queries collide
     * often enough to be served each other's answer.
     */
    static String digest(String key) {
        byte[] sum;
        try {
            sum = MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-8"));
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
        char[] hex = new char[32];
        for (int i = 0; i < 16; i++) {
            hex[i * 2] = HEX[(sum[i] >> 4) & 0xF];
            hex[i * 2 + 1] = HEX[sum[i] & 0xF];
        }
        return new String(hex);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String read(File f) {
        if (!f.exists()) return null;
        RandomAccessFile in = null;
        try {
            in = new RandomAccessFile(f, "r");
            byte[] raw = new byte[(int) in.length()];
            in.readFully(raw);
            return new String(raw, "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void write(File f, String body) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(body.getBytes("UTF-8"));
        } catch (Exception ignored) {
        } finally {
            if (out != null) try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }
}
