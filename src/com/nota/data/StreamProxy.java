package com.nota.data;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves remote audio to the player over the loopback address, keeping a copy as it goes.
 *
 * <p>YouTube's own address could be handed to a MediaPlayer as it is, and the song would play.
 * It would also be paid for on every listen, because the player keeps nothing. Standing in the
 * middle buys both: the bytes reach the player as they arrive and land in a file at the same
 * time, and the next listen is served from that file without touching the network.
 */
public class StreamProxy {

    /** A song this large is not a song; refusing it keeps a bad id from filling the cache. */
    private static final long MAX_BYTES = 24L * 1024 * 1024;
    /** Roughly the first ten seconds at the bitrate YouTube sends. */
    private static final int HEAD_BYTES = 192 * 1024;
    /** How much of the song is asked for at once. Large enough to keep the line busy. */
    private static final int CHUNK_BYTES = 1024 * 1024;
    private static final int KEEP = 6;
    private static final String TYPE = "audio/mp4";
    /** Marks a request made on nobody's behalf, so it is not read as the player listening. */
    private static final String AHEAD_HEADER = "X-Ahead";
    /** A gap this long in what the player takes means it is buffered rather than starving. */
    private static final long QUIET_MS = 1000;
    /** However busy the player stays, a song waiting its turn is not held back longer than this. */
    private static final long HOLD_OFF_MS = 45000;
    private static final int REQUEST_TIMEOUT_MS = 5000;
    private static final int MAX_REQUEST_BYTES = 4096;
    private static final int MAX_REQUEST_LINES = 48;
    private static final int MAX_CONNECTIONS = 8;

    private static StreamProxy instance;

    public static synchronized StreamProxy get(Context c) {
        if (instance == null) instance = new StreamProxy(c.getApplicationContext());
        return instance;
    }

    /**
     * A song being pulled off the network right now, and how much of it has reached the disk.
     *
     * <p>A player that seeks back to the beginning does not rewind the connection it is reading:
     * it drops it and opens another. Without this, each of those would start the song over from
     * YouTube, so the same song came down three or four times at once and each copy crawled.
     */
    private static final class Fetching {
        final File part;
        final long total;
        volatile long written;
        volatile boolean done;

        Fetching(File part, long total) {
            this.part = part;
            this.total = total;
        }
    }

    private final Context app;
    private final File dir;
    /** By video id, the fetch a second request for the same song should read along with. */
    private final Map<String, Fetching> fetching = new HashMap<String, Fetching>();
    /**
     * Loopback is not private: any other app on the device can reach this port. The path
     * carries a secret minted per run, so a neighbour cannot use it to fetch ids of its own.
     */
    private final String token;
    private final AtomicInteger live = new AtomicInteger();
    private volatile ServerSocket server;
    /** When the player last took bytes off the network, rather than off the disk. */
    private volatile long feeding;

    private StreamProxy(Context app) {
        this.app = app;
        this.dir = dir(app);
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

    /** The address to hand a MediaPlayer, or null when the proxy is not listening. */
    public String url(String videoId) {
        ServerSocket s = server;
        if (s == null || !YtApi.validId(videoId)) return null;
        return "http://127.0.0.1:" + s.getLocalPort() + "/" + token + "/" + videoId;
    }

    /** Whether this song would play without the network. */
    public boolean cached(String videoId) {
        return cached(app, videoId);
    }

    /**
     * The same question asked of the files alone. A list being drawn wants to know what it can
     * offer, and it should not have to take a port to find out.
     */
    public static boolean cached(Context c, String videoId) {
        File f = whole(c.getApplicationContext(), videoId);
        return f.isFile() && f.length() > 0;
    }

    private File whole(String videoId) {
        return whole(app, videoId);
    }

    /**
     * Pulls a whole song in before anything asks to hear it, so a swipe to it opens from disk.
     * It is fetched through the proxy's own address rather than from YouTube, which puts the
     * song on disk by the very path a played one takes; the bytes read here are thrown away.
     *
     * <p>Blocking: call it from a worker thread.
     *
     * @return whether the song is on disk afterwards
     */
    public boolean preload(String videoId) throws IOException {
        if (cached(videoId)) return true;
        String address = url(videoId);
        if (address == null) return false;
        holdOff();
        if (cached(videoId)) return true;
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(180000);
        c.setRequestProperty(AHEAD_HEADER, "1");
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            InputStream in = c.getInputStream();
            try {
                byte[] chunk = new byte[65536];
                while (in.read(chunk) > 0) ;
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
        return cached(videoId);
    }

    /**
     * Pulls in the opening of a song, which is what a swipe to it has to wait for. The whole
     * song is worth more and is fetched after this, but it waits for a quiet line and a long
     * song holds that up; the opening is small enough to be taken straight away.
     *
     * <p>The total length goes in the name. The rest is asked for by range later, and the two
     * halves are one song only while YouTube still serves the file this length came from.
     *
     * <p>Blocking: call it from a worker thread.
     *
     * @return whether the opening is on disk afterwards
     */
    public boolean preloadHead(String id) throws IOException {
        if (cached(id) || headFile(id) != null) return true;
        String url = YtApi.streamUrl(app, id);
        if (url == null) return false;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("User-Agent", YtStream.USER_AGENT);
        c.setRequestProperty("Range", "bytes=0-" + (HEAD_BYTES - 1));
        File part = new File(dir, id + "." + System.nanoTime() + ".headpart");
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                return false;
            }
            long total = totalOf(c);
            if (total <= 0 || total > MAX_BYTES) return false;
            long want = Math.min(HEAD_BYTES, total);
            long written = 0;
            InputStream from = c.getInputStream();
            OutputStream copy = new FileOutputStream(part);
            try {
                byte[] chunk = new byte[65536];
                int n;
                while (written < want && (n = from.read(chunk, 0,
                        (int) Math.min(chunk.length, want - written))) > 0) {
                    copy.write(chunk, 0, n);
                    written += n;
                }
            } finally {
                copy.close();
                from.close();
            }
            DataSaver.get(app).addDownloaded(written);
            // A short opening is no opening: the splice later would leave a hole in the song.
            if (written != want) return false;
            return part.renameTo(new File(dir, id + "." + total + ".head"));
        } finally {
            c.disconnect();
            part.delete();
        }
    }

    /** The length of the whole song behind a partial answer, or -1. */
    private static long totalOf(HttpURLConnection c) {
        String contentRange = c.getHeaderField("Content-Range");
        if (contentRange != null) {
            int slash = contentRange.indexOf('/');
            if (slash > 0) {
                try {
                    return Long.parseLong(contentRange.substring(slash + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return -1;
        }
        return c.getHeaderFieldInt("Content-Length", -1);
    }

    /** The stored opening of a song, named `<id>.<total>.head`, or null. */
    private File headFile(final String id) {
        File[] found = dir.listFiles(new FilenameFilter() {
            public boolean accept(File where, String name) {
                return name.startsWith(id + ".") && name.endsWith(".head");
            }
        });
        return found == null || found.length == 0 ? null : found[0];
    }

    private static long headTotal(File head) {
        String[] parts = head.getName().split("\\.");
        if (parts.length != 3) return -1;
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Waits while the player is pulling a song off the network. Two songs sharing a thin line
     * means the one being listened to arrives at half speed, and a song fetched early is worth
     * nothing next to the one playing now. The wait ends by itself once the player is buffered,
     * and it is capped, so a long song never keeps the next one from being fetched at all.
     */
    private void holdOff() {
        long until = System.currentTimeMillis() + HOLD_OFF_MS;
        while (System.currentTimeMillis() < until
                && System.currentTimeMillis() - feeding < QUIET_MS) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * A kept song answers before a cached one. Both are complete files; the difference is that
     * the kept one is never pruned, so preferring it means a download stays playable offline
     * however many other songs have been streamed since.
     */
    private static File whole(Context app, String videoId) {
        File kept = Downloads.get(app).file(videoId);
        if (kept.isFile() && kept.length() > 0) return kept;
        return new File(dir(app), videoId + ".m4a");
    }

    private static File dir(Context app) {
        return new File(app.getCacheDir(), "stream");
    }

    private void accept() {
        while (true) {
            ServerSocket listening = server;
            if (listening == null) return;
            final Socket client;
            try {
                client = listening.accept();
            } catch (IOException e) {
                if (listening.isClosed()) {
                    server = null;
                    return;
                }
                continue;
            }
            if (live.get() >= MAX_CONNECTIONS) {
                try {
                    client.close();
                } catch (IOException ignored) {
                }
                continue;
            }
            live.incrementAndGet();
            // A seek opens a second connection while the first is still being read, so each
            // one is answered on its own thread.
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        handle(client);
                    } catch (Exception ignored) {
                    } finally {
                        live.decrementAndGet();
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

    private static String requestLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(96);
        int c = in.read();
        if (c == -1) return null;
        while (c != -1 && c != '\n') {
            if (c != '\r') sb.append((char) c);
            if (sb.length() >= MAX_REQUEST_BYTES) throw new IOException("request too long");
            c = in.read();
        }
        return sb.toString();
    }

    private void handle(Socket client) throws IOException {
        client.setSoTimeout(REQUEST_TIMEOUT_MS);
        InputStream in = new BufferedInputStream(client.getInputStream(), 1024);
        String request = requestLine(in);
        if (request == null) return;

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

        String range = null;
        boolean ahead = false;
        for (int i = 0; i < MAX_REQUEST_LINES; i++) {
            String line = requestLine(in);
            if (line == null || line.length() == 0) break;
            if (line.regionMatches(true, 0, "Range:", 0, 6)) range = line.substring(6).trim();
            else if (line.regionMatches(true, 0, AHEAD_HEADER, 0, AHEAD_HEADER.length())) ahead = true;
        }

        boolean body = "GET".equals(parts[0]);
        File file = whole(id);
        if (file.isFile() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis());
            fromDisk(client, file, range, body);
        } else {
            fromHelper(client, id, range, body, ahead);
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

    // ---- answering from YouTube ----

    /**
     * Forwards YouTube's answer to the player. A request for the whole song is also written
     * to disk, and the file is kept only if every promised byte arrived: a copy that stops in
     * the middle would otherwise be served as a complete song forever after.
     */
    private void fromHelper(Socket client, String id, String range, boolean body, boolean ahead)
            throws IOException {
        long began = System.currentTimeMillis();
        String url = YtApi.streamUrl(app, id);
        long resolved = System.currentTimeMillis() - began;
        if (url == null) {
            send(client, "404 Not Found");
            return;
        }
        // A player asking for the song from the top says so as "bytes=0-" as often as by
        // sending no range at all, and the difference is not worth a second trip upstream:
        // both are asked for whole, and both are worth keeping.
        boolean fromTop = range == null || openEndedFromZero(range);
        if (fromTop && body) {
            Fetching live;
            synchronized (fetching) {
                live = fetching.get(id);
            }
            if (live != null) {
                fromLive(client, live, ahead);
                return;
            }
            File head = headFile(id);
            if (head != null) {
                long total = headTotal(head);
                if (head.length() > 0 && total > head.length()) {
                    fromHead(client, id, head, total, ahead);
                    return;
                }
                head.delete();
            }
        }
        long start = 0;
        long askedEnd = -1;
        if (!fromTop) {
            long[] asked = askedRange(range);
            if (asked == null) {
                send(client, "416 Range Not Satisfiable");
                return;
            }
            start = asked[0];
            askedEnd = asked[1];
        }
        File part = null;
        Fetching live = null;
        boolean complete = false;
        HttpURLConnection c = chunk(url, start);
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                send(client, "502 Bad Gateway");
                return;
            }
            long total = totalOf(c);
            if (total <= 0 || total > MAX_BYTES) {
                send(client, "502 Bad Gateway");
                return;
            }
            long end = askedEnd < 0 || askedEnd >= total ? total - 1 : askedEnd;
            if (start > end) {
                send(client, "416 Range Not Satisfiable");
                return;
            }
            long promised = end - start + 1;

            StringBuilder head = new StringBuilder();
            head.append(range == null ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 206 Partial Content\r\n");
            head.append("Content-Type: ").append(TYPE).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            head.append("Content-Length: ").append(promised).append("\r\n");
            if (range != null) {
                head.append("Content-Range: bytes ").append(start).append('-').append(end)
                        .append('/').append(total).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            OutputStream out = client.getOutputStream();
            out.write(head.toString().getBytes("UTF-8"));
            if (!body) {
                out.flush();
                return;
            }

            // Only a request for the song from its first byte can become the stored copy. The
            // name carries the moment it began, because a song being pulled in ahead of time and
            // the same song being played can be written at once, and one file cannot hold both.
            if (fromTop) part = new File(dir, id + "." + System.nanoTime() + ".part");
            OutputStream copy = part == null ? null : new FileOutputStream(part);
            if (part != null) {
                live = new Fetching(part, total);
                synchronized (fetching) {
                    fetching.put(id, live);
                }
            }
            long written = 0;
            // A player that walks away is not a reason to stop: the bytes still on the way are
            // the ones the connection it opens next will ask for, and they are cheaper to keep
            // coming than to fetch again from the first byte.
            boolean listening = true;
            try {
                byte[] buffer = new byte[65536];
                while (written < promised) {
                    if (c == null) {
                        c = chunk(url, start + written);
                        int next = c.getResponseCode();
                        if (next != HttpURLConnection.HTTP_OK
                                && next != HttpURLConnection.HTTP_PARTIAL) {
                            break;
                        }
                    }
                    long before = written;
                    InputStream from = c.getInputStream();
                    try {
                        int n;
                        while (written < promised && (n = from.read(buffer)) > 0) {
                            if (!ahead) feeding = System.currentTimeMillis();
                            written += n;
                            if (copy != null) copy.write(buffer, 0, n);
                            if (live != null) live.written = written;
                            if (listening) {
                                try {
                                    out.write(buffer, 0, n);
                                } catch (IOException gone) {
                                    listening = false;
                                }
                            }
                            if (!listening && copy == null) break;
                        }
                    } finally {
                        from.close();
                        c.disconnect();
                        c = null;
                    }
                    if (!listening && copy == null) break;
                    // A stretch that answered with nothing will not answer with more if asked
                    // again, and asking again is how this turns into a loop that never ends.
                    if (written == before) break;
                }
                if (listening) out.flush();
            } catch (IOException e) {
                if (listening) throw e;
            } finally {
                if (copy != null) copy.close();
            }
            DataSaver.get(app).addDownloaded(written);
            long spent = System.currentTimeMillis() - began;
            // Worth a line only when the song came down slower than it plays, which is what a
            // throttled connection looks like and the one thing that makes a song stutter.
            if (spent > 2000 && written * 1000 / spent < 24 * 1024) {
                CrashLog.note(app, "yt slow " + (written / 1024) + "KB in " + spent + "ms");
            }
            complete = written == promised && fromTop;
        } finally {
            if (c != null) c.disconnect();
            if (live != null) {
                live.done = true;
                synchronized (fetching) {
                    fetching.remove(id);
                }
            }
            if (part != null) {
                // Renamed only once whole, so a half-written file is never played as a song.
                if (!complete || !part.renameTo(whole(id))) part.delete();
                else prune();
            }
        }
    }

    /**
     * Opens one stretch of the song upstream.
     *
     * <p>A megabyte at a time, never open-ended: asked for the whole file in one request,
     * YouTube serves it at about the speed it plays — thirty kilobytes a second here, against
     * nine hundred when the same file is asked for in pieces. The first note of a song waits on
     * this, so the difference is the difference between starting at once and starting in five
     * seconds.
     */
    private HttpURLConnection chunk(String url, long from) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        // Generous, because this is read over whatever line the phone happens to be on.
        c.setReadTimeout(120000);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("User-Agent", YtStream.USER_AGENT);
        c.setRequestProperty("Range", "bytes=" + from + "-" + (from + CHUNK_BYTES - 1));
        return c;
    }

    /** "bytes=100-" or "bytes=100-200" as start and end, the end being -1 when open. */
    private static long[] askedRange(String header) {
        int eq = header.indexOf('=');
        if (eq < 0) return null;
        String spec = header.substring(eq + 1).trim();
        int dash = spec.indexOf('-');
        if (dash <= 0) return null;
        try {
            long start = Long.parseLong(spec.substring(0, dash).trim());
            String tail = spec.substring(dash + 1).trim();
            return new long[]{start, tail.length() == 0 ? -1 : Long.parseLong(tail)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Answers a second request for a song already coming down, by reading the file the first
     * one is filling. What has landed is served at once and the rest as it arrives, so a player
     * that reopened the song reads back over ground the phone has already paid for.
     */
    private void fromLive(Socket client, Fetching live, boolean ahead) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 200 OK\r\n");
        head.append("Content-Type: ").append(TYPE).append("\r\n");
        head.append("Accept-Ranges: bytes\r\n");
        head.append("Content-Length: ").append(live.total).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        OutputStream out = client.getOutputStream();
        out.write(head.toString().getBytes("UTF-8"));
        RandomAccessFile from = new RandomAccessFile(live.part, "r");
        long at = 0;
        try {
            byte[] chunk = new byte[65536];
            while (at < live.total) {
                long have = live.written;
                if (at >= have) {
                    // Caught up with the download; it is still running, so there will be more.
                    if (live.done) break;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                from.seek(at);
                int n = from.read(chunk, 0, (int) Math.min(chunk.length, have - at));
                if (n <= 0) break;
                if (!ahead) feeding = System.currentTimeMillis();
                out.write(chunk, 0, n);
                at += n;
            }
            out.flush();
        } finally {
            from.close();
        }
    }

    /**
     * Answers with the opening off the disk and the rest off YouTube. The player has ten
     * seconds of song before anything has been asked of the network, which is the wait a swipe
     * used to be, and the two halves land in the stored copy as one file.
     */
    private void fromHead(Socket client, String id, File head, long total, boolean ahead)
            throws IOException {
        String url = YtApi.streamUrl(app, id);
        if (url == null) {
            send(client, "404 Not Found");
            return;
        }
        long headLen = head.length();
        OutputStream out = client.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + TYPE + "\r\n"
                + "Accept-Ranges: bytes\r\n"
                + "Content-Length: " + total + "\r\n"
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));

        File part = new File(dir, id + "." + System.nanoTime() + ".part");
        OutputStream copy = new FileOutputStream(part);
        Fetching live = new Fetching(part, total);
        synchronized (fetching) {
            fetching.put(id, live);
        }
        HttpURLConnection c = null;
        boolean complete = false;
        // The player may drop this connection and open another; the song keeps coming down
        // either way, and the connection that follows reads it off the disk.
        boolean listening = true;
        try {
            RandomAccessFile stored = new RandomAccessFile(head, "r");
            try {
                byte[] chunk = new byte[65536];
                long left = headLen;
                long sent = 0;
                int n;
                while (left > 0 && (n = stored.read(chunk, 0,
                        (int) Math.min(chunk.length, left))) > 0) {
                    copy.write(chunk, 0, n);
                    sent += n;
                    live.written = sent;
                    if (listening) {
                        try {
                            out.write(chunk, 0, n);
                        } catch (IOException gone) {
                            listening = false;
                        }
                    }
                    left -= n;
                }
                if (listening) out.flush();
            } finally {
                stored.close();
            }

            c = chunk(url, headLen);
            // A different length means YouTube is serving a different file, and half of
            // each is worse than neither: the opening is dropped so the next try starts clean.
            if (c.getResponseCode() != HttpURLConnection.HTTP_PARTIAL || totalOf(c) != total) {
                head.delete();
                return;
            }
            long written = headLen;
            try {
                byte[] buffer = new byte[65536];
                while (written < total) {
                    if (c == null) {
                        c = chunk(url, written);
                        if (c.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) break;
                    }
                    long before = written;
                    InputStream from = c.getInputStream();
                    try {
                        int n;
                        while (written < total && (n = from.read(buffer)) > 0) {
                            if (!ahead) feeding = System.currentTimeMillis();
                            written += n;
                            copy.write(buffer, 0, n);
                            live.written = written;
                            if (listening) {
                                try {
                                    out.write(buffer, 0, n);
                                } catch (IOException gone) {
                                    listening = false;
                                }
                            }
                        }
                    } finally {
                        from.close();
                        c.disconnect();
                        c = null;
                    }
                    if (written == before) break;
                }
                if (listening) out.flush();
            } catch (IOException e) {
                if (listening) throw e;
            }
            DataSaver.get(app).addDownloaded(written - headLen);
            complete = written == total;
        } finally {
            copy.close();
            live.done = true;
            synchronized (fetching) {
                fetching.remove(id);
            }
            if (c != null) c.disconnect();
            if (complete && part.renameTo(whole(id))) {
                head.delete();
                prune();
            } else {
                part.delete();
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
        // Half-written files are left alone: one of them is a song still arriving. Openings are
        // counted apart from whole songs, since one of each can stand for the same song.
        keepNewest(".m4a");
        keepNewest(".head");
    }

    private void keepNewest(final String suffix) {
        File[] files = dir.listFiles(new FilenameFilter() {
            public boolean accept(File where, String name) {
                return name.endsWith(suffix);
            }
        });
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (int i = KEEP; i < files.length; i++) files[i].delete();
    }
}
