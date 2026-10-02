package com.nota.player;

import android.content.Context;

import com.nota.data.MediaLibrary;
import com.nota.data.Prefs;
import com.nota.model.Track;

import java.util.ArrayList;
import java.util.List;

class Session {

    static class Snapshot {
        List<Track> tracks;
        int index;
        long positionMs;
        boolean shuffle;
        int repeat;
    }

    private static final String KEY_QUEUE = "session_queue";
    private static final String KEY_INDEX = "session_index";
    private static final String KEY_POSITION = "session_position";
    private static final String KEY_SHUFFLE = "session_shuffle";
    private static final String KEY_REPEAT = "session_repeat";
    private static final int KEEP_BEHIND = 25;
    private static final int KEEP_TOTAL = 500;

    static void save(Context c, List<Track> playOrder, int index, long positionMs,
                     boolean shuffle, int repeat) {
        if (playOrder.isEmpty() || index < 0 || index >= playOrder.size()) {
            clear(c);
            return;
        }
        int start = Math.max(0, index - KEEP_BEHIND);
        int end = Math.min(playOrder.size(), start + KEEP_TOTAL);
        StringBuilder keys = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (keys.length() > 0) keys.append('\n');
            keys.append(playOrder.get(i).key());
        }
        Prefs.get(c).edit()
                .putString(KEY_QUEUE, keys.toString())
                .putInt(KEY_INDEX, index - start)
                .putLong(KEY_POSITION, Math.max(0, positionMs))
                .putBoolean(KEY_SHUFFLE, shuffle)
                .putInt(KEY_REPEAT, repeat)
                .apply();
    }

    static void savePosition(Context c, long positionMs) {
        if (Prefs.getString(c, KEY_QUEUE, "").length() == 0) return;
        Prefs.setLong(c, KEY_POSITION, Math.max(0, positionMs));
    }

    static void clear(Context c) {
        Prefs.get(c).edit()
                .remove(KEY_QUEUE)
                .remove(KEY_INDEX)
                .remove(KEY_POSITION)
                .remove(KEY_SHUFFLE)
                .remove(KEY_REPEAT)
                .apply();
    }

    static Snapshot load(Context c) {
        String raw = Prefs.getString(c, KEY_QUEUE, "");
        if (raw.length() == 0) return null;
        List<String> keys = new ArrayList<String>();
        for (String key : raw.split("\n")) {
            if (key.length() > 0) keys.add(key);
        }
        int stored = Prefs.getInt(c, KEY_INDEX, 0);
        String wanted = stored >= 0 && stored < keys.size() ? keys.get(stored) : null;

        List<Track> tracks = MediaLibrary.resolve(c, keys);
        if (tracks.isEmpty()) return null;

        Snapshot s = new Snapshot();
        s.tracks = tracks;
        s.index = 0;
        boolean found = false;
        for (int i = 0; i < tracks.size() && wanted != null; i++) {
            if (wanted.equals(tracks.get(i).key())) {
                s.index = i;
                found = true;
                break;
            }
        }
        s.positionMs = found ? Prefs.getLong(c, KEY_POSITION, 0) : 0;
        s.shuffle = Prefs.getBool(c, KEY_SHUFFLE, false);
        s.repeat = Prefs.getInt(c, KEY_REPEAT, Playback.REPEAT_OFF);
        return s;
    }
}
