package tv.shelf.home;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Watches the "now playing" media sessions that apps publish for Alexa and the remote's
 * play/pause buttons, and records what each app is playing in {@link History}. It reads no
 * notifications: being a notification listener is simply what Android requires before it lets
 * an app see other apps' media sessions. Enabled by install.sh --setup
 * ({@code cmd notification allow_listener}); the "Recently used" option "Apps only" turns
 * recording off.
 */
public final class MediaListener extends NotificationListenerService {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Ui.worker("shelf-media");
    private final Map<SessionKey, Watch> watching = new HashMap<>();
    private MediaSessionManager sessions;
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::track;

    /** Wraps a session token so it can key a map (tokens compare by value). */
    private static final class SessionKey {
        final Object token;

        SessionKey(Object token) {
            this.token = token;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SessionKey && ((SessionKey) o).token.equals(token);
        }

        @Override
        public int hashCode() {
            return token.hashCode();
        }
    }

    private final class Watch extends MediaController.Callback {
        final MediaController controller;
        String lastKey;

        Watch(MediaController controller) {
            this.controller = controller;
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            maybeRecord();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            maybeRecord();
        }

        @Override
        public void onSessionDestroyed() {
            untrack(this);
        }

        void maybeRecord() {
            try {
                PlaybackState state = controller.getPlaybackState();
                MediaMetadata m = controller.getMetadata();
                if (state == null || m == null) return;
                int s = state.getState();
                if (s != PlaybackState.STATE_PLAYING && s != PlaybackState.STATE_BUFFERING) return;
                if (!"on".equals(Config.get(MediaListener.this).get(Config.RECENT))) return;
                if (!"titles".equals(Config.get(MediaListener.this).get(Config.RECENT_MEDIA))) return;
                String pkg = controller.getPackageName();
                String title = first(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
                String sub = first(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                        MediaMetadata.METADATA_KEY_ALBUM);
                if (title == null || pkg == null || pkg.equals(getPackageName())) return;
                String key = title + "|" + sub;
                boolean fresh = !key.equals(lastKey);
                lastKey = key;
                Bitmap art = firstBitmap(m);
                String artUri = art == null ? first(m, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
                        MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI) : null;
                android.app.PendingIntent open = controller.getSessionActivity();
                if (!fresh && art == null) {
                    // same item still playing: just refresh the time so the row order stays right
                    io.execute(() -> History.record(getApplicationContext(), pkg, title, sub, null, open));
                    return;
                }
                io.execute(() -> {
                    Bitmap picture = art != null ? art : fetch(artUri);
                    History.record(getApplicationContext(), pkg, title, sub, picture, open);
                });
            } catch (RuntimeException e) {
                Log.w("shelf", "now playing failed", e);
            }
        }
    }

    @Override
    public void onListenerConnected() {
        try {
            sessions = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(this, MediaListener.class);
            sessions.addOnActiveSessionsChangedListener(changed, me, ui);
            track(sessions.getActiveSessions(me));
        } catch (RuntimeException e) {
            Log.w("shelf", "media sessions unavailable", e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        for (Watch w : new ArrayList<>(watching.values())) untrack(w);
    }

    @Override
    public void onDestroy() {
        onListenerDisconnected();
        io.shutdownNow();
        super.onDestroy();
    }

    private void track(List<MediaController> controllers) {
        if (controllers == null) return;
        for (MediaController c : controllers) {
            SessionKey key = new SessionKey(c.getSessionToken());
            if (watching.containsKey(key)) continue;
            Watch w = new Watch(c);
            watching.put(key, w);
            c.registerCallback(w, ui);
            w.maybeRecord();
        }
    }

    private void untrack(Watch w) {
        w.controller.unregisterCallback(w);
        watching.remove(new SessionKey(w.controller.getSessionToken()));
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return null;
    }

    private static Bitmap firstBitmap(MediaMetadata m) {
        for (String k : new String[] {MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap b = m.getBitmap(k);
            if (b != null) return b;
        }
        return null;
    }

    /** Artwork given as a web address (some apps send a link instead of the picture). */
    private static Bitmap fetch(String uri) {
        if (uri == null || !(uri.startsWith("https://") || uri.startsWith("http://"))) return null;
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(uri).openConnection();
            http.setConnectTimeout(8000);
            http.setReadTimeout(8000);
            if (http.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = http.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }
}
