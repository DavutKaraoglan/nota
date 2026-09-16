package com.nota.ui;

import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.nota.R;
import com.nota.data.ArtLoader;
import com.nota.data.ArtistInfo;
import com.nota.data.Db;
import com.nota.data.Follows;
import com.nota.data.YtApi;
import com.nota.model.Track;
import com.nota.player.Playback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Everything one artist has to offer: what has been heard already, then what the catalogue holds. */
public class ArtistPage extends Page implements Playback.Listener {

    private static final int LIMIT = 50;

    private final String name;
    /** A track of theirs that has already been played, so the header has a cover for free. */
    private final Track face;

    private TrackAdapter adapter;
    private ListView list;
    private View header;
    private Button follow;
    private ProgressBar progress;
    private boolean searched;

    public ArtistPage(String name, Track face) {
        this.name = name;
        this.face = face;
    }

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        View root = inflater.inflate(R.layout.view_list, parent, false);
        list = (ListView) root.findViewById(R.id.list);
        progress = (ProgressBar) root.findViewById(R.id.progress);
        root.findViewById(R.id.empty_box).setVisibility(View.GONE);
        header = inflater.inflate(R.layout.header_detail, list, false);
        list.addHeaderView(header, null, false);

        adapter = new TrackAdapter(host, new ArrayList<Track>());
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
                TrackMenu.show(host, view, adapter.getItem(index), 0, null);
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
        follow = (Button) header.findViewById(R.id.btn_follow);
        follow.setVisibility(View.VISIBLE);
        bindFollow(Follows.has(host, name));
        follow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                bindFollow(Follows.toggle(host, name));
            }
        });

        Playback.get(host).addListener(this);
        show(Db.get(host).onlineByArtist(name, LIMIT));
        searchCatalogue();
        loadInfo();
        return root;
    }

    private void bindFollow(boolean following) {
        follow.setText(following ? R.string.following : R.string.follow);
    }

    /** The reading of a non-latin name, the name behind the alias, whichever exists. */
    private void loadInfo() {
        final TextView note = (TextView) header.findViewById(R.id.note);
        ArtistInfo.lookup(host, name, new ArtistInfo.Callback() {
            public void onInfo(String line) {
                if (adapter == null || line.length() == 0) return;
                note.setText(line);
                note.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public String title() {
        return name;
    }

    @Override
    public boolean showsBack() {
        return true;
    }

    /** The catalogue is asked once per visit; coming back to the page must not cost data again. */
    private void searchCatalogue() {
        if (searched) return;
        searched = true;
        progress.setVisibility(View.VISIBLE);
        YtApi.search(host, name, new YtApi.TrackCallback() {
            public void onTracks(List<Track> found) {
                if (adapter == null) return;
                progress.setVisibility(View.GONE);
                List<Track> merged = new ArrayList<Track>(adapter.items());
                Set<String> seen = new HashSet<String>();
                for (Track t : merged) seen.add(t.key());
                for (Track t : found) if (seen.add(t.key())) merged.add(t);
                show(merged);
            }

            public void onError() {
                if (adapter == null) return;
                progress.setVisibility(View.GONE);
            }
        });
    }

    private void show(List<Track> items) {
        adapter.setItems(items);
        Track cur = Playback.get(host).current();
        adapter.setActiveKey(cur == null ? null : cur.key());
        bindHeader(items);
    }

    private void bindHeader(List<Track> items) {
        ((TextView) header.findViewById(R.id.title)).setText(name);
        ((TextView) header.findViewById(R.id.subtitle))
                .setText(Ui.songCount(host.getResources(), items.size()));
        ImageView art = (ImageView) header.findViewById(R.id.art);
        final Track cover = face != null ? face : items.isEmpty() ? null : items.get(0);
        if (cover != null) {
            ArtLoader.get(host).bind(art, cover, Ui.dp(host, 112),
                    R.drawable.ic_artist, Ui.dp(host, 34));
            art.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showCover(cover);
                }
            });
        }
        Ui.round(art, Ui.dp(host, 56));
        loadTint(cover);
    }

    /** The thumbnail is the only picture there is, so tapping it gives it the whole screen. */
    private void showCover(Track cover) {
        View view = LayoutInflater.from(host).inflate(R.layout.dialog_cover, null);
        ImageView big = (ImageView) view.findViewById(R.id.art);
        int size = host.getResources().getDisplayMetrics().widthPixels - Ui.dp(host, 56);
        Ui.round(big, host.getResources().getDimension(R.dimen.art_radius) * 2);
        ArtLoader.get(host).bind(big, cover, size, R.drawable.ic_artist, size / 3);

        final Dialog dialog = new Dialog(host);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        view.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    /** Same wash as the home screen, so walking into an artist keeps the colour of the music. */
    private void loadTint(final Track cover) {
        if (cover == null) {
            host.setTint(null);
            return;
        }
        Ui.coverColor(host, cover, new Ui.ColorCallback() {
            public void onColor(int colour) {
                if (adapter == null) return;
                if (colour == 0) {
                    host.setTint(null);
                    return;
                }
                int rgb = colour & 0xFFFFFF;
                host.setTint(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x73000000 | rgb, 0x2E000000 | rgb, rgb & 0xFFFFFF}));
            }
        });
    }

    @Override
    public void onHide() {
        host.setTint(null);
    }

    public void onTrackChanged(Track track) {
        if (adapter != null) adapter.setActiveKey(track == null ? null : track.key());
    }

    public void onStateChanged(boolean playing, boolean buffering) {
    }

    public void onQueueChanged() {
    }

    @Override
    public void onDestroy() {
        Playback.get(host).removeListener(this);
        // A catalogue answer can still be in flight; this says the screen is gone.
        adapter = null;
    }
}
