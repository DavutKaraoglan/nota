// Exercises the parts of YtStream that could only be got wrong in the port: the pattern that
// finds the identity, the unescaping that follows it, and the shape of the player request.
// Android is not involved, so it runs under plain java in Termux:
//   javac -d /tmp/ytcheck tools/YtStreamCheck.java && java -cp /tmp/ytcheck YtStreamCheck
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class YtStreamCheck {

    static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";

    public static void main(String[] args) throws Exception {
        String[] ids = args.length > 0 ? args
                : new String[]{"NAHRpEqgcL4", "KSlOGG5Ohgg", "BBdlfI0ZIf0", "dQw4w9WgXcQ"};

        long t0 = System.currentTimeMillis();
        String page = send("https://www.youtube.com/sw.js_data", null);
        Matcher m = Pattern.compile("\"(Cg[A-Za-z0-9_%-]{20,})\"").matcher(page);
        if (!m.find()) {
            System.out.println("BASARISIZ: visitorData kalibi bulunamadi");
            return;
        }
        String visitor = percentDecode(m.group(1));
        System.out.println("visitorData " + visitor.length() + " karakter, "
                + (System.currentTimeMillis() - t0) + " ms");
        if (visitor.indexOf('%') >= 0) {
            System.out.println("UYARI: cozme eksik, hala % var");
        }

        int ok = 0;
        for (String id : ids) {
            long t = System.currentTimeMillis();
            String body = "{\"context\":{\"client\":{\"clientName\":\"VISIONOS\","
                    + "\"clientVersion\":\"1.02\",\"deviceMake\":\"Apple\","
                    + "\"deviceModel\":\"RealityDevice17,1\",\"osName\":\"visionOS\","
                    + "\"osVersion\":\"26.5.23O471\",\"hl\":\"tr\",\"gl\":\"TR\","
                    + "\"visitorData\":\"" + visitor + "\"}},\"videoId\":\"" + id + "\","
                    + "\"contentCheckOk\":true,\"racyCheckOk\":true}";
            String answer = send("https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    body);
            long ms = System.currentTimeMillis() - t;
            String url = firstAudioUrl(answer);
            if (url == null) {
                System.out.println(id + "  URL YOK  " + status(answer));
                continue;
            }
            long clen = contentLength(answer);
            int code = range(url, clen - 100000, clen - 1);
            System.out.println(id + "  " + ms + " ms  clen " + clen + "  son-100KB HTTP " + code
                    + (url.contains("&n=") || url.contains("?n=") ? "  (n parametresi VAR!)" : ""));
            if (code == 206) ok++;
        }
        System.out.println(ok + "/" + ids.length + " parca duvarsiz");
    }

    /** Finds itag 140's address the way the app does, without a JSON parser on the classpath. */
    static String firstAudioUrl(String json) {
        int at = json.indexOf("\"itag\":140");
        if (at < 0) return null;
        int u = json.indexOf("\"url\":\"", at);
        if (u < 0) return null;
        int end = json.indexOf('"', u + 7);
        return json.substring(u + 7, end).replace("\\u0026", "&").replace("\\/", "/");
    }

    static String status(String json) {
        int at = json.indexOf("\"status\":\"");
        return at < 0 ? "?" : json.substring(at + 10, json.indexOf('"', at + 10));
    }

    static long contentLength(String json) {
        int at = json.indexOf("\"itag\":140");
        int c = json.indexOf("\"contentLength\":\"", at);
        if (c < 0) return -1;
        return Long.parseLong(json.substring(c + 17, json.indexOf('"', c + 17)));
    }

    /**
     * Asks for the last stretch of the file: the offset the old megabyte wall refused. The
     * offset is spelled out, because a suffix range ("the last N bytes") answers 416 here.
     */
    static int range(String url, long from, long to) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Range", "bytes=" + from + "-" + to);
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        try {
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    static String send(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Encoding", "gzip");
        if (json != null) {
            byte[] payload = json.getBytes("UTF-8");
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(payload.length);
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(payload);
        }
        InputStream in = c.getInputStream();
        if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
        in.close();
        c.disconnect();
        return out.toString("UTF-8");
    }

    static String percentDecode(String text) {
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
