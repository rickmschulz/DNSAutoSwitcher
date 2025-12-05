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

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("DNSAutoSwitcherPrefs", MODE_PRIVATE);
        startForegroundService();
        startMonitoring();
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
                // Disconnected from Wi-Fi -> Enable Private DNS (Away Mode)
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
    }

    private void checkNetworkAndSwitchDNS(Network network) {
        // Simple Logic: If Wi-Fi connects -> Disable Private DNS (Home Mode)
        // (Assuming you only use Wi-Fi at home. To make this smarter, we'd need Location permission to check SSID)
        setPrivateDNS(false);
    }

    private void setPrivateDNS(boolean enable) {
        String hostname = prefs.getString("privatedns_id", ""); // Generic name

        if (enable && hostname.isEmpty()) {
            Log.e("DNSAuto", "PrivateDNS ID not set! Cannot switch.");
            return;
        }

        try {
            if (enable) {
                // AWAY MODE: Enable Private DNS
                // We use the user input directly as the hostname (e.g., "12345.dns.nextdns.io")
                Settings.Global.putString(getContentResolver(), "private_dns_mode", "hostname");
                Settings.Global.putString(getContentResolver(), "private_dns_specifier", hostname);
                Log.d("DNSAuto", "Switched to Private DNS: " + hostname);
            } else {
                // HOME MODE: Disable Private DNS (Use DHCP/Pi-hole)
                Settings.Global.putString(getContentResolver(), "private_dns_mode", "off");
                Log.d("DNSAuto", "Switched to Local DNS (Off)");
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

        startForeground(1, notification);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}