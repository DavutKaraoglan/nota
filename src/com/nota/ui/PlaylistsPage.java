package com.nota.ui;

import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.PopupMenu;

import com.nota.R;
import com.nota.data.Db;
import com.nota.model.Track;

import java.util.ArrayList;
import java.util.List;

/** Smart lists (favorites, history) followed by the user's own playlists. */
public class PlaylistsPage extends Page {

    private GroupAdapter adapter;

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        View root = inflater.inflate(R.layout.view_list, parent, false);
        ListView list = (ListView) root.findViewById(R.id.list);
        adapter = new GroupAdapter(host, new ArrayList<GroupAdapter.Group>());
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                open(adapter.getItem(pos));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View view, int pos, long id) {
                Object payload = adapter.getItem(pos).payload;
                if (!(payload instanceof Db.Playlist)) return false;
                showPlaylistMenu(view, (Db.Playlist) payload);
                return true;
            }
        });

        root.findViewById(R.id.empty_action).setVisibility(View.GONE);
        refresh();
        return root;
    }

    @Override
    public String title() {
        return host.getString(R.string.tab_playlists);
    }

    @Override
    public boolean showsBack() {
        return true;
    }

    @Override
    public boolean hasOverflow() {
        return true;
    }

    @Override
    public void onShow() {
        refresh();
    }

    public void refresh() {
        if (adapter == null) return;
        Db db = Db.get(host);
        List<GroupAdapter.Group> groups = new ArrayList<GroupAdapter.Group>();

        groups.add(smart(R.string.favorites, db.favoriteKeys().size(),
                R.drawable.ic_favorite, "fav"));
        groups.add(smart(R.string.recently_played, db.recentKeys(100).size(),
                R.drawable.ic_timer, "recent"));
        groups.add(smart(R.string.most_played, db.mostPlayedKeys(100).size(),
                R.drawable.ic_note, "most"));

        for (Db.Playlist p : db.playlists()) {
            groups.add(new GroupAdapter.Group(p.name,
                    Ui.songCount(host.getResources(), p.count),
                    firstTrack(db.playlistKeys(p.id)), R.drawable.ic_playlist, p));
        }
        adapter.setItems(groups);
    }

    private GroupAdapter.Group smart(int labelRes, int count, int icon, String tag) {
        return new GroupAdapter.Group(host.getString(labelRes),
                Ui.songCount(host.getResources(), count), null, icon, tag);
    }

    private Track firstTrack(List<String> keys) {
        List<Track> tracks = com.nota.data.MediaLibrary.resolve(host, keys);
        return tracks.isEmpty() ? null : tracks.get(0);
    }

    private void open(GroupAdapter.Group g) {
        if (g.payload instanceof Db.Playlist) {
            Db.Playlist p = (Db.Playlist) g.payload;
            host.push(TrackListPage.forPlaylist(p.id, p.name));
        } else if ("fav".equals(g.payload)) {
            host.push(TrackListPage.favorites(host.getString(R.string.favorites)));
        } else if ("recent".equals(g.payload)) {
            host.push(TrackListPage.recent(host.getString(R.string.recently_played)));
        } else {
            host.push(TrackListPage.mostPlayed(host.getString(R.string.most_played)));
        }
    }

    private void showPlaylistMenu(View anchor, final Db.Playlist playlist) {
        PopupMenu menu = new PopupMenu(host, anchor);
        final int RENAME = 1, DELETE = 2;
        menu.getMenu().add(Menu.NONE, RENAME, 0, R.string.rename);
        menu.getMenu().add(Menu.NONE, DELETE, 1, R.string.delete);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == RENAME) {
                    promptRename(playlist);
                } else {
                    confirmDelete(playlist);
                }
                return true;
            }
        });
        menu.show();
    }

    private void promptRename(final Db.Playlist playlist) {
        final android.widget.EditText input = new android.widget.EditText(host);
        input.setSingleLine(true);
        input.setText(playlist.name);
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
                        Db.get(host).renamePlaylist(playlist.id, name);
                        refresh();
                    }
                })
                .show();
    }

    private void confirmDelete(final Db.Playlist playlist) {
        new android.app.AlertDialog.Builder(host, R.style.NotaTheme_Dialog)
                .setMessage(host.getString(R.string.delete_playlist_q, playlist.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        Db.get(host).deletePlaylist(playlist.id);
                        refresh();
                    }
                })
                .show();
    }

    @Override
    public void onOverflow(View anchor) {
        PopupMenu menu = new PopupMenu(host, anchor);
        menu.getMenu().add(Menu.NONE, 1, 0, R.string.new_playlist);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                TrackMenu.promptNewPlaylist(host, null);
                return true;
            }
        });
        menu.show();
    }
}
