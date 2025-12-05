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

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                super.onAvailable(network);
                checkNetworkAndSwitchDNS(network);
            }

            @Override
            public void onLost(Network network) {
                super.onLost(network);
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

        // Initial Check
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork != null) {
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNetwork);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                checkNetworkAndSwitchDNS(activeNetwork);
            } else {
                setPrivateDNS(true);
            }
        } else {
            setPrivateDNS(true);
        }
    }

    private void checkNetworkAndSwitchDNS(Network network) {
        // In a full version, you would check SSID here.
        // For now, we assume any Wi-Fi is Home.
        setPrivateDNS(false);
    }

    private void setPrivateDNS(boolean enable) {
        String hostname = prefs.getString("privatedns_id", "");

        if (enable && hostname.isEmpty()) {
            return;
        }

        try {
            String currentMode = Settings.Global.getString(getContentResolver(), "private_dns_mode");
            String targetMode = enable ? "hostname" : "off";

            if (!targetMode.equals(currentMode)) {
                if (enable) {
                    Settings.Global.putString(getContentResolver(), "private_dns_mode", "hostname");
                    Settings.Global.putString(getContentResolver(), "private_dns_specifier", hostname);
                } else {
                    Settings.Global.putString(getContentResolver(), "private_dns_mode", "off");
                }
            }
        } catch (SecurityException e) {
            Log.e("DNSAuto", "Permission Denied!", e);
        }
    }

    private void startForegroundService() {
        String channelId = "dns_switcher_channel";
        NotificationChannel channel = new NotificationChannel(channelId, "DNS Monitor", NotificationManager.IMPORTANCE_MIN);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, channelId)
                .setContentTitle("DNSAutoSwitcher")
                .setContentText("Monitoring Network...")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();

        // UPDATED: Use SPECIAL_USE type to prevent crashes on Android 14+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}