package com.nota.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.nota.R;
import com.nota.data.ArtLoader;
import com.nota.data.CrashLog;
import com.nota.data.Db;
import com.nota.data.Signals;
import com.nota.model.Track;
import com.nota.ui.MainActivity;
import com.nota.ui.Ui;

/** Keeps playback alive in the background and mirrors it into a MediaStyle notification. */
public class PlayerService extends Service implements Playback.Listener {

    public static final String ACTION_TOGGLE = "com.nota.action.TOGGLE";
    public static final String ACTION_NEXT = "com.nota.action.NEXT";
    public static final String ACTION_PREV = "com.nota.action.PREV";
    public static final String ACTION_STOP = "com.nota.action.STOP";
    public static final String ACTION_FAVORITE = "com.nota.action.FAVORITE";
    /** Broadcast so any visible screen can re-tick its heart after the notification toggles it. */
    public static final String ACTION_FAVORITE_CHANGED = "com.nota.action.FAVORITE_CHANGED";

    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;
    private static final int ART_PX = 512;

    private Playback playback;
    private MediaSession session;
    private NotificationManager notifications;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String artKeyInNotification;
    private boolean destroyed;

    public static void start(Context c) {
        Intent i = new Intent(c, PlayerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            // From Android 12 a foreground service may not be started while the app sits in the
            // background, which is exactly where auto-advance after a failed track lands.
            CrashLog.note(c, "player service start refused: " + e);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, PlayerService.class));
    }

    /** The notification is the app on the lock screen, so it is worded in the chosen language. */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Ui.chosen(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        playback = Playback.get(this);
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        createSession();
        playback.addListener(this);
        startForeground(NOTIF_ID, buildNotification(null));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notifications.createNotificationChannel(ch);
    }

    private void createSession() {
        session = new MediaSession(this, "Nota");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                playback.resume();
            }

            @Override
            public void onPause() {
                playback.pause();
            }

            @Override
            public void onSkipToNext() {
                playback.next();
            }

            @Override
            public void onSkipToPrevious() {
                playback.previous();
            }

            @Override
            public void onSeekTo(long pos) {
                playback.seekTo(pos);
            }

            @Override
            public void onStop() {
                playback.stop();
            }

            @Override
            public void onCustomAction(String action, android.os.Bundle extras) {
                if (ACTION_FAVORITE.equals(action)) toggleFavorite();
            }
        });
        session.setMediaButtonReceiver(PendingIntent.getBroadcast(this, 0,
                new Intent(Intent.ACTION_MEDIA_BUTTON)
                        .setClass(this, MediaButtonReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | flagImmutable()));
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE.equals(action)) playback.toggle();
        else if (ACTION_NEXT.equals(action)) playback.next();
        else if (ACTION_PREV.equals(action)) playback.previous();
        else if (ACTION_FAVORITE.equals(action)) toggleFavorite();
        else if (ACTION_STOP.equals(action)) {
            playback.pause();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification(null));
        // Nothing survives a process restart: Playback would come back with an empty queue.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        playback.removeListener(this);
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        stopForeground(true);
        super.onDestroy();
    }

    // ---- Playback.Listener ----

    public void onTrackChanged(Track track) {
        updateSessionMetadata(track);
        pushNotification();
    }

    public void onStateChanged(boolean playing, boolean buffering) {
        updateSessionState();
        pushNotification();
        // Staying foreground while buffering: leaving early makes a later resume throw
        // ForegroundServiceStartNotAllowedException because the app is no longer visible.
        if (!playing && !buffering) stopForeground(false);
    }

    public void onQueueChanged() {
    }

    private void updateSessionMetadata(Track t) {
        if (t == null) return;
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST,
                        t.artist == null ? getString(R.string.unknown_artist) : t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM,
                        t.album == null ? "" : t.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, t.durationMs);
        Bitmap art = ArtLoader.get(this).cached(t, ART_PX);
        if (art != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(b.build());
        loadArtAsync(t);
    }

    private void updateSessionState() {
        int state = playback.isPlaying() ? PlaybackState.STATE_PLAYING
                : playback.isBuffering() ? PlaybackState.STATE_BUFFERING
                : PlaybackState.STATE_PAUSED;
        boolean fav = isFavorite(playback.current());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .addCustomAction(new PlaybackState.CustomAction.Builder(
                        ACTION_FAVORITE,
                        getString(fav ? R.string.favorite_remove : R.string.favorite_add),
                        fav ? R.drawable.ic_favorite : R.drawable.ic_favorite_border).build())
                .setState(state, playback.position(), playback.isPlaying() ? 1f : 0f)
                .build());
    }

    /** Album art arrives late; refresh the notification once it is decoded. */
    private void loadArtAsync(final Track t) {
        final String key = ArtLoader.keyFor(t);
        if (key == null || key.equals(artKeyInNotification)) return;
        new Thread(new Runnable() {
            public void run() {
                final Bitmap bmp = ArtLoader.get(PlayerService.this).load(t, ART_PX);
                if (bmp == null) return;
                main.post(new Runnable() {
                    public void run() {
                        if (destroyed || playback.current() != t) return;
                        artKeyInNotification = key;
                        MediaMetadata current = session.getController().getMetadata();
                        MediaMetadata.Builder b = current != null
                                ? new MediaMetadata.Builder(current) : new MediaMetadata.Builder();
                        b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp);
                        session.setMetadata(b.build());
                        pushNotification(bmp);
                    }
                });
            }
        }, "nota-notif-art").start();
    }

    private void toggleFavorite() {
        Track t = playback.current();
        if (t == null) return;
        if (Db.get(this).toggleFavorite(t)) Signals.get(this).liked(t);
        sendBroadcast(new Intent(ACTION_FAVORITE_CHANGED).setPackage(getPackageName()));
        updateSessionState();
        pushNotification();
    }

    private boolean isFavorite(Track t) {
        if (t == null) return false;
        return Db.get(this).isFavorite(t.key());
    }

    private void pushNotification() {
        pushNotification(null);
    }

    private void pushNotification(Bitmap art) {
        if (playback.current() == null) return;
        notifications.notify(NOTIF_ID, buildNotification(art));
    }

    private Notification buildNotification(Bitmap art) {
        Track t = playback.current();
        boolean playing = playback.isPlaying();

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        b.setSmallIcon(R.mipmap.ic_stat_note)
                .setContentTitle(t == null ? getString(R.string.app_name) : t.title)
                .setContentText(t == null ? "" : subtitle(t))
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class)
                                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT | flagImmutable()))
                .setDeleteIntent(servicePendingIntent(ACTION_STOP))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(playing);

        if (art == null && t != null) art = ArtLoader.get(this).cached(t, ART_PX);
        if (art != null) b.setLargeIcon(art);

        boolean fav = isFavorite(t);
        b.addAction(new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this,
                        fav ? R.drawable.ic_favorite : R.drawable.ic_favorite_border),
                getString(fav ? R.string.favorite_remove : R.string.favorite_add),
                servicePendingIntent(ACTION_FAVORITE)).build());
        b.addAction(new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_prev),
                getString(R.string.previous), servicePendingIntent(ACTION_PREV)).build());
        b.addAction(new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this,
                        playing ? R.drawable.ic_pause : R.drawable.ic_play),
                getString(playing ? R.string.pause : R.string.play),
                servicePendingIntent(ACTION_TOGGLE)).build());
        b.addAction(new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_next),
                getString(R.string.next), servicePendingIntent(ACTION_NEXT)).build());

        // The heart is action 0 but stays out of the compact view; collapsed space is only
        // wide enough for the three transport buttons.
        Notification.MediaStyle style = new Notification.MediaStyle()
                .setShowActionsInCompactView(1, 2, 3);
        if (session != null) style.setMediaSession(session.getSessionToken());
        b.setStyle(style);

        return b.build();
    }

    private String subtitle(Track t) {
        String artist = t.artist == null ? getString(R.string.unknown_artist) : t.artist;
        return t.album == null ? artist : artist + " · " + t.album;
    }

    private PendingIntent servicePendingIntent(String action) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | flagImmutable());
    }

    private static int flagImmutable() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }
}
