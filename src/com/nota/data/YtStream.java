package com.nota.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns a video id into an address the player can open, without a helper in the middle.
 *
 * <p>YouTube hands a plain, seekable URL to the client its headset app identifies as, and that
 * client is answered without a signature puzzle to solve or an attestation token to prove: the
 * address arrives ready in the answer. Every other client either withholds the address, or
 * caps what it will send at about a megabyte, which is a minute of a song.
 */
final class YtStream {

    private YtStream() {
    }

    private static final String PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    private static final String VISITOR_URL = "https://www.youtube.com/sw.js_data";

    /**
     * Apple's headset client. The version numbers travel with the client identity and are
     * checked against it, so they are copied whole rather than made up; when YouTube retires
     * this build, yt-dlp's {@code extractor/youtube/_base.py} carries the replacement.
     */
    private static final String CLIENT_NAME = "VISIONOS";
    private static final String CLIENT_VERSION = "1.02";
    static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/26.0 Safari/605.1.15";

    /** AAC at 128 kbit, the format every Android decoder reads and the one the app asks for. */
    private static final int PREFERRED_ITAG = 140;

    private static final int TIMEOUT_MS = 20000;
    /** An address carries the moment it dies; it is dropped early enough to still be usable. */
    private static final long EXPIRY_MARGIN_MS = 5 * 60 * 1000;

    /** Identifies the caller to YouTube. Without it the player endpoint answers LOGIN_REQUIRED. */
    private static String visitorData;
    /**
     * The identity outlives the run that asked for it, so it is kept where the next launch can
     * find it: fetching one costs a second or two, and that second would otherwise be paid on
     * the first song of every launch, which is the one wait a listener notices.
     */
    private static final String VISITOR_KEY = "yt_visitor";
    private static final String VISITOR_AT_KEY = "yt_visitor_at";
    private static final long VISITOR_LIFE_MS = 7L * 24 * 60 * 60 * 1000;

    private static final Map<String, Resolved> CACHE = new HashMap<>();
    /**
     * One lock per song being looked up. A player opening two connections to the same song asks
     * twice at once, and without this both would pay the full lookup; the second waits instead
     * and finds the answer already in hand.
     */
    private static final Map<String, Object> LOOKUPS = new HashMap<>();

    private static class Resolved {
        final String url;
        final long deadline;

        Resolved(String url, long deadline) {
            this.url = url;
            this.deadline = deadline;
        }
    }

    /**
     * Blocking: call it from a worker thread.
     *
     * @return an address for the song's audio, or null when YouTube would not give one
     */
    static String url(Context app, String videoId) {
        if (!YtApi.validId(videoId)) return null;
        String hit = remembered(videoId);
        if (hit != null) return hit;
        Object lock;
        synchronized (LOOKUPS) {
            lock = LOOKUPS.get(videoId);
            if (lock == null) LOOKUPS.put(videoId, lock = new Object());
        }
        synchronized (lock) {
            try {
                String waited = remembered(videoId);
                if (waited != null) return waited;
                String url = resolve(app, videoId, true);
                if (url == null) return null;
                synchronized (CACHE) {
                    CACHE.put(videoId, new Resolved(url, deadline(url)));
                }
                return url;
            } finally {
                synchronized (LOOKUPS) {
                    LOOKUPS.remove(videoId);
                }
            }
        }
    }

    /** The address found earlier, while it still has life in it. */
    private static String remembered(String videoId) {
        synchronized (CACHE) {
            Resolved hit = CACHE.get(videoId);
            return hit != null && System.currentTimeMillis() < hit.deadline ? hit.url : null;
        }
    }

    /**
     * Asks YouTube who we are before anything is played, so the first song does not wait for it.
     *
     * <p>Blocking: call it from a worker thread.
     */
    static void warmSession(Context app) {
        visitor(app);
    }

    /**
     * @param mayRetry whether a refusal is worth a second attempt with a fresh identity, which
     *                 is what an expired one looks like from here
     */
    private static String resolve(Context app, String videoId, boolean mayRetry) {
        String visitor = visitor(app);
        if (visitor == null) return null;
        String answer;
        try {
            JSONObject client = new JSONObject()
                    .put("clientName", CLIENT_NAME)
                    .put("clientVersion", CLIENT_VERSION)
                    .put("deviceMake", "Apple")
                    .put("deviceModel", "RealityDevice17,1")
                    .put("osName", "visionOS")
                    .put("osVersion", "26.5.23O471")
                    .put("hl", "tr")
                    .put("gl", "TR")
                    .put("visitorData", visitor);
            String body = new JSONObject()
                    .put("context", new JSONObject().put("client", client))
                    .put("videoId", videoId)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true)
                    .toString();
            answer = Net.post(PLAYER_URL, body, USER_AGENT, TIMEOUT_MS);
        } catch (Exception e) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(answer);
            String status = root.optJSONObject("playabilityStatus") == null
                    ? "" : root.getJSONObject("playabilityStatus").optString("status");
            if (!"OK".equals(status)) {
                if (mayRetry) {
                    forget(app);
                    return resolve(app, videoId, false);
                }
                return null;
            }
            return pickAudio(root.optJSONObject("streamingData"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String pickAudio(JSONObject streaming) {
        if (streaming == null) return null;
        JSONArray formats = streaming.optJSONArray("adaptiveFormats");
        if (formats == null) return null;
        String best = null;
        int bestBitrate = -1;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (f == null) continue;
            String url = f.optString("url", null);
            if (url == null || url.isEmpty()) continue;
            if (!f.optString("mimeType").startsWith("audio/mp4")) continue;
            if (f.optInt("itag") == PREFERRED_ITAG) return url;
            int bitrate = f.optInt("bitrate");
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate;
                best = url;
            }
        }
        return best;
    }

    /** The address says when it stops working; anything unreadable is treated as short-lived. */
    private static long deadline(String url) {
        long fallback = System.currentTimeMillis() + 60 * 60 * 1000;
        int at = url.indexOf("expire=");
        if (at < 0) return fallback;
        int from = at + "expire=".length();
        int to = from;
        while (to < url.length() && Character.isDigit(url.charAt(to))) to++;
        if (to == from) return fallback;
        try {
            return Long.parseLong(url.substring(from, to)) * 1000 - EXPIRY_MARGIN_MS;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static synchronized String visitor(Context app) {
        if (visitorData != null) return visitorData;
        long kept = Prefs.getLong(app, VISITOR_AT_KEY, 0);
        if (System.currentTimeMillis() - kept < VISITOR_LIFE_MS) {
            String stored = Prefs.getString(app, VISITOR_KEY, null);
            if (stored != null && stored.length() > 0) return visitorData = stored;
        }
        try {
            String body = Net.get(VISITOR_URL, TIMEOUT_MS);
            // The answer opens with a guard against being read as script, and the identity is
            // the one long base64 string in the tree that starts the way these always do.
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"(Cg[A-Za-z0-9_%-]{20,})\"").matcher(body);
            if (!m.find()) return null;
            visitorData = percentDecode(m.group(1));
            Prefs.setString(app, VISITOR_KEY, visitorData);
            Prefs.setLong(app, VISITOR_AT_KEY, System.currentTimeMillis());
            return visitorData;
        } catch (Exception e) {
            return null;
        }
    }

    /** Drops the identity everywhere, so the next lookup asks YouTube for a fresh one. */
    private static synchronized void forget(Context app) {
        visitorData = null;
        Prefs.setLong(app, VISITOR_AT_KEY, 0);
    }

    /**
     * Decodes the escaping a browser would undo. A plus sign is left alone: it is a character of
     * the identity itself here, not the space a form field would have meant by it.
     */
    private static String percentDecode(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '%' && i + 2 < text.length()) {
                int value = Character.digit(text.charAt(i + 1), 16) * 16
                        + Character.digit(text.charAt(i + 2), 16);
                if (value >= 0) {
                    out.append((char) value);
                    i += 2;
                    continue;
                }
            }
            out.append(ch);
        }
        return out.toString();
    }
}
