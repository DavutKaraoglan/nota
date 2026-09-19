package com.nota.data;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Serves remote audio to the player over the loopback address, keeping a copy as it goes.
 *
 * <p>Handing the helper's URL straight to a MediaPlayer starts a song in a second rather than
 * in ten, because playing no longer waits for the last byte. On its own that would also mean
 * paying for the same song on every listen, since the player keeps nothing. Standing in the
 * middle buys both: the bytes reach the player as they arrive and land in a file at the same
 * time, and the next listen is served from that file without touching the network.
 */
public class StreamProxy {

    /** A song this large is not a song; refusing it keeps a bad id from filling the cache. */
    private static final long MAX_BYTES = 24L * 1024 * 1024;
    private static final int KEEP = 6;
    private static final String TYPE = "audio/mp4";

    private static StreamProxy instance;

    public static synchronized StreamProxy get(Context c) {
        if (instance == null) instance = new StreamProxy(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final File dir;
    /**
     * Loopback is not private: any other app on the device can reach this port. The path
     * carries a secret minted per run, so a neighbour cannot use it to fetch ids of its own.
     */
    private final String token;
    private ServerSocket server;

    private StreamProxy(Context app) {
        this.app = app;
        this.dir = new File(app.getCacheDir(), "stream");
        dir.mkdirs();
        byte[] raw = new byte[12];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        this.token = sb.toString();
        try {
            ServerSocket s = new ServerSocket();
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            server = s;
        } catch (IOException e) {
            CrashLog.note(app, "proxy bind failed: " + e);
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                accept();
            }
        }, "nota-proxy");
        t.setDaemon(true);
        t.start();
    }

    /** The address to hand a MediaPlayer, or null when the proxy could not take a port. */
    public String url(String videoId) {
        if (server == null || !YtApi.validId(videoId)) return null;
        return "http://127.0.0.1:" + server.getLocalPort() + "/" + token + "/" + videoId;
    }

    /** Whether this song would play without the network. */
    public boolean cached(String videoId) {
        File f = whole(videoId);
        return f.isFile() && f.length() > 0;
    }

    /**
     * A kept song answers before a cached one. Both are complete files; the difference is that
     * the kept one is never pruned, so preferring it means a download stays playable offline
     * however many other songs have been streamed since.
     */
    private File whole(String videoId) {
        File kept = Downloads.get(app).file(videoId);
        if (kept.isFile() && kept.length() > 0) return kept;
        return new File(dir, videoId + ".m4a");
    }

    private void accept() {
        while (true) {
            final Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                return;
            }
            // A seek opens a second connection while the first is still being read, so each
            // one is answered on its own thread.
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        handle(client);
                    } catch (Exception ignored) {
                    } finally {
                        try {
                            client.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }, "nota-proxy-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void handle(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(client.getInputStream(), "UTF-8"), 8192);
        String request = in.readLine();
        if (request == null) return;
        String range = null;
        for (String line = in.readLine(); line != null && line.length() > 0; line = in.readLine()) {
            if (line.regionMatches(true, 0, "Range:", 0, 6)) range = line.substring(6).trim();
        }

        String[] parts = request.split(" ");
        if (parts.length < 2 || !("GET".equals(parts[0]) || "HEAD".equals(parts[0]))) {
            send(client, "405 Method Not Allowed");
            return;
        }
        String prefix = "/" + token + "/";
        if (!parts[1].startsWith(prefix)) {
            send(client, "403 Forbidden");
            return;
        }
        String id = parts[1].substring(prefix.length());
        if (!YtApi.validId(id)) {
            send(client, "404 Not Found");
            return;
        }

        boolean body = "GET".equals(parts[0]);
        File file = whole(id);
        if (file.isFile() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis());
            fromDisk(client, file, range, body);
        } else {
            fromHelper(client, id, range, body);
        }
    }

    // ---- answering from the copy ----

    private void fromDisk(Socket client, File file, String range, boolean body)
            throws IOException {
        long length = file.length();
        long start = 0, end = length - 1;
        if (range != null) {
            long[] asked = parseRange(range, length);
            if (asked == null) {
                send(client, "416 Requested Range Not Satisfiable");
                return;
            }
            start = asked[0];
            end = asked[1];
        }
        long count = end - start + 1;
        StringBuilder head = new StringBuilder();
        head.append(range == null ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 206 Partial Content\r\n");
        head.append("Content-Type: ").append(TYPE).append("\r\n");
        head.append("Accept-Ranges: bytes\r\n");
        head.append("Content-Length: ").append(count).append("\r\n");
        if (range != null) {
            head.append("Content-Range: bytes ").append(start).append('-').append(end)
                    .append('/').append(length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        OutputStream out = client.getOutputStream();
        out.write(head.toString().getBytes("UTF-8"));
        if (!body) {
            out.flush();
            return;
        }
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            raf.seek(start);
            byte[] chunk = new byte[65536];
            while (count > 0) {
                int n = raf.read(chunk, 0, (int) Math.min(chunk.length, count));
                if (n <= 0) break;
                out.write(chunk, 0, n);
                count -= n;
            }
            out.flush();
        } finally {
            raf.close();
        }
    }

    /** "bytes=100-" or "bytes=100-200", against a known length. Null when it cannot be met. */
    private static long[] parseRange(String header, long length) {
        int eq = header.indexOf('=');
        if (eq < 0) return null;
        String spec = header.substring(eq + 1).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        try {
            long start = dash == 0 ? -1 : Long.parseLong(spec.substring(0, dash).trim());
            String tail = spec.substring(dash + 1).trim();
            long end = tail.length() == 0 ? -1 : Long.parseLong(tail);
            if (start < 0) {
                // A suffix range: the last `end` bytes.
                if (end <= 0) return null;
                start = Math.max(0, length - end);
                end = length - 1;
            } else if (end < 0 || end >= length) {
                end = length - 1;
            }
            if (start > end || start >= length) return null;
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- answering from the helper ----

    /**
     * Forwards the helper's answer to the player. A request for the whole song is also written
     * to disk, and the file is kept only if every promised byte arrived: a copy that stops in
     * the middle would otherwise be served as a complete song forever after.
     */
    private void fromHelper(Socket client, String id, String range, boolean body)
            throws IOException {
        String url = YtApi.streamUrl(id);
        if (url == null) {
            send(client, "404 Not Found");
            return;
        }
        // A player asking for the song from the top says so as "bytes=0-" as often as by
        // sending no range at all, and the difference is not worth a second trip to the
        // helper: both are asked for whole, and both are worth keeping.
        boolean fromTop = range == null || openEndedFromZero(range);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        // The helper extracts before it can send, so the wait for the first byte is long.
        c.setReadTimeout(120000);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (!fromTop) c.setRequestProperty("Range", range);
        File part = null;
        boolean complete = false;
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                send(client, "502 Bad Gateway");
                return;
            }
            long promised = c.getHeaderFieldInt("Content-Length", -1);
            if (promised > MAX_BYTES) {
                send(client, "502 Bad Gateway");
                return;
            }
            StringBuilder head = new StringBuilder();
            head.append(range == null ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 206 Partial Content\r\n");
            head.append("Content-Type: ").append(TYPE).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            if (promised >= 0) head.append("Content-Length: ").append(promised).append("\r\n");
            if (range != null) {
                String contentRange = c.getHeaderField("Content-Range");
                if (contentRange == null && promised > 0) {
                    contentRange = "bytes 0-" + (promised - 1) + "/" + promised;
                }
                if (contentRange != null) {
                    head.append("Content-Range: ").append(contentRange).append("\r\n");
                }
            }
            head.append("Connection: close\r\n\r\n");
            OutputStream out = client.getOutputStream();
            out.write(head.toString().getBytes("UTF-8"));
            if (!body) {
                out.flush();
                return;
            }

            // Only a request for the song from its first byte can become the stored copy.
            if (fromTop) part = new File(dir, id + ".part");
            OutputStream copy = part == null ? null : new FileOutputStream(part);
            InputStream from = c.getInputStream();
            long written = 0;
            try {
                byte[] chunk = new byte[65536];
                int n;
                while ((n = from.read(chunk)) > 0) {
                    written += n;
                    if (written > MAX_BYTES) break;
                    out.write(chunk, 0, n);
                    if (copy != null) copy.write(chunk, 0, n);
                }
                out.flush();
            } finally {
                if (copy != null) copy.close();
                from.close();
            }
            DataSaver.get(app).addDownloaded(written);
            complete = promised >= 0 && written == promised;
        } finally {
            c.disconnect();
            if (part != null) {
                // Renamed only once whole, so a half-written file is never played as a song.
                if (!complete || !part.renameTo(whole(id))) part.delete();
                else prune();
            }
        }
    }

    /** Whether a Range header asks for everything, as "bytes=0-". */
    private static boolean openEndedFromZero(String header) {
        int eq = header.indexOf('=');
        return eq >= 0 && "0-".equals(header.substring(eq + 1).trim());
    }

    private void send(Socket client, String status) throws IOException {
        client.getOutputStream().write(("HTTP/1.1 " + status + "\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
    }

    private void prune() {
        File[] files = dir.listFiles();
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (int i = KEEP; i < files.length; i++) files[i].delete();
    }
}
