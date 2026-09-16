package com.nota.player;

import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;

import com.nota.data.CrashLog;
import com.nota.data.Db;
import com.nota.data.Prefetch;
import com.nota.data.Prefs;
import com.nota.data.Recommender;
import com.nota.data.Signals;
import com.nota.data.YtApi;
import com.nota.model.Track;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the MediaPlayer, the queue and audio focus. PlayerService only mirrors this state
 * into a notification and a MediaSession, so the UI never has to wait on a service binding.
 */
public class Playback implements MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnPreparedListener,
        AudioManager.OnAudioFocusChangeListener {

    public interface Listener {
        void onTrackChanged(Track track);

        void onStateChanged(boolean playing, boolean buffering);

        void onQueueChanged();
    }

    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    public static final String KEY_AUTOPLAY = "autoplay";
    /** Enough to keep playing for half an hour without asking the network again. */
    private static final int AUTOPLAY_BATCH = 8;

    private static Playback instance;

    public static synchronized Playback get(Context c) {
        if (instance == null) instance = new Playback(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    private MediaPlayer mp;
    private final List<Track> queue = new ArrayList<Track>();
    /** Indices into queue, in the order they will actually play. */
    private final List<Integer> order = new ArrayList<Integer>();
    private int orderPos = -1;

    private boolean shuffle;
    private int repeat = REPEAT_OFF;
    private boolean playing;
    private boolean prepared;
    private boolean buffering;
    private boolean playWhenReady;
    private boolean resumeOnFocusGain;
    private int audioSessionId;
    /** Bumped on every open so a late stream URL cannot hijack a track the user skipped. */
    private int openGeneration;
    /** The track the player is on, held back so the next open can score the one it replaces. */
    private Track opened;
    /** The track a recommendation batch was last asked for, so the tail is only extended once. */
    private String extendedFrom;
    private boolean extending;

    private AudioFocusRequest focusRequest;
    private boolean noisyRegistered;
    private Runnable sleepTask;
    private long sleepAtMs;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) pause();
        }
    };

    private Playback(Context app) {
        this.app = app;
        this.audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
    }

    // ---- listeners ----

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fireTrack() {
        Track t = current();
        for (Listener l : listeners) l.onTrackChanged(t);
    }

    private void fireState() {
        for (Listener l : listeners) l.onStateChanged(playing, buffering);
    }

    private void fireQueue() {
        for (Listener l : listeners) l.onQueueChanged();
    }

    // ---- state ----

    public Track current() {
        if (orderPos < 0 || orderPos >= order.size()) return null;
        int i = order.get(orderPos);
        return i >= 0 && i < queue.size() ? queue.get(i) : null;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isBuffering() {
        return buffering;
    }

    public boolean hasTrack() {
        return current() != null;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    public int repeatMode() {
        return repeat;
    }

    public int audioSessionId() {
        return audioSessionId;
    }

    public List<Track> queue() {
        List<Track> out = new ArrayList<Track>(order.size());
        for (int i : order) out.add(queue.get(i));
        return out;
    }

    public int queuePosition() {
        return orderPos;
    }

    public long position() {
        try {
            return prepared && mp != null ? mp.getCurrentPosition() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public long duration() {
        Track t = current();
        if (t == null) return 0;
        try {
            return prepared && mp != null ? mp.getDuration() : t.durationMs;
        } catch (IllegalStateException e) {
            return t.durationMs;
        }
    }

    // ---- queue control ----

    public void play(List<Track> tracks, int index) {
        if (tracks == null || tracks.isEmpty()) return;
        extendedFrom = null;
        queue.clear();
        queue.addAll(tracks);
        buildOrder(Math.max(0, Math.min(index, queue.size() - 1)));
        fireQueue();
        openCurrent(true);
    }

    public void playSingle(Track t) {
        play(Collections.singletonList(t), 0);
    }

    /** Shuffles the whole list and starts from a random entry. */
    public void shuffleAll(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        shuffle = true;
        extendedFrom = null;
        queue.clear();
        queue.addAll(tracks);
        buildOrder((int) (Math.random() * queue.size()));
        fireQueue();
        openCurrent(true);
    }

    private void buildOrder(int startIndex) {
        order.clear();
        for (int i = 0; i < queue.size(); i++) order.add(i);
        if (shuffle) {
            Collections.shuffle(order);
            int at = order.indexOf(startIndex);
            if (at > 0) Collections.swap(order, 0, at);
            orderPos = 0;
        } else {
            orderPos = startIndex;
        }
    }

    public void setShuffle(boolean on) {
        if (shuffle == on) return;
        shuffle = on;
        Track cur = current();
        int curIndex = cur == null ? 0 : queue.indexOf(cur);
        buildOrder(Math.max(0, curIndex));
        fireQueue();
        fireState();
    }

    public void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        fireState();
    }

    public void setRepeat(int mode) {
        repeat = mode;
        fireState();
    }

    public void addToQueue(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        boolean wasEmpty = queue.isEmpty();
        for (Track t : tracks) {
            queue.add(t);
            order.add(queue.size() - 1);
        }
        fireQueue();
        if (wasEmpty) {
            orderPos = 0;
            openCurrent(true);
        }
    }

    public void playNext(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        if (queue.isEmpty()) {
            play(tracks, 0);
            return;
        }
        int insertAt = orderPos + 1;
        for (Track t : tracks) {
            queue.add(t);
            order.add(Math.min(insertAt++, order.size()), queue.size() - 1);
        }
        fireQueue();
    }

    public void removeFromQueue(int orderIndex) {
        if (orderIndex < 0 || orderIndex >= order.size()) return;
        boolean wasCurrent = orderIndex == orderPos;
        int trackIndex = order.remove(orderIndex);
        // The track has to leave `queue` too, otherwise a later reshuffle resurrects it.
        queue.remove(trackIndex);
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i) > trackIndex) order.set(i, order.get(i) - 1);
        }
        if (orderIndex < orderPos) orderPos--;
        if (order.isEmpty()) {
            stop();
            fireQueue();
            return;
        }
        if (wasCurrent) {
            if (orderPos >= order.size()) orderPos = 0;
            openCurrent(playing);
        }
        fireQueue();
    }

    public void jumpTo(int orderIndex) {
        if (orderIndex < 0 || orderIndex >= order.size()) return;
        orderPos = orderIndex;
        openCurrent(true);
    }

    // ---- transport ----

    public void toggle() {
        if (playing) pause();
        else resume();
    }

    public void resume() {
        if (current() == null) return;
        if (!prepared) {
            openCurrent(true);
            return;
        }
        if (!requestFocus()) return;
        try {
            mp.start();
        } catch (IllegalStateException e) {
            openCurrent(true);
            return;
        }
        playing = true;
        playWhenReady = true;
        registerNoisy();
        PlayerService.start(app);
        fireState();
    }

    public void pause() {
        playWhenReady = false;
        if (mp != null && prepared) {
            try {
                mp.pause();
            } catch (IllegalStateException ignored) {
            }
        }
        if (playing) {
            playing = false;
            fireState();
        }
        unregisterNoisy();
    }

    public void stop() {
        closeOut(null);
        extendedFrom = null;
        playWhenReady = false;
        playing = false;
        prepared = false;
        buffering = false;
        releasePlayer();
        abandonFocus();
        unregisterNoisy();
        order.clear();
        queue.clear();
        orderPos = -1;
        fireTrack();
        fireState();
        PlayerService.stop(app);
    }

    public void next() {
        if (order.isEmpty()) return;
        if (orderPos + 1 < order.size()) {
            orderPos++;
        } else if (repeat == REPEAT_ALL) {
            orderPos = 0;
        } else {
            orderPos = 0;
            playing = false;
            openCurrent(false);
            return;
        }
        openCurrent(true);
    }

    /** Restarts the track when more than 3 seconds in, like every other player. */
    public void previous() {
        if (order.isEmpty()) return;
        if (position() > 3000) {
            seekTo(0);
            return;
        }
        if (orderPos > 0) orderPos--;
        else if (repeat == REPEAT_ALL) orderPos = order.size() - 1;
        openCurrent(true);
    }

    public void seekTo(long ms) {
        if (mp != null && prepared) {
            try {
                mp.seekTo((int) ms);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    // ---- engine ----

    /**
     * Scores the outgoing track, while its player is still alive enough to say how far it got.
     * A position of zero means the track never really started — a failed stream rather than a
     * rejected song — so it is left unscored.
     */
    private void closeOut(Track next) {
        Track done = opened;
        opened = null;
        if (done == null) return;
        long pos = 0, dur = 0;
        try {
            if (mp != null && prepared) {
                pos = mp.getCurrentPosition();
                dur = mp.getDuration();
            }
        } catch (IllegalStateException ignored) {
        }
        if (pos <= 0) return;
        if (dur <= 0) dur = done.durationMs;
        Signals.get(app).ended(done, dur > 0 ? (float) pos / dur : 0f, next);
    }

    private void openCurrent(boolean autoStart) {
        Track t = current();
        if (t == null) return;
        closeOut(t);
        playWhenReady = autoStart;
        prepared = false;
        buffering = true;
        openGeneration++;

        releasePlayer();
        mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mp.setOnPreparedListener(this);
        mp.setOnCompletionListener(this);
        mp.setOnErrorListener(this);
        // Network streams stall if the CPU sleeps while the screen is off.
        mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK);
        audioSessionId = mp.getAudioSessionId();

        try {
            if (t.isRemote()) {
                mp.setDataSource(app, Uri.parse(streamUrl(t)));
            } else {
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, t.id);
                mp.setDataSource(app, uri);
            }
            mp.prepareAsync();
        } catch (Exception e) {
            buffering = false;
            onError(mp, 0, 0);
            return;
        }
        opened = t;

        PlayerService.start(app);
        fireTrack();
        fireState();
    }

    public void onPrepared(MediaPlayer player) {
        prepared = true;
        buffering = false;
        Fx.get(app).attach(audioSessionId);
        if (playWhenReady && requestFocus()) {
            try {
                player.start();
                playing = true;
                registerNoisy();
            } catch (IllegalStateException ignored) {
            }
        }
        Track t = current();
        if (t != null) Db.get(app).recordPlay(t);
        maybeExtend();
        Prefetch.get(app).warm(upcoming(4));
        fireTrack();
        fireState();
    }

    /**
     * Keeps the queue from running dry. Reaching the last track means the listener is out of
     * things they chose, which is exactly where a played song should lead somewhere rather than
     * back into the list it came from.
     */
    private void maybeExtend() {
        if (repeat != REPEAT_OFF || extending) return;
        if (orderPos < 0 || orderPos != order.size() - 1) return;
        if (!Prefs.getBool(app, KEY_AUTOPLAY, true)) return;
        final Track seed = current();
        if (seed == null || seed.key().equals(extendedFrom)) return;
        extending = true;
        extendedFrom = seed.key();
        Recommender.nextFor(app, seed, queue(), AUTOPLAY_BATCH, new Recommender.Callback() {
            public void onTracks(List<Track> tracks) {
                extending = false;
                Track now = current();
                // The answer is only useful if the listener is still where they were when it
                // was asked for; a skip in the meantime deserves a fresh question.
                if (tracks.isEmpty() || now == null || !seed.key().equals(now.key())) return;
                addToQueue(tracks);
                Prefetch.get(app).warm(upcoming(4));
            }
        });
    }

    public void onCompletion(MediaPlayer player) {
        // Scored here rather than in closeOut: some devices report position 0 once a track ends,
        // and a finished song is the one signal worth never losing.
        Track done = opened;
        opened = null;
        if (done != null) {
            Signals.get(app).ended(done, 1f, repeat == REPEAT_ONE ? done : null);
        }
        if (repeat == REPEAT_ONE) {
            seekTo(0);
            resume();
            return;
        }
        if (orderPos + 1 < order.size()) {
            orderPos++;
            openCurrent(true);
        } else if (repeat == REPEAT_ALL) {
            orderPos = 0;
            openCurrent(true);
        } else {
            playing = false;
            playWhenReady = false;
            unregisterNoisy();
            fireState();
        }
    }

    private String streamUrl(Track t) {
        if (!YtApi.isYouTube(t)) return t.data;
        File ready = Prefetch.get(app).ready(t);
        return ready != null ? Uri.fromFile(ready).toString() : YtApi.streamUrl(t);
    }

    /** The tracks after the current one, in the order they will play. */
    private List<Track> upcoming(int limit) {
        List<Track> out = new ArrayList<Track>(limit);
        for (int i = orderPos + 1; i < order.size() && out.size() < limit; i++) {
            out.add(queue.get(order.get(i)));
        }
        return out;
    }

    public boolean onError(MediaPlayer player, int what, int extra) {
        Track playingTrack = current();
        if (YtApi.isYouTube(playingTrack)) {
            // The report is handed out through public Downloads, so it carries the failure
            // and not the address or the id of what was being listened to.
            CrashLog.note(app, "yt play failed what=" + what + " extra=" + extra);
        }
        // A stream that died says nothing about taste, so the track leaves unscored.
        opened = null;
        prepared = false;
        buffering = false;
        playing = false;
        releasePlayer();
        unregisterNoisy();
        fireState();
        // A broken file or a dead stream should not stall the queue.
        main.postDelayed(new Runnable() {
            public void run() {
                if (order.size() > 1 && playWhenReady) next();
            }
        }, 400);
        return true;
    }

    private void releasePlayer() {
        if (mp != null) {
            Fx.get(app).release();
            try {
                mp.reset();
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
        audioSessionId = 0;
        prepared = false;
    }

    // ---- audio focus ----

    private boolean requestFocus() {
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(this)
                        .setWillPauseWhenDucked(false)
                        .build();
            }
            result = audio.requestAudioFocus(focusRequest);
        } else {
            result = audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        } else {
            audio.abandonAudioFocus(this);
        }
    }

    public void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                resumeOnFocusGain = false;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                resumeOnFocusGain = playing;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mp != null && prepared) try {
                    mp.setVolume(0.25f, 0.25f);
                } catch (IllegalStateException ignored) {
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (mp != null && prepared) try {
                    mp.setVolume(1f, 1f);
                } catch (IllegalStateException ignored) {
                }
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false;
                    resume();
                }
                break;
        }
    }

    private void registerNoisy() {
        if (!noisyRegistered) {
            app.registerReceiver(noisyReceiver,
                    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        }
    }

    private void unregisterNoisy() {
        if (noisyRegistered) {
            try {
                app.unregisterReceiver(noisyReceiver);
            } catch (Exception ignored) {
            }
            noisyRegistered = false;
        }
    }

    // ---- sleep timer ----

    public void setSleepTimer(long minutes) {
        cancelSleepTimer();
        if (minutes <= 0) return;
        sleepAtMs = System.currentTimeMillis() + minutes * 60000L;
        sleepTask = new Runnable() {
            public void run() {
                sleepTask = null;
                sleepAtMs = 0;
                pause();
            }
        };
        main.postDelayed(sleepTask, minutes * 60000L);
    }

    public void cancelSleepTimer() {
        if (sleepTask != null) main.removeCallbacks(sleepTask);
        sleepTask = null;
        sleepAtMs = 0;
    }

    /** Milliseconds left on the sleep timer, or 0 when it is off. */
    public long sleepRemainingMs() {
        return sleepAtMs == 0 ? 0 : Math.max(0, sleepAtMs - System.currentTimeMillis());
    }
}
