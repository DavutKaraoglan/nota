package com.nota.ui;

import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.nota.R;
import com.nota.data.ArtLoader;
import com.nota.data.Db;
import com.nota.data.Downloads;
import com.nota.data.MediaLibrary;
import com.nota.data.Signals;
import com.nota.data.YtApi;
import com.nota.model.Album;
import com.nota.model.Artist;
import com.nota.model.Track;
import com.nota.player.Playback;

import java.util.Collections;
import java.util.List;

/** Long-press actions for a track. */
public class TrackMenu {

    public interface OnChanged {
        void changed();
    }

    private static final int PLAY_NEXT = 1, QUEUE = 2, PLAYLIST = 3, FAV = 4, ALBUM = 5,
            ARTIST = 6, REMOVE = 7, SHARE = 8, DOWNLOAD = 9, UNDOWNLOAD = 10;

    public static void show(final MainActivity host, View anchor, final Track track,
                            final long playlistId, final OnChanged onChanged) {
        final Playback playback = Playback.get(host);
        final Db db = Db.get(host);
        final boolean favorite = db.isFavorite(track.key());

        final String videoId = YtApi.isYouTube(track) ? YtApi.videoId(track) : null;
        final Downloads downloads = Downloads.get(host);
        final boolean kept = videoId != null && downloads.has(videoId);

        Sheet sheet = new Sheet(host).header(trackHeader(host, track));
        sheet.add(PLAY_NEXT, R.drawable.ic_next, R.string.play_next);
        sheet.add(QUEUE, R.drawable.ic_queue, R.string.add_to_queue);
        if (!track.isRemote()) sheet.add(PLAYLIST, R.drawable.ic_playlist, R.string.add_to_playlist);
        sheet.add(FAV, favorite ? R.drawable.ic_favorite : R.drawable.ic_favorite_border,
                favorite ? R.string.favorite_remove : R.string.favorite_add);
        if (videoId != null) {
            if (kept) {
                sheet.add(UNDOWNLOAD, R.drawable.ic_downloaded, R.string.download_remove);
            } else if (downloads.running(videoId)) {
                sheet.add(DOWNLOAD, R.drawable.ic_download, R.string.downloading);
            } else {
                sheet.add(DOWNLOAD, R.drawable.ic_download, R.string.download);
            }
        }
        if (!track.isRemote() && track.album != null) {
            sheet.add(ALBUM, R.drawable.ic_album, R.string.go_to_album);
        }
        if (track.artist != null) sheet.add(ARTIST, R.drawable.ic_artist, R.string.go_to_artist);
        if (!track.isRemote()) sheet.add(SHARE, R.drawable.ic_share, R.string.share);
        if (playlistId != 0) sheet.add(REMOVE, R.drawable.ic_delete, R.string.remove);

        sheet.show(new Sheet.OnPick() {
            public void picked(int id) {
                switch (id) {
                    case PLAY_NEXT:
                        playback.playNext(Collections.singletonList(track));
                        toast(host, R.string.queued_next);
                        break;
                    case QUEUE:
                        playback.addToQueue(Collections.singletonList(track));
                        toast(host, R.string.queued);
                        break;
                    case PLAYLIST:
                        pickPlaylist(host, Collections.singletonList(track));
                        break;
                    case FAV:
                        if (db.toggleFavorite(track)) Signals.get(host).liked(track);
                        if (onChanged != null) onChanged.changed();
                        break;
                    case DOWNLOAD:
                        // The track has to survive the download, or a song kept from a search
                        // would have nothing to show once the search is gone.
                        db.saveOnline(track);
                        downloads.start(videoId);
                        break;
                    case UNDOWNLOAD:
                        downloads.remove(videoId);
                        break;
                    case ALBUM: {
                        Album a = MediaLibrary.get().album(track.albumId);
                        if (a != null) host.push(TrackListPage.forAlbum(a));
                        break;
                    }
                    case ARTIST: {
                        if (track.isRemote()) {
                            host.push(new ArtistPage(track.artist, track));
                            break;
                        }
                        Artist a = MediaLibrary.get().artist(track.artistId);
                        if (a != null) host.push(TrackListPage.forArtist(a));
                        break;
                    }
                    case SHARE:
                        share(host, track);
                        break;
                    case REMOVE:
                        db.removeFromPlaylist(playlistId, track.key());
                        if (onChanged != null) onChanged.changed();
                        break;
                }
            }
        });
    }

    /** Names the song the actions belong to, the way the row that was pressed did. */
    private static View trackHeader(MainActivity host, Track track) {
        View v = LayoutInflater.from(host).inflate(R.layout.sheet_track_header, null, false);
        ImageView art = (ImageView) v.findViewById(R.id.art);
        Ui.round(art, host.getResources().getDimension(R.dimen.art_radius));
        ArtLoader.get(host).bind(art, track,
                host.getResources().getDimensionPixelSize(R.dimen.row_art),
                R.drawable.ic_note, Ui.dp(host, 14));
        ((TextView) v.findViewById(R.id.title)).setText(track.title);
        ((TextView) v.findViewById(R.id.subtitle)).setText(Ui.artistOr(host, track.artist));
        return v;
    }

    /** Hands the file to another app as a MediaStore item, which needs no FileProvider. */
    private static void share(MainActivity host, Track track) {
        Uri uri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.id);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("audio/*");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, track.title);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        host.startActivity(Intent.createChooser(send, host.getString(R.string.share)));
    }

    /** Lets the user drop tracks into an existing playlist or make a new one. */
    public static void pickPlaylist(final MainActivity host, final List<Track> tracks) {
        final Db db = Db.get(host);
        final List<Db.Playlist> lists = db.playlists();
        final String[] labels = new String[lists.size() + 1];
        labels[0] = host.getString(R.string.new_playlist);
        for (int i = 0; i < lists.size(); i++) labels[i + 1] = lists.get(i).name;

        new AlertDialog.Builder(host, R.style.NotaTheme_Dialog)
                .setTitle(R.string.add_to_playlist)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            promptNewPlaylist(host, tracks);
                        } else {
                            Db.Playlist p = lists.get(which - 1);
                            db.addToPlaylist(p.id, tracks);
                            Toast.makeText(host,
                                    host.getString(R.string.added_to_playlist, p.name),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    public static void promptNewPlaylist(final MainActivity host, final List<Track> tracks) {
        final EditText input = new EditText(host);
        input.setHint(R.string.playlist_name);
        input.setSingleLine(true);
        int pad = Ui.dp(host, 20);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(host, R.style.NotaTheme_Dialog)
                .setTitle(R.string.new_playlist)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.create, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText().toString().trim();
                        if (name.length() == 0) return;
                        long id = Db.get(host).createPlaylist(name);
                        if (tracks != null && !tracks.isEmpty()) {
                            Db.get(host).addToPlaylist(id, tracks);
                            Toast.makeText(host,
                                    host.getString(R.string.added_to_playlist, name),
                                    Toast.LENGTH_SHORT).show();
                        }
                        host.refreshPlaylists();
                    }
                })
                .show();
    }

    private static void toast(MainActivity host, int res) {
        Toast.makeText(host, res, Toast.LENGTH_SHORT).show();
    }
}
