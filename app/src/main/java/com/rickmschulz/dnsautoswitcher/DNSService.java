package com.rickmschulz.dnsautoswitcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

public class DNSService extends Service {

    public static boolean isRunning = false;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        prefs = getSharedPreferences("DNSAutoSwitcherPrefs", MODE_PRIVATE);

        startForegroundService();
        startMonitoring();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.e("DNSAuto", "Error unregistering callback", e);
            }
        }
    }

    private void startMonitoring() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        // 1. SETUP THE LISTENER (For future changes)
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                super.onAvailable(network);
                Log.d("DNSAuto", "Network Available: " + network);
                checkNetworkAndSwitchDNS(network);
            }

            @Override
            public void onLost(Network network) {
                super.onLost(network);
                Log.d("DNSAuto", "Network Lost");
                // If we lost Wi-Fi, assume we are "Away"
                setPrivateDNS(true);
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                checkNetworkAndSwitchDNS(network);
            }
        };

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        connectivityManager.registerNetworkCallback(request, networkCallback);

        // 2. PERFORM INITIAL CHECK (For right now)
        // We manually check the current state so we don't have to wait for a callback event.
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork != null) {
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNetwork);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                // We are already on Wi-Fi -> Check it and likely Turn OFF
                checkNetworkAndSwitchDNS(activeNetwork);
            } else {
                // We are on Mobile Data or nothing -> Turn ON
                setPrivateDNS(true);
            }
        } else {
            // No network at all -> Default to Secure (Turn ON)
            setPrivateDNS(true);
        }
    }

    private void checkNetworkAndSwitchDNS(Network network) {
        // In this simple version, any Wi-Fi connection triggers "Home Mode" (DNS Off).
        // This runs immediately on start if Wi-Fi is connected.
        setPrivateDNS(false);
    }

    private void setPrivateDNS(boolean enable) {
        String hostname = prefs.getString("privatedns_id", "");

        if (enable && hostname.isEmpty()) {
            Log.e("DNSAuto", "PrivateDNS ID not set! Cannot switch.");
            return;
        }

        try {
            // Check current state to avoid redundant writes (optional optimization)
            String currentMode = Settings.Global.getString(getContentResolver(), "private_dns_mode");
            String targetMode = enable ? "hostname" : "off";

            // Only write if the state is actually different
            if (!targetMode.equals(currentMode)) {
                if (enable) {
                    // AWAY MODE: Enable Private DNS
                    Settings.Global.putString(getContentResolver(), "private_dns_mode", "hostname");
                    Settings.Global.putString(getContentResolver(), "private_dns_specifier", hostname);
                    Log.d("DNSAuto", "Switched to Private DNS: " + hostname);
                } else {
                    // HOME MODE: Disable Private DNS
                    Settings.Global.putString(getContentResolver(), "private_dns_mode", "off");
                    Log.d("DNSAuto", "Switched to Local DNS (Off)");
                }
            }
        } catch (SecurityException e) {
            Log.e("DNSAuto", "Permission Denied! Run the ADB command.", e);
        }
    }

    private void startForegroundService() {
        String channelId = "dns_switcher_channel";
        NotificationChannel channel = new NotificationChannel(channelId, "DNS Monitor", NotificationManager.IMPORTANCE_MIN);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, channelId)
                .setContentTitle("Privacy Pilot")
                .setContentText("Monitoring Network...")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}