package com.nota.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import com.nota.model.Track;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class Backup {

    public static class Result {
        public int favorites;
        public int plays;
        public int playlists;
        public int missingLocal;
    }

    private static final String APP = "nota";
    private static final int FORMAT = 1;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int HISTORY_KEEP = 500;
    private static final int EVENTS_KEEP = 2000;

    public static void write(Context c, OutputStream out) throws IOException {
        try {
            byte[] body = build(c).toString().getBytes(StandardCharsets.UTF_8);
            out.write(body);
            out.flush();
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static JSONObject build(Context c) throws JSONException {
        SQLiteDatabase db = Db.get(c).getReadableDatabase();
        Set<String> keys = new HashSet<String>();
        JSONObject root = new JSONObject();
        root.put("app", APP);
        root.put("format", FORMAT);
        root.put("exported_at", System.currentTimeMillis());

        JSONArray favorites = new JSONArray();
        Cursor cur = db.rawQuery("SELECT track_key, added FROM favorites", null);
        try {
            while (cur.moveToNext()) {
                keys.add(cur.getString(0));
                favorites.put(new JSONObject().put("key", cur.getString(0))
                        .put("added", cur.getLong(1)));
            }
        } finally {
            cur.close();
        }
        root.put("favorites", favorites);

        JSONArray history = new JSONArray();
        cur = db.rawQuery("SELECT track_key, played_at FROM history ORDER BY played_at", null);
        try {
            while (cur.moveToNext()) {
                keys.add(cur.getString(0));
                history.put(new JSONObject().put("key", cur.getString(0))
                        .put("at", cur.getLong(1)));
            }
        } finally {
            cur.close();
        }
        root.put("history", history);

        JSONArray plays = new JSONArray();
        cur = db.rawQuery("SELECT track_key, plays, last_at, heard_ms FROM play_counts", null);
        try {
            while (cur.moveToNext()) {
                keys.add(cur.getString(0));
                plays.put(new JSONObject().put("key", cur.getString(0))
                        .put("plays", cur.getInt(1))
                        .put("last_at", cur.getLong(2))
                        .put("heard_ms", cur.getLong(3)));
            }
        } finally {
            cur.close();
        }
        root.put("plays", plays);

        JSONArray events = new JSONArray();
        cur = db.rawQuery("SELECT track_key, kind, score, at FROM events ORDER BY at", null);
        try {
            while (cur.moveToNext()) {
                keys.add(cur.getString(0));
                events.put(new JSONObject().put("key", cur.getString(0))
                        .put("kind", cur.getInt(1))
                        .put("score", cur.getInt(2))
                        .put("at", cur.getLong(3)));
            }
        } finally {
            cur.close();
        }
        root.put("events", events);

        JSONArray transitions = new JSONArray();
        cur = db.rawQuery("SELECT from_key, to_key, weight, at FROM transitions", null);
        try {
            while (cur.moveToNext()) {
                keys.add(cur.getString(0));
                keys.add(cur.getString(1));
                transitions.put(new JSONObject().put("from", cur.getString(0))
                        .put("to", cur.getString(1))
                        .put("weight", cur.getInt(2))
                        .put("at", cur.getLong(3)));
            }
        } finally {
            cur.close();
        }
        root.put("transitions", transitions);

        JSONArray playlists = new JSONArray();
        Db store = Db.get(c);
        cur = db.rawQuery("SELECT id, name, created, updated FROM playlists", null);
        try {
            while (cur.moveToNext()) {
                JSONArray items = new JSONArray();
                for (String k : store.playlistKeys(cur.getLong(0))) {
                    keys.add(k);
                    items.put(k);
                }
                playlists.put(new JSONObject().put("name", cur.getString(1))
                        .put("created", cur.getLong(2))
                        .put("updated", cur.getLong(3))
                        .put("keys", items));
            }
        } finally {
            cur.close();
        }
        root.put("playlists", playlists);

        root.put("tracks", describe(c, keys));
        root.put("follows", new JSONArray(Follows.all(c)));
        root.put("muted", new JSONArray(Muted.all(c)));
        return root;
    }

    private static JSONObject describe(Context c, Set<String> keys) throws JSONException {
        Set<String> sourceIds = new HashSet<String>();
        for (String k : keys) if (k.startsWith("o:")) sourceIds.add(k.substring(2));
        Map<String, Track> remote = Db.get(c).online(sourceIds);
        MediaLibrary lib = MediaLibrary.get();
        JSONObject out = new JSONObject();
        for (String k : keys) {
            Track t = k.startsWith("o:") ? remote.get(k.substring(2)) : lib.byKey(k);
            if (t == null) continue;
            JSONObject o = new JSONObject();
            o.put("title", t.title);
            o.put("artist", t.artist);
            o.put("album", t.album);
            o.put("duration", t.durationMs);
            if (t.isRemote()) {
                o.put("art", t.artUrl);
                o.put("track_no", t.trackNo);
                o.put("year", t.year);
                if (YtApi.videoId(t) == null) o.put("url", t.data);
            } else {
                o.put("path", t.data);
            }
            out.put(k, o);
        }
        return out;
    }

    public static Result read(Context c, InputStream in) throws IOException {
        JSONObject root;
        try {
            root = new JSONObject(new String(slurp(in), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        if (!APP.equals(root.optString("app")) || root.optInt("format") != FORMAT) {
            throw new IOException("not a nota backup");
        }
        Result result = new Result();
        Db store = Db.get(c);
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            Map<String, String> keys = remap(db, root.optJSONObject("tracks"), result);
            mergeFavorites(db, root.optJSONArray("favorites"), keys, result);
            mergeHistory(db, root.optJSONArray("history"), keys);
            mergePlays(db, root.optJSONArray("plays"), keys, result);
            mergeEvents(db, root.optJSONArray("events"), keys);
            mergeTransitions(db, root.optJSONArray("transitions"), keys);
            mergePlaylists(store, db, root.optJSONArray("playlists"), keys, result);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        Follows.addAll(c, strings(root.optJSONArray("follows")));
        Muted.addAll(c, strings(root.optJSONArray("muted")));
        store.reloaded();
        return result;
    }

    private static byte[] slurp(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16 * 1024];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_BYTES) throw new IOException("backup too large");
        }
        return buf.toByteArray();
    }

    private static Map<String, String> remap(SQLiteDatabase db, JSONObject tracks, Result result) {
        Map<String, String> out = new HashMap<String, String>();
        if (tracks == null) return out;
        Map<String, String> byPath = new HashMap<String, String>();
        Map<String, String> byName = new HashMap<String, String>();
        for (Track t : MediaLibrary.get().tracks()) {
            if (t.data != null) byPath.put(t.data, t.key());
            byName.put(nameKey(t.title, t.artist), t.key());
        }
        JSONArray names = tracks.names();
        if (names == null) return out;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i);
            JSONObject o = tracks.optJSONObject(key);
            if (o == null) continue;
            if (key.startsWith("o:")) {
                if (saveOnline(db, key.substring(2), o)) out.put(key, key);
            } else if (key.startsWith("l:")) {
                String local = byPath.get(o.optString("path"));
                if (local == null) local = byName.get(nameKey(o.optString("title"),
                        o.optString("artist")));
                if (local != null) out.put(key, local);
                else result.missingLocal++;
            }
        }
        return out;
    }

    private static String nameKey(String title, String artist) {
        return (title == null ? "" : title.trim().toLowerCase(Locale.ROOT)) + "\n"
                + (artist == null ? "" : artist.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean saveOnline(SQLiteDatabase db, String sourceId, JSONObject o) {
        boolean youtube = sourceId.startsWith(YtApi.PREFIX);
        if (youtube && !YtApi.validId(sourceId.substring(YtApi.PREFIX.length()))) return false;
        String url = o.optString("url", null);
        if (!youtube && url == null) return false;
        ContentValues v = new ContentValues();
        v.put("source_id", sourceId);
        v.put("title", o.optString("title", null));
        v.put("artist", o.optString("artist", null));
        v.put("album", o.optString("album", null));
        v.put("url", url);
        v.put("art", o.optString("art", null));
        v.put("duration", o.optLong("duration"));
        v.put("track_no", o.optInt("track_no"));
        v.put("year", o.optInt("year"));
        v.put("added", System.currentTimeMillis());
        db.insertWithOnConflict("online_tracks", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return true;
    }

    private static void mergeFavorites(SQLiteDatabase db, JSONArray rows,
                                       Map<String, String> keys, Result result) {
        if (rows == null) return;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            String key = o == null ? null : keys.get(o.optString("key"));
            if (key == null) continue;
            ContentValues v = new ContentValues();
            v.put("track_key", key);
            v.put("added", o.optLong("added", System.currentTimeMillis()));
            if (db.insertWithOnConflict("favorites", null, v,
                    SQLiteDatabase.CONFLICT_IGNORE) != -1) result.favorites++;
        }
    }

    private static void mergeHistory(SQLiteDatabase db, JSONArray rows, Map<String, String> keys) {
        if (rows == null) return;
        SQLiteStatement exists = db.compileStatement(
                "SELECT COUNT(*) FROM history WHERE track_key = ? AND played_at = ?");
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.optJSONObject(i);
                String key = o == null ? null : keys.get(o.optString("key"));
                long at = o == null ? 0 : o.optLong("at");
                if (key == null || at <= 0) continue;
                exists.bindString(1, key);
                exists.bindLong(2, at);
                if (exists.simpleQueryForLong() > 0) continue;
                ContentValues v = new ContentValues();
                v.put("track_key", key);
                v.put("played_at", at);
                db.insert("history", null, v);
            }
        } finally {
            exists.close();
        }
        db.execSQL("DELETE FROM history WHERE id NOT IN "
                + "(SELECT id FROM history ORDER BY played_at DESC LIMIT " + HISTORY_KEEP + ")");
    }

    private static void mergePlays(SQLiteDatabase db, JSONArray rows,
                                   Map<String, String> keys, Result result) {
        if (rows == null) return;
        SQLiteStatement raise = db.compileStatement("UPDATE play_counts SET "
                + "plays = MAX(plays, ?), last_at = MAX(last_at, ?), heard_ms = MAX(heard_ms, ?) "
                + "WHERE track_key = ?");
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.optJSONObject(i);
                String key = o == null ? null : keys.get(o.optString("key"));
                int plays = o == null ? 0 : o.optInt("plays");
                if (key == null || plays <= 0) continue;
                raise.bindLong(1, plays);
                raise.bindLong(2, o.optLong("last_at"));
                raise.bindLong(3, o.optLong("heard_ms"));
                raise.bindString(4, key);
                if (raise.executeUpdateDelete() > 0) continue;
                ContentValues v = new ContentValues();
                v.put("track_key", key);
                v.put("plays", plays);
                v.put("last_at", o.optLong("last_at"));
                v.put("heard_ms", o.optLong("heard_ms"));
                db.insert("play_counts", null, v);
                result.plays++;
            }
        } finally {
            raise.close();
        }
    }

    private static void mergeEvents(SQLiteDatabase db, JSONArray rows, Map<String, String> keys) {
        if (rows == null) return;
        SQLiteStatement exists = db.compileStatement(
                "SELECT COUNT(*) FROM events WHERE track_key = ? AND kind = ? AND at = ?");
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.optJSONObject(i);
                String key = o == null ? null : keys.get(o.optString("key"));
                long at = o == null ? 0 : o.optLong("at");
                if (key == null || at <= 0) continue;
                exists.bindString(1, key);
                exists.bindLong(2, o.optInt("kind"));
                exists.bindLong(3, at);
                if (exists.simpleQueryForLong() > 0) continue;
                ContentValues v = new ContentValues();
                v.put("track_key", key);
                v.put("kind", o.optInt("kind"));
                v.put("score", o.optInt("score"));
                v.put("at", at);
                db.insert("events", null, v);
            }
        } finally {
            exists.close();
        }
        db.execSQL("DELETE FROM events WHERE id NOT IN "
                + "(SELECT id FROM events ORDER BY at DESC LIMIT " + EVENTS_KEEP + ")");
    }

    private static void mergeTransitions(SQLiteDatabase db, JSONArray rows,
                                         Map<String, String> keys) {
        if (rows == null) return;
        SQLiteStatement raise = db.compileStatement("UPDATE transitions SET "
                + "weight = MAX(weight, ?), at = MAX(at, ?) WHERE from_key = ? AND to_key = ?");
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.optJSONObject(i);
                if (o == null) continue;
                String from = keys.get(o.optString("from"));
                String to = keys.get(o.optString("to"));
                if (from == null || to == null) continue;
                raise.bindLong(1, o.optInt("weight"));
                raise.bindLong(2, o.optLong("at"));
                raise.bindString(3, from);
                raise.bindString(4, to);
                if (raise.executeUpdateDelete() > 0) continue;
                ContentValues v = new ContentValues();
                v.put("from_key", from);
                v.put("to_key", to);
                v.put("weight", o.optInt("weight"));
                v.put("at", o.optLong("at"));
                db.insertWithOnConflict("transitions", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            }
        } finally {
            raise.close();
        }
    }

    private static void mergePlaylists(Db store, SQLiteDatabase db, JSONArray rows,
                                       Map<String, String> keys, Result result) {
        if (rows == null) return;
        Map<String, Long> existing = new HashMap<String, Long>();
        for (Db.Playlist p : store.playlists()) existing.put(p.name, p.id);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            String name = o == null ? "" : o.optString("name").trim();
            if (name.length() == 0) continue;
            Long id = existing.get(name);
            if (id == null) {
                long now = System.currentTimeMillis();
                ContentValues v = new ContentValues();
                v.put("name", name);
                v.put("created", o.optLong("created", now));
                v.put("updated", o.optLong("updated", now));
                id = db.insert("playlists", null, v);
                if (id == -1) continue;
                existing.put(name, id);
                result.playlists++;
            }
            Set<String> have = new HashSet<String>(store.playlistKeys(id));
            JSONArray items = o.optJSONArray("keys");
            if (items == null) continue;
            int pos = nextPos(db, id);
            for (int j = 0; j < items.length(); j++) {
                String key = keys.get(items.optString(j));
                if (key == null || !have.add(key)) continue;
                ContentValues v = new ContentValues();
                v.put("playlist_id", id);
                v.put("track_key", key);
                v.put("pos", pos++);
                db.insert("playlist_items", null, v);
            }
        }
    }

    private static int nextPos(SQLiteDatabase db, long id) {
        Cursor c = db.rawQuery("SELECT IFNULL(MAX(pos), -1) + 1 FROM playlist_items "
                + "WHERE playlist_id=?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    private static List<String> strings(JSONArray rows) {
        List<String> out = new ArrayList<String>();
        if (rows == null) return out;
        for (int i = 0; i < rows.length(); i++) {
            String s = rows.optString(i).trim();
            if (s.length() > 0 && s.indexOf('\n') < 0) out.add(s);
        }
        return out;
    }
}
