package com.nota.ui;

import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.nota.R;
import com.nota.data.Db;
import com.nota.data.MediaLibrary;
import com.nota.model.Track;
import com.nota.player.Playback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything played, newest first. A tab rather than a row inside the playlists screen: reaching
 * for the song from an hour ago is the commonest thing asked of this app, and it was two taps
 * and a list of names away. The playlists themselves are one tap deeper, in the overflow.
 */
public class HistoryPage extends Page implements Playback.Listener, MediaLibrary.Listener {

    private static final int MAX = 200;

    private TrackAdapter adapter;
    private ListView list;
    private View emptyBox;
    private boolean stale;

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        View root = inflater.inflate(R.layout.view_list, parent, false);
        list = (ListView) root.findViewById(R.id.list);
        emptyBox = root.findViewById(R.id.empty_box);
        ((TextView) root.findViewById(R.id.empty)).setText(R.string.empty_history);
        root.findViewById(R.id.empty_action).setVisibility(View.GONE);

        adapter = new TrackAdapter(host, new ArrayList<Track>());
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                Playback.get(host).playSingle(adapter.getItem(pos));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View view, int pos, long id) {
                TrackMenu.show(host, view, adapter.getItem(pos), 0, new TrackMenu.OnChanged() {
                    public void changed() {
                        reload();
                    }
                });
                return true;
            }
        });

        Playback.get(host).addListener(this);
        MediaLibrary.get().addListener(this);
        reload();
        return root;
    }

    @Override
    public String title() {
        return host.getString(R.string.tab_history);
    }

    @Override
    public boolean hasOverflow() {
        return true;
    }

    @Override
    public void onShow() {
        if (stale) reload();
    }

    @Override
    public void onOnline(boolean online) {
        reloadWhenSeen();
    }

    @Override
    public void onDestroy() {
        Playback.get(host).removeListener(this);
        MediaLibrary.get().removeListener(this);
    }

    private void reloadWhenSeen() {
        if (!visible()) {
            stale = true;
            return;
        }
        reload();
    }

    private void reload() {
        if (adapter == null) return;
        stale = false;
        Map<String, Long> played = Db.get(host).recentPlays(MAX);
        List<Track> items = MediaLibrary.resolve(host,
                new ArrayList<String>(played.keySet()));
        adapter.setItems(items);
        adapter.setHeadings(days(items, played));
        Track cur = Playback.get(host).current();
        adapter.setActiveKey(cur == null ? null : cur.key());
        emptyBox.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** The day a song was last played, said only once per day it opens. */
    private List<String> days(List<Track> items, Map<String, Long> played) {
        List<String> out = new ArrayList<String>();
        String last = null;
        for (Track t : items) {
            Long at = played.get(t.key());
            String day = at == null ? null : Ui.day(host, at);
            out.add(day != null && !day.equals(last) ? day : null);
            if (day != null) last = day;
        }
        return out;
    }

    @Override
    public void onOverflow(View anchor) {
        PopupMenu menu = new PopupMenu(host, anchor);
        final int PLAYLISTS = 1, MOST = 2, CLEAR = 3;
        menu.getMenu().add(Menu.NONE, PLAYLISTS, 0, R.string.tab_playlists);
        menu.getMenu().add(Menu.NONE, MOST, 1, R.string.most_played);
        menu.getMenu().add(Menu.NONE, CLEAR, 2, R.string.clear_history);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case PLAYLISTS:
                        host.push(new PlaylistsPage());
                        return true;
                    case MOST:
                        host.push(TrackListPage.mostPlayed(
                                host.getString(R.string.most_played)));
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

    public void onTrackChanged(Track track) {
        if (adapter == null) return;
        adapter.setActiveKey(track == null ? null : track.key());
        // A song starting is exactly what puts a new name at the top of this list.
        reloadWhenSeen();
    }

    public void onStateChanged(boolean playing, boolean buffering) {
    }

    public void onQueueChanged() {
    }

    public void onLibraryChanged() {
        reloadWhenSeen();
    }
}
