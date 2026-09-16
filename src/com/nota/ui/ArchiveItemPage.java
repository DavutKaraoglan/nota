package com.nota.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;

import com.nota.R;
import com.nota.data.ArchiveApi;
import com.nota.model.Track;
import com.nota.player.Playback;

import java.util.List;

/** The file list of one Internet Archive item, already resolved by {@link DiscoverPage}. */
public class ArchiveItemPage extends Page implements Playback.Listener {

    private final ArchiveApi.Item item;
    private final List<Track> tracks;
    private TrackAdapter adapter;

    public ArchiveItemPage(ArchiveApi.Item item, List<Track> tracks) {
        this.item = item;
        this.tracks = tracks;
    }

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        View root = inflater.inflate(R.layout.view_list, parent, false);
        ListView list = (ListView) root.findViewById(R.id.list);

        adapter = new TrackAdapter(host, tracks);
        adapter.setNumbered(true);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                Playback.get(host).play(tracks, pos);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View view, int pos, long id) {
                TrackMenu.show(host, view, tracks.get(pos), 0, null);
                return true;
            }
        });

        Playback.get(host).addListener(this);
        return root;
    }

    @Override
    public String title() {
        return item.title;
    }

    @Override
    public boolean showsBack() {
        return true;
    }

    @Override
    public void onShow() {
        Track cur = Playback.get(host).current();
        if (adapter != null) adapter.setActiveKey(cur == null ? null : cur.key());
    }

    @Override
    public void onDestroy() {
        Playback.get(host).removeListener(this);
    }

    public void onTrackChanged(Track track) {
        if (adapter != null) adapter.setActiveKey(track == null ? null : track.key());
    }

    public void onStateChanged(boolean playing, boolean buffering) {
    }

    public void onQueueChanged() {
    }
}
