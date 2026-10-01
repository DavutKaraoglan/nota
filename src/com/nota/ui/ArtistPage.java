package com.nota.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.Uri;
import android.text.Layout;
import android.view.Menu;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.nota.R;
import com.nota.data.ArtLoader;
import com.nota.data.ArtistBio;
import com.nota.data.ArtistInfo;
import com.nota.data.ArtistLinks;
import com.nota.data.ArtistPhoto;
import com.nota.data.Db;
import com.nota.data.Follows;
import com.nota.data.Muted;
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
    private static final int BANNER_FADE_MS = 320;
    /** How much of the life is offered before it is asked for in full. */
    private static final int BIO_LINES = 4;

    private final String name;
    /** A track of theirs that has already been played, so the header has a cover for free. */
    private final Track face;

    private TrackAdapter adapter;
    private ListView list;
    private View header;
    private Button follow;
    private ProgressBar progress;
    private boolean searched;
    /** Empty while the catalogue is being asked, so the header is only asked once. */
    private String banner;
    /** The picture outranks the colour wash, which a later catalogue answer would else repaint. */
    private boolean bannerUp;
    /** What the page last painted the window with, kept so coming back to it is not bare. */
    private Drawable wash;

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
        header = inflater.inflate(R.layout.header_artist, list, false);
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
        bindListened();
        loadInfo();
        loadLinks();
        loadBio();
        return root;
    }

    private void bindFollow(boolean following) {
        follow.setText(following ? R.string.following : R.string.follow);
    }

    /** What the listener has spent on this artist, which only they can have earned. */
    private void bindListened() {
        TextView line = (TextView) header.findViewById(R.id.listened);
        long minutes = Db.get(host).listenedMs(name) / 60000;
        if (minutes < 1) {
            line.setVisibility(View.GONE);
            return;
        }
        line.setText(minutes < 60
                ? host.getString(R.string.listened_m, minutes)
                : host.getString(R.string.listened_hm, minutes / 60, minutes % 60));
        line.setVisibility(View.VISIBLE);
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

    /** The artist's own pages, as MusicBrainz has them filed. */
    private void loadLinks() {
        final View box = header.findViewById(R.id.links_box);
        final LinearLayout row = (LinearLayout) header.findViewById(R.id.links);
        ArtistLinks.lookup(host, name, new ArtistLinks.Callback() {
            public void onLinks(List<ArtistLinks.Link> links) {
                if (adapter == null || links.isEmpty()) return;
                row.removeAllViews();
                for (ArtistLinks.Link link : links) row.addView(chip(row, link));
                box.setVisibility(View.VISIBLE);
            }
        });
    }

    /** Who they are, in the sentences an encyclopedia opens their article with. */
    private void loadBio() {
        final TextView bio = (TextView) header.findViewById(R.id.bio);
        final TextView more = (TextView) header.findViewById(R.id.bio_more);
        View.OnClickListener open = new View.OnClickListener() {
            public void onClick(View v) {
                // A header is not the place for a whole life, until it is asked for.
                bio.setMaxLines(Integer.MAX_VALUE);
                more.setVisibility(View.GONE);
            }
        };
        bio.setOnClickListener(open);
        more.setOnClickListener(open);
        ArtistBio.lookup(host, name, new ArtistBio.Callback() {
            public void onBio(String text) {
                if (adapter == null || text.length() == 0) return;
                bio.setText(text);
                bio.setVisibility(View.VISIBLE);
                bio.post(new Runnable() {
                    public void run() {
                        // Only a life that does not fit has anything left to open, and what
                        // does not fit is exactly what the last line had to cut off.
                        Layout laid = bio.getLayout();
                        if (adapter == null || laid == null) return;
                        if (laid.getLineCount() < BIO_LINES
                                || laid.getEllipsisCount(BIO_LINES - 1) == 0) return;
                        more.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private View chip(LinearLayout row, final ArtistLinks.Link link) {
        View view = LayoutInflater.from(host).inflate(R.layout.item_link, row, false);
        ((ImageView) view.findViewById(R.id.icon)).setImageResource(icon(link.label));
        TextView label = (TextView) view.findViewById(R.id.label);
        label.setText(link.label);
        label.setPaintFlags(label.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        view.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent go = new Intent(Intent.ACTION_VIEW, Uri.parse(link.url));
                // The page opens wherever the phone opens pages, and on one with nothing
                // that can, the tap is simply nothing rather than a crash.
                if (go.resolveActivity(host.getPackageManager()) != null) host.startActivity(go);
            }
        });
        return view;
    }

    /** A site is known by its own mark; anything without one is just a page on the web. */
    private static int icon(String label) {
        if (label.equals("Spotify")) return R.drawable.ic_link_spotify;
        if (label.equals("YouTube")) return R.drawable.ic_link_youtube;
        if (label.equals("YouTube Music")) return R.drawable.ic_link_youtubemusic;
        if (label.equals("Instagram")) return R.drawable.ic_link_instagram;
        if (label.equals("X")) return R.drawable.ic_link_x;
        if (label.equals("Facebook")) return R.drawable.ic_link_facebook;
        if (label.equals("Wikipedia")) return R.drawable.ic_link_wikipedia;
        if (label.equals("Apple Music")) return R.drawable.ic_link_applemusic;
        if (label.equals("Deezer")) return R.drawable.ic_link_deezer;
        if (label.equals("Tidal")) return R.drawable.ic_link_tidal;
        if (label.equals("SoundCloud")) return R.drawable.ic_link_soundcloud;
        if (label.equals("Bandcamp")) return R.drawable.ic_link_bandcamp;
        if (label.equals("TikTok")) return R.drawable.ic_link_tiktok;
        if (label.equals("VK")) return R.drawable.ic_link_vk;
        return R.drawable.ic_link_site;
    }

    @Override
    public String title() {
        // The name is already the biggest thing on the page, under the picture it belongs to.
        return "";
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
    public void onOverflow(View anchor) {
        final boolean muted = Muted.has(host, name);
        PopupMenu menu = new PopupMenu(host, anchor);
        menu.getMenu().add(Menu.NONE, 1, 0, muted ? R.string.unmute_artist : R.string.mute_artist);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                boolean now = Muted.toggle(host, name);
                Toast.makeText(host, host.getString(
                        now ? R.string.muted_artist : R.string.unmuted_artist, name),
                        Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        menu.show();
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
        Track cover = face != null ? face : items.isEmpty() ? null : items.get(0);
        loadTint(cover);
        loadBanner();
    }

    /**
     * The wide picture from the artist's own page on the catalogue, behind the whole header. It
     * arrives after the colour wash and takes its place, so the screen is never bare while it
     * travels; a name the catalogue gives no banner for is looked for on Wikidata instead, and
     * one that is nowhere simply keeps the wash.
     */
    private void loadBanner() {
        if (banner != null) return;
        banner = "";
        // Under a scrim, half the screen's width is as sharp as it needs to be, and it is the
        // difference between a 130 KB picture and a 75 KB one.
        final int w = Math.min(host.getResources().getDisplayMetrics().widthPixels, 540);
        final int h = w * host.getResources().getDimensionPixelSize(R.dimen.wash_height)
                / host.getResources().getDisplayMetrics().widthPixels;
        YtApi.artistBanner(host, name, new YtApi.UrlCallback() {
            public void onUrl(String url) {
                if (adapter == null) return;
                if (url == null) {
                    ArtistPhoto.lookup(host, name, w, new ArtistPhoto.Callback() {
                        public void onUrl(String photo) {
                            showBanner(photo, w, h);
                        }
                    });
                    return;
                }
                showBanner(url, w, h);
            }
        });
    }

    private void showBanner(String url, final int w, final int h) {
        if (adapter == null || url == null) return;
        banner = url;
        ArtLoader.get(host).loadWide(url, w, h, new ArtLoader.BitmapCallback() {
            public void onBitmap(Bitmap bmp) {
                if (adapter == null || !visible()) return;
                bannerUp = true;
                dissolveTo(new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(host.getResources(), Ui.band(bmp, w, h)),
                        Ui.scrim(host)}));
            }
        });
    }

    /** Same wash as the home screen, so walking into an artist keeps the colour of the music. */
    private void loadTint(final Track cover) {
        if (bannerUp) return;
        if (cover == null) {
            paint(null);
            return;
        }
        Ui.coverColor(host, cover, new Ui.ColorCallback() {
            public void onColor(int colour) {
                if (adapter == null || bannerUp || !visible()) return;
                if (colour == 0) {
                    paint(null);
                    return;
                }
                int rgb = colour & 0xFFFFFF;
                paint(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x73000000 | rgb, 0x2E000000 | rgb, rgb & 0xFFFFFF}));
            }
        });
    }

    /** Applying it and remembering it are the same act, so neither happens without the other. */
    private void paint(Drawable drawable) {
        wash = drawable;
        host.setTint(drawable);
    }

    /**
     * The picture takes the colour wash's place by dissolving into it rather than replacing it
     * between two frames. Only the crossing is the transition: what the page remembers painting
     * is the picture itself, so coming back to the page arrives at it rather than fading again.
     */
    private void dissolveTo(Drawable next) {
        Drawable from = wash != null ? wash : new ColorDrawable(0);
        TransitionDrawable crossing = new TransitionDrawable(new Drawable[]{from, next});
        crossing.setCrossFadeEnabled(true);
        wash = next;
        host.setTint(crossing);
        crossing.startTransition(BANNER_FADE_MS);
    }

    @Override
    public void onShow() {
        host.setTint(wash);
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
