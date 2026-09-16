package com.nota.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.nota.R;
import com.nota.data.ArtLoader;
import com.nota.data.Db;
import com.nota.data.Lyrics;
import com.nota.data.Prefs;
import com.nota.model.Track;
import com.nota.player.Playback;
import com.nota.player.PlayerService;

import java.util.List;

public class NowPlayingActivity extends Activity
        implements Playback.Listener {

    /** Where the mini bar sat when it was tapped, so the screen can open out of it. */
    public static final String EXTRA_BAR_TOP = "bar_top";

    /** The controls sit on the blurred cover, so they are tinted for that backdrop, not the theme. */
    private static final int CONTROL_ON = 0xFFFFFFFF;
    private static final int CONTROL_IDLE = 0xB3FFFFFF;

    private static final int HEART_EVERY_MS = 110;

    private ImageView art;
    private View controls;
    /** Holds the rising hearts above everything, so no layout has to make room for them. */
    private FrameLayout heartLayer;
    private TextView title, subtitle, position, duration, lyricsEmpty, lyricLine;
    private SeekBar seek;
    private ImageButton toggle, repeat, favorite;
    private ListView lyricsList;
    private DragSheet sheet;
    private LockScrollView scroll;
    private View playerPage;
    private ViewGroup queueList;
    private int barTop;
    private boolean closing;

    private Playback playback;
    private TrackAdapter queueAdapter;
    private Lyrics lyrics;
    private LyricAdapter lyricAdapter;
    private boolean showingLyrics;
    private boolean lookingUpLyrics;
    private boolean dragging;
    private int highlighted = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() {
            updateProgress();
            // A line that lands half a second late reads as a mistake, so the timer runs
            // tighter while there are timed words to follow.
            handler.postDelayed(this, lyrics != null && lyrics.synced ? 250 : 500);
        }
    };
    /** Keeps releasing hearts for as long as the button is held. */
    private final Runnable rising = new Runnable() {
        public void run() {
            releaseHeart();
            handler.postDelayed(this, HEART_EVERY_MS);
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        int mode = Prefs.getInt(base, SettingsPage.KEY_THEME, SettingsPage.THEME_SYSTEM);
        if (mode != SettingsPage.THEME_SYSTEM) {
            Configuration c = new Configuration(base.getResources().getConfiguration());
            c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | (mode == SettingsPage.THEME_DARK
                    ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            base = base.createConfigurationContext(c);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_now_playing);
        playback = Playback.get(this);

        art = (ImageView) findViewById(R.id.art);
        title = (TextView) findViewById(R.id.title);
        subtitle = (TextView) findViewById(R.id.subtitle);
        position = (TextView) findViewById(R.id.position);
        duration = (TextView) findViewById(R.id.duration);
        seek = (SeekBar) findViewById(R.id.seek);
        toggle = (ImageButton) findViewById(R.id.btn_toggle);
        repeat = (ImageButton) findViewById(R.id.btn_repeat);
        favorite = (ImageButton) findViewById(R.id.btn_favorite);
        lyricsList = (ListView) findViewById(R.id.lyrics);
        lyricsEmpty = (TextView) findViewById(R.id.lyrics_empty);
        lyricLine = (TextView) findViewById(R.id.lyric_line);
        Ui.round(art, getResources().getDimension(R.dimen.art_radius) * 2);
        controls = findViewById(R.id.controls);
        Ui.round(controls, Ui.dp(this, 22));
        title.setSelected(true);
        sheet = (DragSheet) findViewById(R.id.sheet);
        sheet.setListener(new DragSheet.Listener() {
            public void onDismissed() {
                close();
            }
        });
        scroll = (LockScrollView) findViewById(R.id.scroll);
        sheet.setScroller(scroll);
        playerPage = findViewById(R.id.player_page);
        queueList = (ViewGroup) findViewById(R.id.queue_list);
        queueAdapter = new TrackAdapter(this, playback.queue());
        Ui.round(findViewById(R.id.handle), Ui.dp(this, 2));
        // The player fills the window and the queue starts right under it, so one upward
        // scroll walks from one to the other instead of opening a second surface.
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    public void onGlobalLayout() {
                        int h = scroll.getHeight();
                        if (h <= 0 || playerPage.getLayoutParams().height == h) return;
                        playerPage.getLayoutParams().height = h;
                        playerPage.requestLayout();
                    }
                });
        barTop = getIntent().getIntExtra(EXTRA_BAR_TOP,
                getResources().getDisplayMetrics().heightPixels);
        if (state == null) {
            sheet.setTranslationY(barTop);
            sheet.setAlpha(0f);
            sheet.animate().translationY(0).alpha(1f).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        toggle.setColorFilter(0xFF101010);
        for (int id : new int[]{R.id.btn_prev, R.id.btn_next}) {
            ((ImageButton) findViewById(id)).setColorFilter(CONTROL_ON);
        }
        lyricAdapter = new LyricAdapter();
        lyricsList.setAdapter(lyricAdapter);

        findViewById(R.id.btn_close).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                close();
            }
        });
        title.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                Track t = playback.current();
                if (t == null) return false;
                ClipboardManager clip =
                        (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clip.setPrimaryClip(ClipData.newPlainText(t.title, t.title));
                Toast.makeText(NowPlayingActivity.this, R.string.copied,
                        Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        subtitle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Track t = playback.current();
                if (t == null || t.artist == null || t.artist.length() == 0) return;
                startActivity(new Intent(NowPlayingActivity.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_ARTIST)
                        .putExtra(MainActivity.EXTRA_ARTIST, t.artist));
                close();
            }
        });


        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                playback.toggle();
            }
        });
        findViewById(R.id.btn_next).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                playback.next();
            }
        });
        findViewById(R.id.btn_prev).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                playback.previous();
            }
        });
        repeat.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                playback.cycleRepeat();
                updateModes();
            }
        });
        favorite.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Routed through the service so the button, the notification and the
                // lock screen all toggle through one path and stay in sync.
                startService(new Intent(NowPlayingActivity.this, PlayerService.class)
                        .setAction(PlayerService.ACTION_FAVORITE));
            }
        });
        favorite.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                handler.post(rising);
                return true;
            }
        });
        favorite.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    handler.removeCallbacks(rising);
                }
                return false;
            }
        });
        View.OnClickListener flipLyrics = new View.OnClickListener() {
            public void onClick(View v) {
                showingLyrics = !showingLyrics;
                applyLyricsVisibility();
            }
        };
        art.setOnClickListener(flipLyrics);
        lyricsEmpty.setOnClickListener(flipLyrics);
        lyricLine.setOnClickListener(flipLyrics);
        lyricsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                showingLyrics = false;
                applyLyricsVisibility();
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                if (fromUser) position.setText(Ui.duration(playback.duration() * value / 1000));
            }

            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                long total = playback.duration();
                if (total > 0) playback.seekTo(total * s.getProgress() / 1000);
            }
        });

        playback.addListener(this);
        bind();
    }

    /** Sends the screen back down to the bar it came from, then leaves without a flicker. */
    private void close() {
        if (closing) return;
        closing = true;
        sheet.animate().translationY(barTop).alpha(0f).setDuration(220)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    public void run() {
                        finish();
                        overridePendingTransition(0, 0);
                    }
                }).start();
    }

    @Override
    public void onBackPressed() {
        if (scroll.getScrollY() > 0) scroll.smoothScrollTo(0, 0);
        else close();
    }

    @Override
    protected void onResume() {
        super.onResume();
        bind();
        handler.removeCallbacks(tick);
        handler.post(tick);
        IntentFilter filter = new IntentFilter(PlayerService.ACTION_FAVORITE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(favoriteWatcher, filter,
                Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(favoriteWatcher, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(rising);
        unregisterReceiver(favoriteWatcher);
    }

    private final BroadcastReceiver favoriteWatcher = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            updateFavorite();
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(tick);
        playback.removeListener(this);
    }

    private void bind() {
        Track t = playback.current();
        if (t == null) {
            finish();
            return;
        }
        title.setText(t.title);
        subtitle.setText(Ui.artistOr(this, t.artist));
        ArtLoader.get(this).bind(art, t, Ui.dp(this, 320),
                R.drawable.ic_note, Ui.dp(this, 90));
        loadTint(t);
        duration.setText(Ui.duration(playback.duration()));

        loadLyrics(t);

        fillQueue();
        updateToggle();
        updateModes();
        updateFavorite();
        updateProgress();
    }

    /** The words come off the disk or the network, so a late answer is dropped if the song moved on. */
    private void loadLyrics(final Track t) {
        lyrics = null;
        highlighted = -1;
        lookingUpLyrics = true;
        lyricLine.animate().cancel();
        lyricLine.setAlpha(1f);
        lyricLine.setTranslationY(0f);
        lyricLine.setText("");
        lyricAdapter.notifyDataSetChanged();
        applyLyricsVisibility();
        Lyrics.load(this, t, new Lyrics.Callback() {
            public void onLyrics(Lyrics found) {
                Track now = playback.current();
                if (now == null || !now.equals(t)) return;
                lyrics = found;
                lookingUpLyrics = false;
                lyricAdapter.notifyDataSetChanged();
                applyLyricsVisibility();
                updateProgress();
            }
        });
    }

    /**
     * Rows are laid out rather than listed: a ListView inside the page would fight it for drags.
     * Only what is still to come is shown — the song playing is already the whole screen above,
     * and the ones behind it are not a queue any more.
     */
    private void fillQueue() {
        List<Track> q = playback.queue();
        Track cur = playback.current();
        int from = playback.queuePosition() + 1;
        queueAdapter.setItems(q);
        queueAdapter.setActiveKey(cur == null ? null : cur.key());
        findViewById(R.id.queue_title).setVisibility(from < q.size() ? View.VISIBLE : View.GONE);
        queueList.removeAllViews();
        for (int i = from; i < q.size(); i++) {
            final int index = i;
            View row = queueAdapter.getView(i, null, queueList);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    playback.jumpTo(index);
                }
            });
            queueList.addView(row);
        }
    }

    /** One heart, let go from the button and drifting out of sight. */
    private void releaseHeart() {
        if (heartLayer == null) {
            heartLayer = new FrameLayout(this);
            addContentView(heartLayer, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        int[] from = new int[2];
        int[] layer = new int[2];
        favorite.getLocationInWindow(from);
        heartLayer.getLocationInWindow(layer);

        int size = Ui.dp(this, 16 + (int) (Math.random() * 10));
        final ImageView heart = new ImageView(this);
        heart.setImageResource(R.drawable.ic_favorite);
        heart.setColorFilter(CONTROL_ON);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.leftMargin = from[0] - layer[0] + (favorite.getWidth() - size) / 2;
        lp.topMargin = from[1] - layer[1] + (favorite.getHeight() - size) / 2;
        heartLayer.addView(heart, lp);

        heart.animate()
                .translationY(-Ui.dp(this, 110 + (int) (Math.random() * 90)))
                .translationX((float) (Math.random() - 0.5) * Ui.dp(this, 90))
                .rotation((float) (Math.random() - 0.5) * 50f)
                .alpha(0f)
                .setDuration(900 + (long) (Math.random() * 400))
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    public void run() {
                        heartLayer.removeView(heart);
                    }
                }).start();
    }

    private void updateToggle() {
        boolean playing = playback.isPlaying();
        toggle.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        toggle.setContentDescription(getString(playing ? R.string.pause : R.string.play));
    }

    private void updateModes() {
        int mode = playback.repeatMode();
        repeat.setImageResource(mode == Playback.REPEAT_ONE
                ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        repeat.setColorFilter(mode == Playback.REPEAT_OFF ? CONTROL_IDLE : CONTROL_ON);
        repeat.setContentDescription(getString(mode == Playback.REPEAT_OFF
                ? R.string.repeat_off
                : mode == Playback.REPEAT_ONE ? R.string.repeat_one : R.string.repeat_all));
    }

    private void updateFavorite() {
        Track t = playback.current();
        boolean fav = t != null && Db.get(this).isFavorite(t.key());
        favorite.setImageResource(fav
                ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        favorite.setColorFilter(fav ? CONTROL_ON : CONTROL_IDLE);
    }

    private void updateProgress() {
        long pos = playback.position();
        long total = playback.duration();
        if (!dragging) {
            seek.setProgress(total > 0 ? (int) (pos * 1000 / total) : 0);
            position.setText(Ui.duration(pos));
            if (total > 0) duration.setText(Ui.duration(total));
        }
        if (lyrics != null && lyrics.synced) {
            int index = lyrics.indexAt(pos);
            if (index != highlighted) {
                highlighted = index;
                lyricAdapter.notifyDataSetChanged();
                if (showingLyrics && index >= 0) {
                    lyricsList.smoothScrollToPosition(Math.max(0, index - 2));
                }
                showLine(index < 0 ? "" : lyrics.lines.get(index).text);
            }
        }
    }

    /** One line at a time above the title, swapped with a short lift so the change reads as movement. */
    private void showLine(final String text) {
        if (text.equals(lyricLine.getText().toString())) return;
        lyricLine.animate().alpha(0f).translationY(-Ui.dp(this, 4)).setDuration(130)
                .withEndAction(new Runnable() {
                    public void run() {
                        lyricLine.setText(text);
                        lyricLine.setTranslationY(Ui.dp(NowPlayingActivity.this, 6));
                        lyricLine.animate().alpha(1f).translationY(0f).setDuration(180).start();
                    }
                }).start();
    }

    private void applyLyricsVisibility() {
        boolean has = lyrics != null && !lyrics.lines.isEmpty();
        lyricsList.setVisibility(showingLyrics && has ? View.VISIBLE : View.GONE);
        lyricsEmpty.setVisibility(showingLyrics && !has ? View.VISIBLE : View.GONE);
        sheet.setDragEnabled(!showingLyrics);
        scroll.setLocked(showingLyrics);
        if (showingLyrics && !has) {
            lyricsEmpty.setText(lookingUpLyrics ? R.string.lyrics_loading : R.string.lyrics_missing);
        }
        art.setVisibility(showingLyrics ? View.GONE : View.VISIBLE);
        // The single line says what the list is already saying, so only one of them is ever up.
        boolean timed = lyrics != null && lyrics.synced;
        lyricLine.setVisibility(timed && !showingLyrics ? View.VISIBLE : View.GONE);
    }

    /**
     * Tints the control row with the cover's own colour, fading out towards the top so the
     * row melts into the screen instead of sitting in a box.
     */
    private void loadTint(final Track t) {
        Ui.coverColor(this, t, new Ui.ColorCallback() {
            public void onColor(int colour) {
                if (playback.current() == t) applyTint(colour);
            }
        });
    }

    private void applyTint(int colour) {
        int bar = colour == 0 ? getColor(R.color.accent) : vivid(colour);
        seek.setProgressTintList(ColorStateList.valueOf(bar));
        seek.setThumbTintList(ColorStateList.valueOf(bar));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
        if (colour == 0) {
            controls.setBackground(null);
            return;
        }
        int rgb = colour & 0xFFFFFF;
        controls.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xD9000000 | rgb, 0x40000000 | rgb, rgb}));
    }

    /** The seek bar sits on a dark page, so the cover colour is pushed up before it is used. */
    private static int vivid(int colour) {
        float[] hsv = new float[3];
        Color.colorToHSV(colour, hsv);
        hsv[1] = Math.min(1f, Math.max(hsv[1], 0.45f));
        hsv[2] = Math.max(hsv[2], 0.95f);
        return Color.HSVToColor(hsv);
    }

    public void onTrackChanged(Track track) {
        bind();
    }

    public void onStateChanged(boolean playing, boolean buffering) {
        updateToggle();
    }

    public void onQueueChanged() {
        fillQueue();
    }

    private class LyricAdapter extends BaseAdapter {
        public int getCount() {
            return lyrics == null ? 0 : lyrics.lines.size();
        }

        public Object getItem(int i) {
            return lyrics.lines.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int i, View convertView, ViewGroup parent) {
            TextView v = (TextView) convertView;
            if (v == null) {
                v = (TextView) LayoutInflater.from(NowPlayingActivity.this)
                        .inflate(R.layout.row_lyric, parent, false);
            }
            Lyrics.Line line = lyrics.lines.get(i);
            v.setText(line.text);
            boolean active = lyrics.synced && i == highlighted;
            v.setTextColor(getColor(active ? R.color.accent : R.color.text_secondary));
            v.setTextSize(active ? 18 : 16);
            return v;
        }
    }
}
