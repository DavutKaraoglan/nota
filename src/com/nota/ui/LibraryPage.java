package com.nota.ui;

import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.nota.R;
import com.nota.data.MediaLibrary;
import com.nota.model.Album;
import com.nota.model.Artist;
import com.nota.model.FolderBucket;
import com.nota.model.Track;
import com.nota.player.Playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class LibraryPage extends Page implements MediaLibrary.Listener, Playback.Listener {

    private static final int S_SONGS = 0, S_ALBUMS = 1, S_ARTISTS = 2, S_FOLDERS = 3;

    public static final int SORT_TITLE = 0, SORT_ARTIST = 1, SORT_ALBUM = 2,
            SORT_ADDED = 3, SORT_DURATION = 4;

    private final TextView[] chips = new TextView[4];
    private final View[] sections = new View[4];
    private FrameLayout container;
    private int section = S_SONGS;
    private String query = "";
    private int sortMode = SORT_TITLE;

    private TrackAdapter songAdapter;
    private AlbumAdapter albumAdapter;
    private GroupAdapter artistAdapter;
    private GroupAdapter folderAdapter;

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        View root = inflater.inflate(R.layout.view_library, parent, false);
        container = (FrameLayout) root.findViewById(R.id.lib_content);

        chips[S_SONGS] = (TextView) root.findViewById(R.id.chip_songs);
        chips[S_ALBUMS] = (TextView) root.findViewById(R.id.chip_albums);
        chips[S_ARTISTS] = (TextView) root.findViewById(R.id.chip_artists);
        chips[S_FOLDERS] = (TextView) root.findViewById(R.id.chip_folders);
        chips[S_SONGS].setText(R.string.lib_songs);
        chips[S_ALBUMS].setText(R.string.lib_albums);
        chips[S_ARTISTS].setText(R.string.lib_artists);
        chips[S_FOLDERS].setText(R.string.lib_folders);
        for (int i = 0; i < chips.length; i++) {
            final int index = i;
            chips[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showSection(index);
                }
            });
        }

        MediaLibrary.get().addListener(this);
        Playback.get(host).addListener(this);
        showSection(S_SONGS);
        return root;
    }

    @Override
    public String title() {
        return host.getString(R.string.tab_library);
    }

    @Override
    public boolean hasSearch() {
        return true;
    }

    @Override
    public boolean hasOverflow() {
        return true;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public void onDestroy() {
        MediaLibrary.get().removeListener(this);
        Playback.get(host).removeListener(this);
    }

    public void refresh() {
        rebuild();
    }

    // ---- sections ----

    private void showSection(int index) {
        section = index;
        for (int i = 0; i < chips.length; i++) chips[i].setSelected(i == index);
        View v = sections[index];
        if (v == null) {
            v = buildSection(index);
            sections[index] = v;
            container.addView(v);
        }
        for (int i = 0; i < container.getChildCount(); i++) {
            View c = container.getChildAt(i);
            c.setVisibility(c == v ? View.VISIBLE : View.GONE);
        }
        rebuild();
    }

    private View buildSection(final int index) {
        View v = LayoutInflater.from(host).inflate(R.layout.view_list, container, false);
        ListView list = (ListView) v.findViewById(R.id.list);
        GridView grid = (GridView) v.findViewById(R.id.grid);

        if (index == S_ALBUMS) {
            list.setVisibility(View.GONE);
            grid.setVisibility(View.VISIBLE);
            albumAdapter = new AlbumAdapter(host, new ArrayList<Album>());
            grid.setAdapter(albumAdapter);
            grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                    host.push(TrackListPage.forAlbum(albumAdapter.getItem(pos)));
                }
            });
            return v;
        }

        if (index == S_SONGS) {
            songAdapter = new TrackAdapter(host, new ArrayList<Track>());
            list.setAdapter(songAdapter);
            list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                    Playback.get(host).play(songAdapter.items(), pos);
                }
            });
            list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                public boolean onItemLongClick(AdapterView<?> p, View view, int pos, long id) {
                    TrackMenu.show(host, view, songAdapter.getItem(pos), 0, new TrackMenu.OnChanged() {
                        public void changed() {
                            rebuild();
                        }
                    });
                    return true;
                }
            });
            return v;
        }

        final GroupAdapter adapter = new GroupAdapter(host, new ArrayList<GroupAdapter.Group>());
        if (index == S_ARTISTS) artistAdapter = adapter;
        else folderAdapter = adapter;
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                Object payload = adapter.getItem(pos).payload;
                if (payload instanceof Artist) {
                    host.push(TrackListPage.forArtist((Artist) payload));
                } else if (payload instanceof FolderBucket) {
                    host.push(TrackListPage.forFolder((FolderBucket) payload));
                }
            }
        });
        return v;
    }

    // ---- data ----

    private void rebuild() {
        View v = sections[section];
        if (v == null) return;
        MediaLibrary lib = MediaLibrary.get();

        boolean noPermission = !MainActivity.hasAudioPermission(host);
        int count;
        switch (section) {
            case S_ALBUMS: {
                List<Album> list = new ArrayList<Album>();
                for (Album a : lib.albums()) {
                    if (matches(a.title) || matches(a.artist)) list.add(a);
                }
                albumAdapter.setItems(list);
                count = list.size();
                break;
            }
            case S_ARTISTS: {
                List<GroupAdapter.Group> list = new ArrayList<GroupAdapter.Group>();
                for (Artist a : lib.artists()) {
                    if (!matches(a.name)) continue;
                    Track art = a.tracks.isEmpty() ? null : a.tracks.get(0);
                    list.add(new GroupAdapter.Group(Ui.artistOr(host, a.name),
                            Ui.songCount(host.getResources(), a.tracks.size()),
                            art, R.drawable.ic_artist, a));
                }
                artistAdapter.setItems(list);
                count = list.size();
                break;
            }
            case S_FOLDERS: {
                List<GroupAdapter.Group> list = new ArrayList<GroupAdapter.Group>();
                for (FolderBucket f : lib.folders()) {
                    if (!matches(f.name) && !matches(f.path)) continue;
                    list.add(new GroupAdapter.Group(f.name,
                            Ui.songCount(host.getResources(), f.tracks.size()),
                            null, R.drawable.ic_folder, f));
                }
                folderAdapter.setItems(list);
                count = list.size();
                break;
            }
            default: {
                List<Track> list = new ArrayList<Track>();
                for (Track t : lib.tracks()) {
                    if (matches(t.title) || matches(t.artist) || matches(t.album)) list.add(t);
                }
                sort(list, sortMode);
                songAdapter.setItems(list);
                Track cur = Playback.get(host).current();
                songAdapter.setActiveKey(cur == null ? null : cur.key());
                count = list.size();
                break;
            }
        }

        View emptyBox = v.findViewById(R.id.empty_box);
        TextView empty = (TextView) v.findViewById(R.id.empty);
        View action = v.findViewById(R.id.empty_action);
        View progress = v.findViewById(R.id.progress);

        boolean scanning = lib.isLoading();
        progress.setVisibility(scanning && count == 0 ? View.VISIBLE : View.GONE);
        emptyBox.setVisibility(count == 0 && !scanning ? View.VISIBLE : View.GONE);
        action.setVisibility(View.GONE);

        if (count == 0 && !scanning) {
            if (noPermission) {
                empty.setText(R.string.perm_body);
                android.widget.Button b = (android.widget.Button) action;
                b.setText(R.string.perm_grant);
                b.setVisibility(View.VISIBLE);
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View view) {
                        host.requestAudioPermission();
                    }
                });
            } else if (query.length() > 0) {
                empty.setText(R.string.empty_search);
            } else {
                empty.setText(section == S_ALBUMS ? R.string.empty_albums
                        : section == S_ARTISTS ? R.string.empty_artists
                        : section == S_FOLDERS ? R.string.empty_folders
                        : R.string.empty_songs);
            }
        }
    }

    private boolean matches(String value) {
        if (query.length() == 0) return true;
        return value != null && value.toLowerCase(new Locale("tr", "TR")).contains(query);
    }

    static void sort(List<Track> list, final int mode) {
        switch (mode) {
            case SORT_ARTIST:
                Collections.sort(list, new Comparator<Track>() {
                    public int compare(Track a, Track b) {
                        int c = MediaLibrary.COLLATOR.compare(s(a.artist), s(b.artist));
                        return c != 0 ? c : MediaLibrary.COLLATOR.compare(s(a.title), s(b.title));
                    }
                });
                break;
            case SORT_ALBUM:
                Collections.sort(list, new Comparator<Track>() {
                    public int compare(Track a, Track b) {
                        int c = MediaLibrary.COLLATOR.compare(s(a.album), s(b.album));
                        if (c != 0) return c;
                        if (a.trackNo != b.trackNo) return a.trackNo - b.trackNo;
                        return MediaLibrary.COLLATOR.compare(s(a.title), s(b.title));
                    }
                });
                break;
            case SORT_ADDED:
                Collections.sort(list, new Comparator<Track>() {
                    public int compare(Track a, Track b) {
                        return Long.compare(b.dateAddedSec, a.dateAddedSec);
                    }
                });
                break;
            case SORT_DURATION:
                Collections.sort(list, new Comparator<Track>() {
                    public int compare(Track a, Track b) {
                        return Long.compare(b.durationMs, a.durationMs);
                    }
                });
                break;
            default:
                MediaLibrary.sortByTitle(list);
        }
    }

    private static String s(String v) {
        return v == null ? "" : v;
    }

    // ---- callbacks ----

    @Override
    public void onSearch(String text) {
        query = text.toLowerCase(new Locale("tr", "TR")).trim();
        rebuild();
    }

    @Override
    public void onOverflow(View anchor) {
        PopupMenu menu = new PopupMenu(host, anchor);
        Menu m = menu.getMenu();
        final int SHUFFLE = 100, RESCAN = 101;
        m.add(Menu.NONE, SHUFFLE, 0, R.string.shuffle_all);
        if (section == S_SONGS) {
            m.add(Menu.NONE, SORT_TITLE, 1, R.string.sort_title);
            m.add(Menu.NONE, SORT_ARTIST, 2, R.string.sort_artist);
            m.add(Menu.NONE, SORT_ALBUM, 3, R.string.sort_album);
            m.add(Menu.NONE, SORT_ADDED, 4, R.string.sort_added);
            m.add(Menu.NONE, SORT_DURATION, 5, R.string.sort_duration);
        }
        m.add(Menu.NONE, RESCAN, 6, R.string.rescan);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                if (id == SHUFFLE) {
                    Playback.get(host).shuffleAll(MediaLibrary.get().tracks());
                } else if (id == RESCAN) {
                    MediaLibrary.get().load(host, true);
                    rebuild();
                } else {
                    sortMode = id;
                    rebuild();
                }
                return true;
            }
        });
        menu.show();
    }

    public void onLibraryChanged() {
        rebuild();
    }

    public void onTrackChanged(Track track) {
        if (songAdapter != null) {
            songAdapter.setActiveKey(track == null ? null : track.key());
        }
    }

    public void onStateChanged(boolean playing, boolean buffering) {
    }

    public void onQueueChanged() {
    }
}
