package com.nota.ui;

import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.Toast;

import com.nota.R;
import com.nota.data.Db;
import com.nota.data.MediaLibrary;
import com.nota.data.Signals;
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

    public static void show(final MainActivity host, View anchor, final Track track,
                            final long playlistId, final OnChanged onChanged) {
        final Playback playback = Playback.get(host);
        final Db db = Db.get(host);
        final boolean favorite = db.isFavorite(track.key());

        PopupMenu menu = new PopupMenu(host, anchor);
        Menu m = menu.getMenu();
        final int PLAY_NEXT = 1, QUEUE = 2, PLAYLIST = 3, FAV = 4, ALBUM = 5, ARTIST = 6,
                REMOVE = 7, SHARE = 8;
        m.add(Menu.NONE, PLAY_NEXT, 0, R.string.play_next);
        m.add(Menu.NONE, QUEUE, 1, R.string.add_to_queue);
        if (!track.isRemote()) {
            m.add(Menu.NONE, PLAYLIST, 2, R.string.add_to_playlist);
        }
        m.add(Menu.NONE, FAV, 3, favorite ? R.string.favorite_remove : R.string.favorite_add);
        if (!track.isRemote() && track.album != null) {
            m.add(Menu.NONE, ALBUM, 4, R.string.go_to_album);
        }
        if (track.artist != null) m.add(Menu.NONE, ARTIST, 5, R.string.go_to_artist);
        if (!track.isRemote()) m.add(Menu.NONE, SHARE, 6, R.string.share);
        if (playlistId != 0) m.add(Menu.NONE, REMOVE, 7, R.string.remove);

        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case PLAY_NEXT:
                        playback.playNext(Collections.singletonList(track));
                        toast(host, R.string.queued_next);
                        return true;
                    case QUEUE:
                        playback.addToQueue(Collections.singletonList(track));
                        toast(host, R.string.queued);
                        return true;
                    case PLAYLIST:
                        pickPlaylist(host, Collections.singletonList(track));
                        return true;
                    case FAV:
                        if (db.toggleFavorite(track)) Signals.get(host).liked(track);
                        if (onChanged != null) onChanged.changed();
                        return true;
                    case ALBUM: {
                        Album a = MediaLibrary.get().album(track.albumId);
                        if (a != null) host.push(TrackListPage.forAlbum(a));
                        return true;
                    }
                    case ARTIST: {
                        if (track.isRemote()) {
                            host.push(new ArtistPage(track.artist, track));
                            return true;
                        }
                        Artist a = MediaLibrary.get().artist(track.artistId);
                        if (a != null) host.push(TrackListPage.forArtist(a));
                        return true;
                    }
                    case SHARE:
                        share(host, track);
                        return true;
                    case REMOVE:
                        db.removeFromPlaylist(playlistId, track.key());
                        if (onChanged != null) onChanged.changed();
                        return true;
                }
                return false;
            }
        });
        menu.show();
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
