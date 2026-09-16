package com.nota.data;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

public class Net {
    /** Some hosts throttle anonymous clients, so every plain GET identifies the app. */
    public static final String USER_AGENT = "Nota/1.0 (Android music player)";

    /**
     * These endpoints answer with a catalogue page; the largest seen is under a megabyte. The
     * cap is on the decoded size because gzip lets a small answer expand without limit, and an
     * OutOfMemoryError is an Error rather than an Exception, so callers would not catch it.
     */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    public static String get(String url, int timeoutMs) throws Exception {
        return send(url, timeoutMs, USER_AGENT, null);
    }

    /** InnerTube rejects a request whose User-Agent disagrees with its client context. */
    public static String post(String url, String json, String userAgent, int timeoutMs)
            throws Exception {
        return send(url, timeoutMs, userAgent, json);
    }

    private static String send(String url, int timeoutMs, String userAgent, String json)
            throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (json != null) {
                byte[] payload = json.getBytes("UTF-8");
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(payload.length);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.getOutputStream().write(payload);
            }
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            in = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) in = new GZIPInputStream(in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            long wire = 0;
            while ((n = in.read(chunk)) > 0) {
                out.write(chunk, 0, n);
                wire += n;
                if (out.size() > MAX_BYTES) throw new Exception("response too large");
            }
            DataSaver meter = DataSaver.peek();
            if (meter != null) {
                // Content-Length is the compressed size; the decoded total would overstate usage.
                int declared = conn.getContentLength();
                meter.addDownloaded(declared > 0 ? declared : wire);
            }
            return out.toString("UTF-8");
        } finally {
            if (in != null) try {
                in.close();
            } catch (Exception ignored) {
            }
            if (conn != null) conn.disconnect();
        }
    }
}
