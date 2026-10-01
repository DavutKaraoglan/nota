package com.nota.data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;

import com.nota.model.Album;
import com.nota.model.Artist;
import com.nota.model.FolderBucket;
import com.nota.model.Track;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory index of the device's audio files, built from MediaStore. */
public class MediaLibrary {

    public interface Listener {
        void onLibraryChanged();
    }

    private static MediaLibrary instance;

    public static synchronized MediaLibrary get() {
        if (instance == null) instance = new MediaLibrary();
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    private List<Track> tracks = new ArrayList<Track>();
    private List<Album> albums = new ArrayList<Album>();
    private List<Artist> artists = new ArrayList<Artist>();
    private List<FolderBucket> folders = new ArrayList<FolderBucket>();
    private Map<String, Track> byKey = new LinkedHashMap<String, Track>();

    private volatile boolean loading;
    private volatile boolean loaded;

    /** Turkish-aware ordering so "Çilek" lands after "Cem", not after "Zil". */
    public static final Collator COLLATOR;

    static {
        COLLATOR = Collator.getInstance(new Locale("tr", "TR"));
        COLLATOR.setStrength(Collator.SECONDARY);
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean isLoading() {
        return loading;
    }

    public List<Track> tracks() {
        return tracks;
    }

    public List<Album> albums() {
        return albums;
    }

    public List<Artist> artists() {
        return artists;
    }

    public List<FolderBucket> folders() {
        return folders;
    }

    public Track byKey(String key) {
        return byKey.get(key);
    }

    public Album album(long id) {
        for (Album a : albums) if (a.id == id) return a;
        return null;
    }

    public Artist artist(long id) {
        for (Artist a : artists) if (a.id == id) return a;
        return null;
    }

    public FolderBucket folder(String path) {
        for (FolderBucket f : folders) if (f.path.equals(path)) return f;
        return null;
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void load(Context context, boolean force) {
        if (loading || (loaded && !force)) return;
        loading = true;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                try {
                    scan(app);
                } finally {
                    loading = false;
                    loaded = true;
                    main.post(new Runnable() {
                        public void run() {
                            for (Listener l : listeners) l.onLibraryChanged();
                        }
                    });
                }
            }
        }, "nota-scan").start();
    }

    private static final String[] COLUMNS = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
    };

    private void scan(Context context) {
        List<Track> found = new ArrayList<Track>();
        ContentResolver cr = context.getContentResolver();
        Uri uri = Build.VERSION.SDK_INT >= 29
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        Cursor c = null;
        try {
            c = cr.query(uri, COLUMNS, MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null);
            if (c != null) {
                int iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                int iArtistId = c.getColumnIndex(MediaStore.Audio.Media.ARTIST_ID);
                int iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int iData = c.getColumnIndex(MediaStore.Audio.Media.DATA);
                int iTrack = c.getColumnIndex(MediaStore.Audio.Media.TRACK);
                int iYear = c.getColumnIndex(MediaStore.Audio.Media.YEAR);
                int iSize = c.getColumnIndex(MediaStore.Audio.Media.SIZE);
                int iAdded = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED);

                while (c.moveToNext()) {
                    Track t = new Track();
                    t.type = Track.TYPE_LOCAL;
                    t.id = c.getLong(iId);
                    t.title = notEmpty(c.getString(iTitle), "?");
                    t.artist = c.getString(iArtist);
                    t.album = c.getString(iAlbum);
                    t.albumId = c.getLong(iAlbumId);
                    t.artistId = iArtistId >= 0 ? c.getLong(iArtistId) : 0;
                    t.durationMs = c.getLong(iDur);
                    t.data = iData >= 0 ? c.getString(iData) : null;
                    t.trackNo = iTrack >= 0 ? c.getInt(iTrack) : 0;
                    t.year = iYear >= 0 ? c.getInt(iYear) : 0;
                    t.sizeBytes = iSize >= 0 ? c.getLong(iSize) : 0;
                    t.dateAddedSec = iAdded >= 0 ? c.getLong(iAdded) : 0;

                    if (isUnknown(t.artist)) t.artist = null;
                    if (isUnknown(t.album)) t.album = null;

                    // MediaStore reports disc-prefixed numbers as 1xx / 2xx.
                    if (t.trackNo > 1000) t.trackNo = t.trackNo % 1000;

                    if (t.data != null) {
                        int slash = t.data.lastIndexOf('/');
                        t.folder = slash > 0 ? t.data.substring(0, slash) : "/";
                    }
                    found.add(t);
                }
            }
        } catch (Exception ignored) {
            // A denied permission or a vendor MediaStore quirk leaves an empty library
            // rather than crashing the app.
        } finally {
            if (c != null) c.close();
        }

        sortByTitle(found);
        publish(found);
    }

    private void publish(List<Track> found) {
        Map<Long, Album> albumMap = new LinkedHashMap<Long, Album>();
        Map<Long, Artist> artistMap = new LinkedHashMap<Long, Artist>();
        Map<String, FolderBucket> folderMap = new LinkedHashMap<String, FolderBucket>();
        Map<String, Track> keyMap = new LinkedHashMap<String, Track>();

        for (Track t : found) {
            keyMap.put(t.key(), t);

            Album al = albumMap.get(t.albumId);
            if (al == null) {
                al = new Album();
                al.id = t.albumId;
                al.title = t.album;
                al.artist = t.artist;
                al.year = t.year;
                albumMap.put(al.id, al);
            }
            if (al.artist == null) al.artist = t.artist;
            if (al.year == 0) al.year = t.year;
            al.tracks.add(t);

            long artistKey = t.artistId != 0 ? t.artistId
                    : (t.artist == null ? 0 : t.artist.toLowerCase(Locale.ROOT).hashCode());
            Artist ar = artistMap.get(artistKey);
            if (ar == null) {
                ar = new Artist();
                ar.id = artistKey;
                ar.name = t.artist;
                artistMap.put(artistKey, ar);
            }
            ar.tracks.add(t);
            ar.albums.put(al.id, al);

            if (t.folder != null) {
                FolderBucket fb = folderMap.get(t.folder);
                if (fb == null) {
                    fb = new FolderBucket();
                    fb.path = t.folder;
                    int slash = t.folder.lastIndexOf('/');
                    fb.name = slash >= 0 && slash < t.folder.length() - 1
                            ? t.folder.substring(slash + 1) : t.folder;
                    folderMap.put(fb.path, fb);
                }
                fb.tracks.add(t);
            }
        }

        List<Album> albumList = new ArrayList<Album>(albumMap.values());
        for (Album a : albumList) {
            Collections.sort(a.tracks, new Comparator<Track>() {
                public int compare(Track x, Track y) {
                    if (x.trackNo != y.trackNo && x.trackNo > 0 && y.trackNo > 0) {
                        return x.trackNo - y.trackNo;
                    }
                    return COLLATOR.compare(nn(x.title), nn(y.title));
                }
            });
        }
        Collections.sort(albumList, new Comparator<Album>() {
            public int compare(Album x, Album y) {
                return COLLATOR.compare(nn(x.title), nn(y.title));
            }
        });

        List<Artist> artistList = new ArrayList<Artist>(artistMap.values());
        Collections.sort(artistList, new Comparator<Artist>() {
            public int compare(Artist x, Artist y) {
                return COLLATOR.compare(nn(x.name), nn(y.name));
            }
        });

        List<FolderBucket> folderList = new ArrayList<FolderBucket>(folderMap.values());
        Collections.sort(folderList, new Comparator<FolderBucket>() {
            public int compare(FolderBucket x, FolderBucket y) {
                return COLLATOR.compare(nn(x.name), nn(y.name));
            }
        });

        this.tracks = found;
        this.albums = albumList;
        this.artists = artistList;
        this.folders = folderList;
        this.byKey = keyMap;
    }

    /**
     * Whether a song can be opened as things stand. Offline that means the phone already holds
     * it: a download, or a stream whose bytes are still in the cache.
     */
    public static boolean playable(Context context, Track t, boolean online) {
        if (online || !t.isRemote()) return true;
        String id = YtApi.videoId(t);
        return id != null && StreamProxy.cached(context, id);
    }

    /** The same list without the songs a tap could not open. */
    public static List<Track> playableOnly(Context context, List<Track> tracks) {
        boolean online = Connectivity.isOnline(context);
        if (online) return tracks;
        List<Track> out = new ArrayList<Track>();
        for (Track t : tracks) {
            if (playable(context, t, false)) out.add(t);
        }
        return out;
    }

    /**
     * Turns stored track keys back into tracks, dropping entries that no longer exist — and,
     * offline, the ones that exist only on the other side of the connection.
     */
    public static List<Track> resolve(Context context, List<String> keys) {
        List<Track> out = new ArrayList<Track>();
        MediaLibrary lib = get();
        boolean online = Connectivity.isOnline(context);
        Set<String> onlineIds = new HashSet<String>();
        for (String key : keys) {
            if (key.startsWith("o:")) onlineIds.add(key.substring(2));
        }
        Map<String, Track> remote = onlineIds.isEmpty()
                ? Collections.<String, Track>emptyMap()
                : Db.get(context).online(onlineIds);
        for (String key : keys) {
            Track t = key.startsWith("o:") ? remote.get(key.substring(2)) : lib.byKey(key);
            if (t != null && playable(context, t, online)) out.add(t);
        }
        return out;
    }

    public static void sortByTitle(List<Track> list) {
        Collections.sort(list, new Comparator<Track>() {
            public int compare(Track x, Track y) {
                return COLLATOR.compare(nn(x.title), nn(y.title));
            }
        });
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    private static String notEmpty(String s, String fallback) {
        return TextUtils.isEmpty(s) ? fallback : s;
    }

    private static boolean isUnknown(String s) {
        return TextUtils.isEmpty(s) || "<unknown>".equals(s);
    }
}
