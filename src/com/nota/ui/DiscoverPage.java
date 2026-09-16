package com.nota.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.nota.R;
import com.nota.data.ArtLoader;
import com.nota.data.Prefs;
import com.nota.data.Signals;
import com.nota.data.YtApi;
import com.nota.model.Track;
import com.nota.player.Playback;

import java.util.ArrayList;
import java.util.List;

/** On-demand music search over YouTube. */
public class DiscoverPage extends Page implements Playback.Listener {

    /** Past searches, newest first, each as "term\tcover url" on its own line. */
    private static final String KEY_RECENT = "discover_recent";
    private static final int RECENT_MAX = 12;

    private TrackAdapter adapter;
    private ProgressBar progress;
    private View emptyBox;
    private TextView emptyText;
    private Button emptyAction;
    private View recentBox;
    private LinearLayout recentRow;

    private String query = "";
    /** Incremented on every request so a late response from an older query is ignored. */
    private int requestId;

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        View root = inflater.inflate(R.layout.view_discover, parent, false);
        ListView list = (ListView) root.findViewById(R.id.list);
        progress = (ProgressBar) root.findViewById(R.id.progress);
        emptyBox = root.findViewById(R.id.empty_box);
        emptyText = (TextView) root.findViewById(R.id.empty);
        emptyAction = (Button) root.findViewById(R.id.empty_action);
        recentBox = root.findViewById(R.id.recent_box);
        recentRow = (LinearLayout) root.findViewById(R.id.recent_row);

        adapter = new TrackAdapter(host, new ArrayList<Track>());
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                // Only the chosen song is queued: the rest of a search is everything the listener
                // did not pick, so the recommender continues from here instead.
                Track picked = adapter.items().get(pos);
                Signals.get(host).searched(picked);
                Playback.get(host).playSingle(picked);
            }
        });

        Playback.get(host).addListener(this);
        buildRecent();
        load();
        return root;
    }

    @Override
    public String title() {
        return host.getString(R.string.tab_discover);
    }

    @Override
    public boolean hasSearch() {
        return true;
    }

    @Override
    public void onDestroy() {
        Playback.get(host).removeListener(this);
    }

    @Override
    public void onSearch(String q) {
        query = q == null ? "" : q.trim();
        load();
    }

    /** An empty query has no useful answer here, so it stays a prompt instead of a request. */
    private void load() {
        final int id = ++requestId;
        if (query.length() == 0) {
            deliver(new ArrayList<Track>(), R.string.discover_search_hint, false);
            return;
        }
        setLoading(true);
        YtApi.search(host, query, new YtApi.TrackCallback() {
            public void onTracks(List<Track> found) {
                if (id != requestId) return;
                deliver(found, R.string.discover_empty, false);
            }

            public void onError() {
                if (id != requestId) return;
                deliver(new ArrayList<Track>(), R.string.discover_error, true);
            }
        });
    }

    private void deliver(List<Track> found, int emptyRes, boolean retry) {
        setLoading(false);
        // A search worth keeping is one that answered; the first hit lends the card its cover.
        if (query.length() > 0 && !found.isEmpty()) {
            remember(query, found.get(0).artUrl);
            buildRecent();
        }
        recentBox.setVisibility(query.length() == 0 && recentRow.getChildCount() > 0
                ? View.VISIBLE : View.GONE);
        adapter.setItems(found);
        Track cur = Playback.get(host).current();
        adapter.setActiveKey(cur == null ? null : cur.key());

        boolean empty = found.isEmpty();
        emptyBox.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            emptyText.setText(emptyRes);
            emptyAction.setVisibility(retry ? View.VISIBLE : View.GONE);
            emptyAction.setText(R.string.retry);
            emptyAction.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    load();
                }
            });
        }
    }

    /** Same term twice is one entry, moved back to the front. */
    private void remember(String term, String artUrl) {
        List<String[]> kept = new ArrayList<String[]>();
        kept.add(new String[]{term, artUrl == null ? "" : artUrl});
        for (String[] old : recent()) {
            if (old[0].equalsIgnoreCase(term) || kept.size() >= RECENT_MAX) continue;
            kept.add(old);
        }
        StringBuilder sb = new StringBuilder();
        for (String[] e : kept) sb.append(e[0]).append('\t').append(e[1]).append('\n');
        Prefs.setString(host, KEY_RECENT, sb.toString());
    }

    private List<String[]> recent() {
        List<String[]> out = new ArrayList<String[]>();
        String raw = Prefs.getString(host, KEY_RECENT, "");
        for (String line : raw.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) out.add(new String[]{line.substring(0, tab), line.substring(tab + 1)});
        }
        return out;
    }

    private void buildRecent() {
        recentRow.removeAllViews();
        ArtLoader loader = ArtLoader.get(host);
        int artPx = host.getResources().getDimensionPixelSize(R.dimen.row_art);
        float radius = host.getResources().getDimension(R.dimen.art_radius);
        for (String[] entry : recent()) {
            final String term = entry[0];
            View row = LayoutInflater.from(host).inflate(
                    R.layout.row_track, recentRow, false);
            ImageView art = (ImageView) row.findViewById(R.id.art);
            Ui.round(art, radius);
            // The cover belongs to the search, not to a track anyone can play from here.
            Track stand = new Track();
            stand.type = Track.TYPE_ONLINE;
            stand.artUrl = entry[1].length() == 0 ? null : entry[1];
            loader.bind(art, stand, artPx, R.drawable.ic_search, Ui.dp(host, 14));
            ((TextView) row.findViewById(R.id.title)).setText(term);
            row.findViewById(R.id.subtitle).setVisibility(View.GONE);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    host.searchFor(term);
                }
            });
            recentRow.addView(row);
        }
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) emptyBox.setVisibility(View.GONE);
    }

    public void onTrackChanged(Track track) {
        if (adapter != null) adapter.setActiveKey(track == null ? null : track.key());
    }

    public void onStateChanged(boolean playing, boolean buffering) {
    }

    public void onQueueChanged() {
    }
}
