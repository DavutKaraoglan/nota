package com.nota.ui;

import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.nota.R;
import com.nota.data.ArtLoader;
import com.nota.data.Db;
import com.nota.data.MediaLibrary;
import com.nota.data.YtApi;
import com.nota.model.Album;
import com.nota.model.Artist;
import com.nota.model.FolderBucket;
import com.nota.model.Track;
import com.nota.player.Playback;

import java.util.ArrayList;
import java.util.List;

/** One collection of tracks: an album, an artist, a folder, a playlist or a smart list. */
public class TrackListPage extends Page implements Playback.Listener, MediaLibrary.Listener {

    private static final int KIND_ALBUM = 0, KIND_ARTIST = 1, KIND_FOLDER = 2,
            KIND_PLAYLIST = 3, KIND_FAVORITES = 4, KIND_RECENT = 5, KIND_MOST = 6,
            KIND_MIX = 7;

    private int kind;
    private String heading = "";
    private long playlistId;
    private Album album;
    private Artist artist;
    private FolderBucket folder;

    /** The song a mix is built around, and what came back for it. Null means not asked yet. */
    private Track seed;
    private List<Track> mix;
    private boolean asking;
    private boolean gone;

    private TrackAdapter adapter;
    private ListView list;
    private View header;
    private View emptyBox;
    private TextView emptyText;

    public static TrackListPage forAlbum(Album a) {
        TrackListPage p = new TrackListPage();
        p.kind = KIND_ALBUM;
        p.album = a;
        p.heading = a.title;
        return p;
    }

    public static TrackListPage forArtist(Artist a) {
        TrackListPage p = new TrackListPage();
        p.kind = KIND_ARTIST;
        p.artist = a;
        p.heading = a.name;
        return p;
    }

    public static TrackListPage forFolder(FolderBucket f) {
        TrackListPage p = new TrackListPage();
        p.kind = KIND_FOLDER;
        p.folder = f;
        p.heading = f.name;
        return p;
    }

    public static TrackListPage forPlaylist(long id, String name) {
        TrackListPage p = new TrackListPage();
        p.kind = KIND_PLAYLIST;
        p.playlistId = id;
        p.heading = name;
        return p;
    }

    /** A compilation: the radio around one song, kept for as long as the screen is open. */
    public static TrackListPage forMix(Track seed, String name) {
        TrackListPage p = new TrackListPage();
        p.kind = KIND_MIX;
        p.seed = seed;
        p.heading = name;
        return p;
    }

    public static TrackListPage smart(int which, String name) {
        TrackListPage p = new TrackListPage();
        p.kind = which;
        p.heading = name;
        return p;
    }

    public static TrackListPage favorites(String name) {
        return smart(KIND_FAVORITES, name);
    }

    public static TrackListPage recent(String name) {
        return smart(KIND_RECENT, name);
    }

    public static TrackListPage mostPlayed(String name) {
        return smart(KIND_MOST, name);
    }

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        View root = inflater.inflate(R.layout.view_list, parent, false);
        list = (ListView) root.findViewById(R.id.list);
        emptyBox = root.findViewById(R.id.empty_box);
        emptyText = (TextView) root.findViewById(R.id.empty);
        root.findViewById(R.id.empty_action).setVisibility(View.GONE);
        header = inflater.inflate(R.layout.header_detail, list, false);
        list.addHeaderView(header, null, false);

        adapter = new TrackAdapter(host, new ArrayList<Track>());
        adapter.setNumbered(kind == KIND_ALBUM);
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                int index = pos - list.getHeaderViewsCount();
                if (index >= 0) Playback.get(host).play(adapter.items(), index);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View view, int pos, long id) {
                int index = pos - list.getHeaderViewsCount();
                if (index < 0) return false;
                TrackMenu.show(host, view, adapter.getItem(index), playlistId,
                        new TrackMenu.OnChanged() {
                            public void changed() {
                                reload();
                            }
                        });
                return true;
            }
        });

        header.findViewById(R.id.btn_play).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!adapter.items().isEmpty()) Playback.get(host).play(adapter.items(), 0);
            }
        });
        header.findViewById(R.id.btn_shuffle).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Playback.get(host).shuffleAll(adapter.items());
            }
        });

        Playback.get(host).addListener(this);
        MediaLibrary.get().addListener(this);
        reload();
        return root;
    }

    @Override
    public String title() {
        return heading == null ? host.getString(R.string.app_name) : heading;
    }

    @Override
    public boolean showsBack() {
        return true;
    }

    @Override
    public boolean hasOverflow() {
        return kind == KIND_PLAYLIST || kind == KIND_RECENT || kind == KIND_MOST;
    }

    @Override
    public void onShow() {
        reload();
    }

    @Override
    public void onDestroy() {
        Playback.get(host).removeListener(this);
        MediaLibrary.get().removeListener(this);
        // A radio can still be in flight; this says the screen it was for is gone.
        gone = true;
    }

    private void reload() {
        List<Track> items;
        switch (kind) {
            case KIND_ALBUM:
                items = new ArrayList<Track>(album.tracks);
                break;
            case KIND_ARTIST:
                items = new ArrayList<Track>(artist.tracks);
                LibraryPage.sort(items, LibraryPage.SORT_ALBUM);
                break;
            case KIND_FOLDER:
                items = new ArrayList<Track>(folder.tracks);
                break;
            case KIND_PLAYLIST:
                items = MediaLibrary.resolve(host, Db.get(host).playlistKeys(playlistId));
                break;
            case KIND_FAVORITES:
                items = MediaLibrary.resolve(host, Db.get(host).favoriteKeys());
                break;
            case KIND_RECENT:
                items = MediaLibrary.resolve(host, Db.get(host).recentKeys(100));
                break;
            case KIND_MIX:
                // The song it grew from opens the list, so the screen can be played from the
                // moment it appears rather than after the radio has been asked.
                if (mix == null) askForMix();
                items = new ArrayList<Track>();
                items.add(seed);
                if (mix != null) items.addAll(mix);
                break;
            default:
                items = MediaLibrary.resolve(host, Db.get(host).mostPlayedKeys(100));
                break;
        }
        adapter.setItems(items);
        Track cur = Playback.get(host).current();
        adapter.setActiveKey(cur == null ? null : cur.key());
        bindHeader(items);
        showEmpty(items.isEmpty());
    }

    private void askForMix() {
        if (asking) return;
        asking = true;
        YtApi.mix(host, YtApi.videoId(seed), new YtApi.TrackCallback() {
            public void onTracks(List<Track> tracks) {
                deliverMix(tracks);
            }

            public void onError() {
                // An empty answer rather than none: the screen says the radio had nothing
                // instead of sitting on a wait that will not end.
                deliverMix(new ArrayList<Track>());
            }
        });
    }

    private void deliverMix(List<Track> tracks) {
        asking = false;
        if (gone) return;
        mix = tracks;
        reload();
    }

    private void bindHeader(List<Track> items) {
        TextView title = (TextView) header.findViewById(R.id.title);
        TextView subtitle = (TextView) header.findViewById(R.id.subtitle);
        ImageView art = (ImageView) header.findViewById(R.id.art);

        title.setText(heading == null || heading.length() == 0
                ? host.getString(R.string.unknown_album) : heading);

        long total = 0;
        for (Track t : items) total += t.durationMs;
        String count = Ui.songCount(host.getResources(), items.size());
        String sub = kind == KIND_ALBUM || kind == KIND_ARTIST
                ? Ui.artistOr(host, kind == KIND_ALBUM ? album.artist : artist.name)
                + " · " + count
                : count;
        if (total > 0) sub = sub + " · " + Ui.duration(total);
        subtitle.setText(sub);

        int icon = kind == KIND_ARTIST ? R.drawable.ic_artist
                : kind == KIND_FOLDER ? R.drawable.ic_folder
                : kind == KIND_PLAYLIST || kind == KIND_MIX ? R.drawable.ic_playlist
                : kind == KIND_FAVORITES ? R.drawable.ic_favorite
                : R.drawable.ic_album;
        // A mix wears the cover of the song it grew from, which is there before the radio answers.
        Track face = !items.isEmpty() ? items.get(0) : kind == KIND_MIX ? seed : null;
        if (face != null) {
            ArtLoader.get(host).bind(art, face, Ui.dp(host, 112), icon, Ui.dp(host, 34));
        } else {
            art.setTag(null);
            int pad = Ui.dp(host, 34);
            art.setPadding(pad, pad, pad, pad);
            art.setImageResource(icon);
        }
        Ui.round(art, host.getResources().getDimension(R.dimen.art_radius));
    }

    private void showEmpty(boolean empty) {
        emptyBox.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            emptyText.setText(kind == KIND_FAVORITES ? R.string.empty_favorites
                    : kind == KIND_RECENT || kind == KIND_MOST ? R.string.empty_history
                    : R.string.empty_songs);
        }
    }

    @Override
    public void onOverflow(View anchor) {
        PopupMenu menu = new PopupMenu(host, anchor);
        final int RENAME = 1, DELETE = 2, CLEAR = 3;
        if (kind == KIND_PLAYLIST) {
            menu.getMenu().add(Menu.NONE, RENAME, 0, R.string.rename);
            menu.getMenu().add(Menu.NONE, DELETE, 1, R.string.delete);
        } else {
            menu.getMenu().add(Menu.NONE, CLEAR, 0, R.string.clear_history);
        }
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case RENAME:
                        promptRename();
                        return true;
                    case DELETE:
                        confirmDelete();
                        return true;
                    case CLEAR:
                        Db.get(host).clearHistory();
                        reload();
                        return true;
                }
                return false;
            }
        });
        menu.show();
    }

    private void promptRename() {
        final android.widget.EditText input = new android.widget.EditText(host);
        input.setSingleLine(true);
        input.setText(heading);
        int pad = Ui.dp(host, 20);
        input.setPadding(pad, pad, pad, pad);
        new android.app.AlertDialog.Builder(host, R.style.NotaTheme_Dialog)
                .setTitle(R.string.rename)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        String name = input.getText().toString().trim();
                        if (name.length() == 0) return;
                        Db.get(host).renamePlaylist(playlistId, name);
                        heading = name;
                        host.refreshTopBar();
                        host.refreshPlaylists();
                        bindHeader(adapter.items());
                    }
                })
                .show();
    }

    private void confirmDelete() {
        new android.app.AlertDialog.Builder(host, R.style.NotaTheme_Dialog)
                .setMessage(host.getString(R.string.delete_playlist_q, heading))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        Db.get(host).deletePlaylist(playlistId);
                        host.refreshPlaylists();
                        host.pop();
                    }
                })
                .show();
    }

    public void onTrackChanged(Track track) {
        if (adapter != null) adapter.setActiveKey(track == null ? null : track.key());
    }

    public void onStateChanged(boolean playing, boolean buffering) {
    }

    public void onQueueChanged() {
    }

    public void onLibraryChanged() {
        reload();
    }
}
