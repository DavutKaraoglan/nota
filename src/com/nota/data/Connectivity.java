package com.nota.data;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

/** Whether the catalogue is reachable at all, which decides what the app is allowed to offer. */
public class Connectivity {

    public static boolean isOnline(Context c) {
        ConnectivityManager cm = (ConnectivityManager)
                c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network net = cm.getActiveNetwork();
        NetworkCapabilities caps = net == null ? null : cm.getNetworkCapabilities(net);
        // A captive hotel portal answers every request, so a link alone is not a connection.
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    public interface Listener {
        void onOnline(boolean online);
    }

    /**
     * Watches the connection come and go. The system answers on a thread of its own and says
     * which network changed rather than whether there is one left, so every call turns back
     * into the same question, on the main thread.
     */
    public static final class Watcher {

        private static final Handler MAIN = new Handler(Looper.getMainLooper());

        private final Context app;
        private final ConnectivityManager cm;
        private final ConnectivityManager.NetworkCallback callback;
        private boolean last;

        public Watcher(Context c, final Listener listener) {
            app = c.getApplicationContext();
            cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            last = isOnline(app);
            callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network n) {
                    tell(listener);
                }

                @Override
                public void onLost(Network n) {
                    tell(listener);
                }

                @Override
                public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                    tell(listener);
                }
            };
            if (cm != null) try {
                cm.registerDefaultNetworkCallback(callback);
            } catch (Throwable ignored) {
            }
        }

        public boolean online() {
            return last;
        }

        private void tell(final Listener listener) {
            MAIN.post(new Runnable() {
                public void run() {
                    boolean now = isOnline(app);
                    if (now == last) return;
                    last = now;
                    listener.onOnline(now);
                }
            });
        }

        public void stop() {
            if (cm != null) try {
                cm.unregisterNetworkCallback(callback);
            } catch (Throwable ignored) {
            }
        }
    }
}
