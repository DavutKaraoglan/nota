package com.nota.ui;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.nota.R;
import com.nota.data.ArtLoader;
import com.nota.model.Track;
import com.nota.player.Playback;

/** The strip above the tab bar. Stays mounted for the whole session. */
public class MiniPlayer implements Playback.Listener {

    private final MainActivity host;
    private final View root;
    private final ImageView art;
    private final TextView title, subtitle;
    private final ImageButton toggle;
    private final ProgressBar progress;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    public MiniPlayer(MainActivity host, ViewGroup holder) {
        this.host = host;
        root = LayoutInflater.from(host).inflate(R.layout.view_mini, holder, false);
        holder.addView(root);

        art = (ImageView) root.findViewById(R.id.art);
        title = (TextView) root.findViewById(R.id.title);
        subtitle = (TextView) root.findViewById(R.id.subtitle);
        toggle = (ImageButton) root.findViewById(R.id.btn_toggle);
        progress = (ProgressBar) root.findViewById(R.id.progress);
        Ui.round(art, host.getResources().getDimension(R.dimen.art_radius));
        title.setSelected(true);

        root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // The player opens from where this bar sits, so it hands over its position
                // and the window animation is left out of it entirely.
                int[] location = new int[2];
                root.getLocationOnScreen(location);
                host.startActivity(new Intent(host, NowPlayingActivity.class)
                        .putExtra(NowPlayingActivity.EXTRA_BAR_TOP, location[1]));
                host.overridePendingTransition(0, 0);
            }
        });
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Playback.get(host).toggle();
            }
        });
        root.findViewById(R.id.btn_next).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Playback.get(host).next();
            }
        });

        Playback.get(host).addListener(this);
        bind();
    }

    public void onResume() {
        bind();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    public void onPause() {
        handler.removeCallbacks(tick);
    }

    public void onDestroy() {
        handler.removeCallbacks(tick);
        Playback.get(host).removeListener(this);
    }

    private void bind() {
        Playback playback = Playback.get(host);
        Track t = playback.current();
        if (t == null) {
            root.setVisibility(View.GONE);
            return;
        }
        root.setVisibility(View.VISIBLE);
        title.setText(t.title);
        subtitle.setText(Ui.artistOr(host, t.artist));
        ArtLoader.get(host).bind(art, t, Ui.dp(host, 54),
                R.drawable.ic_note, Ui.dp(host, 12));
        toggle.setImageResource(playback.isPlaying()
                ? R.drawable.ic_pause : R.drawable.ic_play);
        toggle.setContentDescription(host.getString(
                playback.isPlaying() ? R.string.pause : R.string.play));
        updateProgress();
    }

    private void updateProgress() {
        Playback playback = Playback.get(host);
        long total = playback.duration();
        progress.setProgress(total > 0 ? (int) (playback.position() * 1000 / total) : 0);
    }

    public void onTrackChanged(Track track) {
        bind();
    }

    public void onStateChanged(boolean playing, boolean buffering) {
        bind();
    }

    public void onQueueChanged() {
    }
}
