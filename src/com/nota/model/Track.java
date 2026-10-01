package com.nota.model;

/** A playable item: a local MediaStore file or an on-demand remote track. */
public class Track {
    public static final int TYPE_LOCAL = 0;
    /** On-demand track from an open catalogue: remote, but seekable and finite. */
    public static final int TYPE_ONLINE = 2;

    public int type = TYPE_LOCAL;
    public long id;
    public String title;
    public String artist;
    public String album;
    public long albumId;
    public long artistId;
    public long durationMs;
    /** Absolute file path for local tracks, stream URL for remote ones. */
    public String data;
    /** Remote artwork URL; local tracks resolve art from albumId instead. */
    public String artUrl;
    public String folder;
    public int trackNo;
    public int year;
    public long sizeBytes;
    public long dateAddedSec;
    /** Catalogue identifier for online tracks, e.g. {@code yt:<videoId>}. */
    public String sourceId;

    /** Played over the network: artwork comes from a URL and there is no local .lrc file. */
    public boolean isRemote() {
        return type != TYPE_LOCAL;
    }

    /** Stable key used by playlists, favorites and history. */
    public String key() {
        switch (type) {
            case TYPE_ONLINE:
                return "o:" + sourceId;
            default:
                return "l:" + id;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Track && key().equals(((Track) o).key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }
}
