package com.nota.data;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

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
}
