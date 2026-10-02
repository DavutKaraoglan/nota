package com.nota.ui;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.nota.R;
import com.nota.data.ArtLoader;
import com.nota.data.Connectivity;
import com.nota.data.Db;
import com.nota.data.Follows;
import com.nota.data.MediaLibrary;
import com.nota.data.Muted;
import com.nota.data.Recommender;
import com.nota.data.YtApi;
import com.nota.model.Track;
import com.nota.player.Playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * The opening screen: a few short shelves that lead straight into playback. A shelf is a spread
 * of the listening rather than a running order, so tapping one song queues only that song and
 * leaves the rest of the queue to follow from it.
 */
public class HomePage extends Page implements MediaLibrary.Listener, Playback.Listener {

    private static final int SHELF = 5;
    /** How many shelves deep the suggestions go, since they are turned rather than scrolled. */
    private static final int FOR_YOU_PAGES = 3;
    private static final int SLIDER = 12;
    /** A mood is the only thing on the screen when it is picked, so it is worth more rows. */
    private static final int MOOD = 20;
    private static final int MOOD_ARTISTS = 3;
    private static final int MIXES = 8;
    /** How many loved artists the new-releases row asks after. */
    private static final int FRESH_ARTISTS = 3;
    /** How many favourites have to change before the suggestions are worth asking for again. */
    private static final int TASTE = 5;

    private LinearLayout sections;
    private ScrollView scroll;
    private View empty;

    private final long mixDraw = new Random().nextLong();

    /** What the recommender last answered, kept so returning to this screen costs nothing. */
    private List<Track> forYou = new ArrayList<Track>();
    private String askedFor;
    private boolean asking;

    /** What the loved artists have just put out, and which names that answer was for. */
    private List<Track> fresh = new ArrayList<Track>();
    private String freshFor;
    private String askingFresh;

    /** The mood on the chips, or null while home is about the listener's own playing. */
    private String mood;
    /** The mood {@link #moodTracks} answers, which is how a stale answer is told from a fresh one. */
    private String moodShown;
    private String asked;
    private List<Track> moodTracks = new ArrayList<Track>();
    /** The Db revision the shelves on screen were built from. -1 until they exist at all. */
    private int builtAt = -1;
    private String playedFrom;


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
        scroll = (ScrollView) root.findViewById(R.id.home_scroll);
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            public void onScrollChange(View v, int x, int y, int oldX, int oldY) {
                host.setTintScroll(y);
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
        // Rebuilding the shelves means a round of queries and a fresh set of views; coming back
        // from another tab without having played or favourited anything earns none of that.
        Db db = Db.get(host);
        if (db.revision() != builtAt || !listenedTo(db).equals(playedFrom)) refresh();
        loadTint();
    }

    @Override
    public void onHide() {
        host.setTint(null);
    }

    @Override
    public void onOnline(boolean online) {
        // Only what the outage left empty is forgotten: a shelf that was answered is still the
        // answer, and asking for it again would cost the same request twice.
        if (online) {
            if (moodTracks.isEmpty()) moodShown = null;
            if (fresh.isEmpty()) freshFor = null;
            if (forYou.isEmpty()) askedFor = null;
        }
        refresh();
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
     * wash belongs to the window, not this view, so it runs behind the top bar as one band —
     * and this page drags it along as it scrolls, so the colour stays the header's own.
     */
    private void loadTint() {
        if (!visible()) return;
        final Track t = Playback.get(host).current();
        if (t == null) {
            paint(0);
            return;
        }
        Ui.coverColor(host, t, new Ui.ColorCallback() {
            public void onColor(int colour) {
                if (sections == null || !visible() || Playback.get(host).current() != t) return;
                paint(colour);
            }
        });
    }

    private void paint(int colour) {
        host.setHeaderTint(colour);
        // Painting starts the wash back at the top of the window, where the header is only if
        // the page has not been scrolled.
        if (scroll != null) host.setTintScroll(scroll.getScrollY());
    }

    private String listenedTo(Db db) {
        return db.mostPlayedKeys(SLIDER) + "|" + db.recentKeys(SHELF);
    }

    private void refresh() {
        if (sections == null) return;
        sections.removeAllViews();
        Db db = Db.get(host);
        builtAt = db.revision();
        playedFrom = listenedTo(db);

        // Offline the catalogue half of this screen is a row of things a tap cannot open, so
        // the page falls back to what the phone is already holding.
        boolean online = Connectivity.isOnline(host);
        if (online) addMoods();
        // The mood row stands whether or not anything has been listened to, so the "nothing
        // here yet" message counts the shelves that came after it rather than the whole column.
        int beforeShelves = sections.getChildCount();

        if (online && mood != null) {
            // A picked mood is the subject of the page, and the listener's own shelves would
            // only bury it. The row above is still there to put them back.
            addMood();
        } else {
            askForYou(db);
            List<Track> faces = online ? artistFaces(db) : new ArrayList<Track>();
            if (online) {
                askForFresh(faces, db);
                addShelf(host.getString(R.string.new_releases), fresh, 1, null);
            }

            List<Track> played = MediaLibrary.resolve(host, db.mostPlayedKeys(SLIDER));
            addSlider(R.string.on_repeat, played,
                    TrackListPage.mostPlayed(host.getString(R.string.on_repeat)));

            addPagedShelf(host.getString(R.string.for_you),
                    MediaLibrary.playableOnly(host, forYou), SHELF, FOR_YOU_PAGES);

            // Built from what has actually been played first, and from the suggestions only where
            // the listening runs out: a compilation is worth more when its starting point is one
            // of theirs.
            // A mix is a card that stands for a search made when it is opened, so it has nothing
            // to give offline however local its starting point is.
            if (online) {
                List<Track> seedPool = new ArrayList<Track>(played);
                seedPool.addAll(forYou);
                addMixes(host.getString(R.string.mixes_for_you), seedPool);
            }

            addArtists(faces);

            addShelf(host.getString(R.string.recently_played),
                    MediaLibrary.resolve(host, db.recentKeys(SHELF)), SHELF,
                    TrackListPage.recent(host.getString(R.string.recently_played)));
            addShelf(host.getString(R.string.favorites),
                    MediaLibrary.resolve(host, db.favoriteKeys()), SHELF,
                    TrackListPage.favorites(host.getString(R.string.favorites)));
        }

        empty.setVisibility(sections.getChildCount() == beforeShelves ? View.VISIBLE : View.GONE);
    }

    /**
     * A way into the catalogue that does not need a name typed first. Each chip answers here
     * rather than handing the screen over: picking one is a change of subject, not a change of
     * place, and the same tap takes it back. The row works on a phone with no library and no
     * listening behind it.
     */
    private void addMoods() {
        View box = LayoutInflater.from(host).inflate(
                R.layout.item_home_moods, sections, false);
        LinearLayout row = (LinearLayout) box.findViewById(R.id.mood_row);
        for (String name : host.getResources().getStringArray(R.array.mood_names)) {
            final String picked = name;
            TextView chip = (TextView) LayoutInflater.from(host).inflate(
                    R.layout.item_mood_chip, row, false);
            chip.setText(picked);
            chip.setSelected(picked.equals(mood));
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    mood = picked.equals(mood) ? null : picked;
                    refresh();
                }
            });
            row.addView(chip);
        }
        sections.addView(box);
    }

    /**
     * What the chosen mood put on the screen. The search is asked for once per mood and the
     * answer is drawn on the round that follows, so the wait shows rather than blanks the page.
     */
    private void addMood() {
        if (!mood.equals(moodShown)) {
            askForMood();
            sections.addView(LayoutInflater.from(host).inflate(
                    R.layout.item_home_skeleton, sections, false));
            return;
        }
        if (moodTracks.isEmpty()) {
            addNote(R.string.empty_search);
            return;
        }
        addMixes(host.getString(R.string.mood_playlists), moodTracks);
        addShelf(mood, moodTracks, MOOD, null);
    }

    private void askForMood() {
        final String want = mood;
        if (want.equals(asked)) return;
        asked = want;
        YtApi.mood(host, want, want + " " + host.getString(R.string.mood_query), moodArtists(),
                new YtApi.TrackCallback() {
                    public void onTracks(List<Track> tracks) {
                        deliverMood(want, tracks);
                    }

                    public void onError() {
                        // Remembered as an empty answer rather than as nothing: a mood that
                        // cannot be answered should say so once, not ask again every round.
                        deliverMood(want, new ArrayList<Track>());
                    }
                });
    }

    /**
     * The names the mood is asked about as well as the mood itself. Who has actually been played
     * comes first — a mood is meant to sound like their own music in that mood — and a followed
     * name only fills in behind. Few of them: each one is a search of its own, and past a handful
     * the shelf stops being about the mood at all.
     */
    private List<String> moodArtists() {
        Db db = Db.get(host);
        List<Track> sources = new ArrayList<Track>(db.topArtists(MOOD_ARTISTS));
        sources.addAll(artistFaces(db));
        List<String> names = new ArrayList<String>();
        Set<String> seen = new HashSet<String>(Muted.keys(host));
        for (Track t : sources) {
            if (TextUtils.isEmpty(t.artist)) continue;
            if (!seen.add(t.artist.toLowerCase(Locale.ROOT))) continue;
            names.add(t.artist);
            if (names.size() == MOOD_ARTISTS) break;
        }
        return names;
    }

    private void deliverMood(String want, List<Track> tracks) {
        if (sections == null) return;
        // Only ours to clear: a late answer to an earlier mood must not report the search that
        // replaced it as finished, or the next round asks for that one all over again.
        if (want.equals(asked)) asked = null;
        // A chip tapped while this was in flight has already moved the screen on.
        if (!want.equals(mood)) return;
        moodTracks = tracks;
        moodShown = want;
        refresh();
    }

    private void addNote(int textRes) {
        TextView note = (TextView) LayoutInflater.from(host).inflate(
                R.layout.item_home_note, sections, false);
        note.setText(textRes);
        sections.addView(note);
    }

    /**
     * What the loved artists have just put out, one song each. A handful of artists rather than
     * all of them: a shelf of everything new by everyone would be a second discover page, and
     * this is meant to be the line that says a favourite has released something since the last
     * look. Artists with nothing recent drop out, so the row is sometimes short and sometimes
     * not there at all — which is the point of it.
     */
    private void askForFresh(List<Track> faces, Db db) {
        final List<String> who = freshArtists(faces, db);
        if (who.isEmpty()) return;
        final String asking = TextUtils.join("\n", who);
        if (asking.equals(freshFor) || asking.equals(askingFresh)) return;
        askingFresh = asking;
        YtApi.latestReleases(host, who, new YtApi.TrackCallback() {
            public void onTracks(List<Track> tracks) {
                deliverFresh(asking, tracks);
            }

            public void onError() {
                // Kept as an empty answer: names the catalogue cannot place should cost one
                // question a session, not one per round.
                deliverFresh(asking, new ArrayList<Track>());
            }
        });
    }

    /**
     * The names worth asking after. The artist played most often leads however the row above is
     * ordered: a followed artist is a standing interest, but the one on repeat is the one whose
     * new song the listener would want to be told about first.
     */
    private List<String> freshArtists(List<Track> faces, Db db) {
        List<String> who = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        List<Track> sources = new ArrayList<Track>(db.topArtists(1));
        sources.addAll(faces);
        for (Track t : sources) {
            if (TextUtils.isEmpty(t.artist)) continue;
            if (!seen.add(t.artist.toLowerCase(Locale.ROOT))) continue;
            who.add(t.artist);
            if (who.size() == FRESH_ARTISTS) break;
        }
        return who;
    }

    private void deliverFresh(String asking, List<Track> tracks) {
        if (sections == null) return;
        if (asking.equals(askingFresh)) askingFresh = null;
        fresh = tracks;
        freshFor = asking;
        refresh();
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
        Recommender.forTaste(host, SHELF * FOR_YOU_PAGES, new Recommender.Callback() {
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

    /**
     * Lists that nobody made: each card is the radio around one song, named after whoever sang it.
     * One per artist, because two compilations grown from the same voice are the same compilation.
     * The radio is only asked for once a card is opened, so a row of them costs nothing to show.
     */
    private void addMixes(CharSequence title, List<Track> pool) {
        List<Track> candidates = new ArrayList<Track>();
        Set<String> seen = new HashSet<String>();
        for (Track t : pool) {
            // A song on the phone has no radio behind it; only the catalogue answers for a seed.
            if (!YtApi.isYouTube(t)) continue;
            String who = TextUtils.isEmpty(t.artist) ? t.title : t.artist;
            if (seen.add(who.toLowerCase(Locale.ROOT))) candidates.add(t);
        }
        Collections.shuffle(candidates, new Random(mixDraw));
        List<Track> seeds = candidates.size() > MIXES
                ? new ArrayList<Track>(candidates.subList(0, MIXES)) : candidates;
        if (seeds.isEmpty()) return;

        View slider = LayoutInflater.from(host).inflate(
                R.layout.item_home_slider, sections, false);
        TextView label = (TextView) slider.findViewById(R.id.section_title);
        label.setText(title);
        label.setBackground(null);

        LinearLayout row = (LinearLayout) slider.findViewById(R.id.slider_row);
        ArtLoader loader = ArtLoader.get(host);
        int artPx = host.getResources().getDimensionPixelSize(R.dimen.card);
        float radius = host.getResources().getDimension(R.dimen.art_radius);
        for (final Track seed : seeds) {
            final String name = host.getString(R.string.mix_of, Ui.artistOr(host, seed.artist));
            View card = LayoutInflater.from(host).inflate(R.layout.item_cover_card, row, false);
            ImageView art = (ImageView) card.findViewById(R.id.art);
            Ui.round(art, radius);
            loader.bind(art, seed, artPx, R.drawable.ic_playlist, Ui.dp(host, 34));
            ((TextView) card.findViewById(R.id.title)).setText(name);
            ((TextView) card.findViewById(R.id.subtitle)).setText(seed.title);
            card.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    host.push(TrackListPage.forMix(seed, name));
                }
            });
            row.addView(card);
        }
        sections.addView(slider);
    }

    /** Subscriptions lead the row; behind them come the artists with the most listening. */
    private List<Track> artistFaces(Db db) {
        List<Track> faces = new ArrayList<Track>();
        // A muted name starts out as one the row has already shown, which is how it never does.
        Set<String> seen = new HashSet<String>(Muted.keys(host));
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

    private void addShelf(CharSequence title, final List<Track> tracks, int limit,
                          final Page seeAll) {
        if (tracks.isEmpty()) return;
        final List<Track> shown = tracks.subList(0, Math.min(limit, tracks.size()));
        View shelf = LayoutInflater.from(host).inflate(
                R.layout.item_home_section, sections, false);
        TextView label = (TextView) shelf.findViewById(R.id.section_title);
        label.setText(title);
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
            rows.addView(trackRow(adapter, shown, i, rows));
        }
        sections.addView(shelf);
    }

    /**
     * A shelf deep enough to be turned rather than scrolled past. Suggestions are worth more
     * than the five rows a shelf can spare, but laying fifteen out in a column would push the
     * rest of the page under the listener's thumb.
     */
    private void addPagedShelf(CharSequence title, List<Track> tracks, int rows, int pages) {
        if (tracks.isEmpty()) return;
        final List<Track> shown = tracks.subList(0, Math.min(rows * pages, tracks.size()));
        View shelf = LayoutInflater.from(host).inflate(
                R.layout.item_home_pager, sections, false);
        ((TextView) shelf.findViewById(R.id.section_title)).setText(title);

        LinearLayout strip = (LinearLayout) shelf.findViewById(R.id.pager_row);
        TrackAdapter adapter = new TrackAdapter(host, shown);
        for (int start = 0; start < shown.size(); start += rows) {
            LinearLayout page = new LinearLayout(host);
            page.setOrientation(LinearLayout.VERTICAL);
            for (int i = start; i < Math.min(start + rows, shown.size()); i++) {
                page.addView(trackRow(adapter, shown, i, page));
            }
            strip.addView(page);
        }
        sections.addView(shelf);
    }

    private View trackRow(TrackAdapter adapter, final List<Track> shown, final int index,
                          ViewGroup parent) {
        View row = adapter.getView(index, null, parent);
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
        return row;
    }

    @Override
    public void onDestroy() {
        MediaLibrary.get().removeListener(this);
        Playback.get(host).removeListener(this);
        // A recommendation or a colour can still be in flight; this says the screen is gone.
        sections = null;
    }
}
