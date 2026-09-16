package com.nota.ui;

import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nota.R;
import com.nota.data.ArtLoader;
import com.nota.data.Db;
import com.nota.data.Follows;
import com.nota.data.MediaLibrary;
import com.nota.data.Recommender;
import com.nota.model.Track;
import com.nota.player.Playback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The opening screen: a few short shelves that lead straight into playback. A shelf is a spread
 * of the listening rather than a running order, so tapping one song queues only that song and
 * leaves the rest of the queue to follow from it.
 */
public class HomePage extends Page implements MediaLibrary.Listener, Playback.Listener {

    private static final int SHELF = 5;
    private static final int SLIDER = 12;
    /** How many favourites have to change before the suggestions are worth asking for again. */
    private static final int TASTE = 5;

    private LinearLayout sections;
    private View empty;

    /** What the recommender last answered, kept so returning to this screen costs nothing. */
    private List<Track> forYou = new ArrayList<Track>();
    private String askedFor;
    private boolean asking;

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        View root = inflater.inflate(R.layout.view_home, parent, false);
        sections = (LinearLayout) root.findViewById(R.id.sections);
        empty = root.findViewById(R.id.empty);
        root.findViewById(R.id.btn_search).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                host.openDiscoverSearch();
            }
        });
        MediaLibrary.get().addListener(this);
        Playback.get(host).addListener(this);
        refresh();
        loadTint();
        return root;
    }

    @Override
    public String title() {
        return host.getString(R.string.tab_home);
    }

    @Override
    public boolean hidesTopBar() {
        return true;
    }

    @Override
    public void onShow() {
        refresh();
        loadTint();
    }

    @Override
    public void onHide() {
        host.setTint(null);
    }

    public void onLibraryChanged() {
        refresh();
    }

    public void onTrackChanged(Track track) {
        loadTint();
    }

    public void onStateChanged(boolean playing, boolean buffering) {
    }

    public void onQueueChanged() {
    }

    /**
     * The song being played colours the top of the screen, the way the player screen does. The
     * wash belongs to the window, not this view, so it runs behind the top bar as one gradient.
     */
    private void loadTint() {
        final Track t = Playback.get(host).current();
        if (t == null) {
            host.setTint(null);
            return;
        }
        Ui.coverColor(host, t, new Ui.ColorCallback() {
            public void onColor(int colour) {
                if (sections == null || Playback.get(host).current() != t) return;
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

    private void refresh() {
        if (sections == null) return;
        sections.removeAllViews();
        Db db = Db.get(host);

        askForYou(db);

        addSlider(R.string.on_repeat, MediaLibrary.resolve(host, db.mostPlayedKeys(SLIDER)),
                TrackListPage.mostPlayed(host.getString(R.string.on_repeat)));
        addShelf(R.string.for_you, forYou, null);
        addArtists(artistFaces(db));

        addShelf(R.string.recently_played, MediaLibrary.resolve(host, db.recentKeys(SHELF)),
                TrackListPage.recent(host.getString(R.string.recently_played)));
        addShelf(R.string.favorites, MediaLibrary.resolve(host, db.favoriteKeys()),
                TrackListPage.favorites(host.getString(R.string.favorites)));

        empty.setVisibility(sections.getChildCount() == 0 ? View.VISIBLE : View.GONE);
    }

    /**
     * The suggestions answer the listening as a whole, so they are asked for again only once the
     * favourites themselves have shifted — not every time another song starts.
     */
    private void askForYou(Db db) {
        String taste = TextUtils.join(",", db.mostPlayedKeys(TASTE));
        if (asking || taste.length() == 0 || taste.equals(askedFor)) return;
        asking = true;
        askedFor = taste;
        Recommender.forTaste(host, SLIDER, new Recommender.Callback() {
            public void onTracks(List<Track> tracks) {
                asking = false;
                if (tracks.isEmpty() || sections == null) return;
                forYou = tracks;
                refresh();
            }
        });
    }

    /** Covers big enough to be looked at, which is the one thing the list rows cannot offer. */
    private void addSlider(int titleRes, List<Track> tracks, final Page seeAll) {
        if (tracks.isEmpty()) return;
        final List<Track> shown = new ArrayList<Track>(
                tracks.subList(0, Math.min(SLIDER, tracks.size())));
        View slider = LayoutInflater.from(host).inflate(
                R.layout.item_home_slider, sections, false);
        TextView label = (TextView) slider.findViewById(R.id.section_title);
        label.setText(titleRes);
        if (seeAll == null) {
            label.setBackground(null);
        } else {
            label.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    host.push(seeAll);
                }
            });
        }

        LinearLayout row = (LinearLayout) slider.findViewById(R.id.slider_row);
        ArtLoader loader = ArtLoader.get(host);
        int artPx = host.getResources().getDimensionPixelSize(R.dimen.card);
        float radius = host.getResources().getDimension(R.dimen.art_radius);
        for (int i = 0; i < shown.size(); i++) {
            final int index = i;
            Track t = shown.get(i);
            View card = LayoutInflater.from(host).inflate(R.layout.item_cover_card, row, false);
            ImageView art = (ImageView) card.findViewById(R.id.art);
            Ui.round(art, radius);
            loader.bind(art, t, artPx, R.drawable.ic_note, Ui.dp(host, 34));
            ((TextView) card.findViewById(R.id.title)).setText(t.title);
            ((TextView) card.findViewById(R.id.subtitle)).setText(Ui.artistOr(host, t.artist));
            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Playback.get(host).playSingle(shown.get(index));
                }
            });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    TrackMenu.show(host, v, shown.get(index), 0, null);
                    return true;
                }
            });
            row.addView(card);
        }
        sections.addView(slider);
    }

    /** Subscriptions lead the row; behind them come the artists with the most listening. */
    private List<Track> artistFaces(Db db) {
        List<Track> faces = new ArrayList<Track>();
        Set<String> seen = new HashSet<String>();
        for (String name : Follows.all(host)) {
            List<Track> known = db.onlineByArtist(name, 1);
            if (!known.isEmpty() && seen.add(name.toLowerCase(Locale.ROOT))) {
                faces.add(known.get(0));
            }
        }
        for (Track t : db.topArtists(SLIDER)) {
            if (t.artist != null && seen.add(t.artist.toLowerCase(Locale.ROOT))) faces.add(t);
        }
        return faces;
    }

    /** Round covers, because a circle reads as a person where a square reads as a record. */
    private void addArtists(List<Track> faces) {
        if (faces.isEmpty()) return;
        View slider = LayoutInflater.from(host).inflate(
                R.layout.item_home_slider, sections, false);
        TextView label = (TextView) slider.findViewById(R.id.section_title);
        label.setText(R.string.loved_artists);
        label.setBackground(null);

        LinearLayout row = (LinearLayout) slider.findViewById(R.id.slider_row);
        ArtLoader loader = ArtLoader.get(host);
        int artPx = host.getResources().getDimensionPixelSize(R.dimen.card);
        for (final Track face : faces) {
            View card = LayoutInflater.from(host).inflate(R.layout.item_artist_card, row, false);
            ImageView art = (ImageView) card.findViewById(R.id.art);
            Ui.round(art, artPx / 2f);
            loader.bind(art, face, artPx, R.drawable.ic_artist, Ui.dp(host, 34));
            ((TextView) card.findViewById(R.id.title)).setText(face.artist);
            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    host.push(new ArtistPage(face.artist, face));
                }
            });
            row.addView(card);
        }
        sections.addView(slider);
    }

    private void addShelf(int titleRes, final List<Track> tracks, final Page seeAll) {
        if (tracks.isEmpty()) return;
        final List<Track> shown = tracks.subList(0, Math.min(SHELF, tracks.size()));
        View shelf = LayoutInflater.from(host).inflate(
                R.layout.item_home_section, sections, false);
        TextView label = (TextView) shelf.findViewById(R.id.section_title);
        label.setText(titleRes);
        if (seeAll == null) {
            label.setBackground(null);
        } else {
            label.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    host.push(seeAll);
                }
            });
        }

        LinearLayout rows = (LinearLayout) shelf.findViewById(R.id.section_rows);
        TrackAdapter adapter = new TrackAdapter(host, shown);
        for (int i = 0; i < shown.size(); i++) {
            final int index = i;
            View row = adapter.getView(i, null, rows);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Playback.get(host).playSingle(shown.get(index));
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    TrackMenu.show(host, v, shown.get(index), 0, null);
                    return true;
                }
            });
            rows.addView(row);
        }
        sections.addView(shelf);
    }

    @Override
    public void onDestroy() {
        MediaLibrary.get().removeListener(this);
        Playback.get(host).removeListener(this);
        // A recommendation or a colour can still be in flight; this says the screen is gone.
        sections = null;
    }
}
