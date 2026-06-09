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

        // Android 12+ requires a specific flag to expose the SSID in the callback
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            networkCallback = new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                @Override
                public void onAvailable(Network network) {
                    // Fires instantly when connected, but SSID is often not fully loaded yet.
                    checkNetworkAndSwitchDNS(network);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                    // Fires milliseconds later when Android has successfully attached the SSID to the network info.
                    checkNetworkAndSwitchDNS(network);
                }

                @Override
                public void onLost(Network network) {
                    setPrivateDNS(true);
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    checkNetworkAndSwitchDNS(network);
                }
            };
        } else {
            // Fallback for older Android versions
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    checkNetworkAndSwitchDNS(network);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                    checkNetworkAndSwitchDNS(network);
                }

                @Override
                public void onLost(Network network) {
                    setPrivateDNS(true);
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    checkNetworkAndSwitchDNS(network);
                }
            };
        }

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        connectivityManager.registerNetworkCallback(request, networkCallback);

        // Initial Check (for when the service is manually started)
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
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);

        // Ignore if the network is not Wi-Fi
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return;
        }

        String currentSsid = null;

        // Attempt to extract SSID via TransportInfo (Android 10+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.net.TransportInfo transportInfo = caps.getTransportInfo();
            if (transportInfo instanceof android.net.wifi.WifiInfo) {
                currentSsid = ((android.net.wifi.WifiInfo) transportInfo).getSSID();
            }
        }

        // Fallback to WifiManager for older versions or masked SSIDs
        if (currentSsid == null || currentSsid.equals("<unknown ssid>")) {
            android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                android.net.wifi.WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null) {
                    currentSsid = info.getSSID();
                }
            }
        }

        if (currentSsid != null) {
            currentSsid = currentSsid.replace("\"", "");
        }
        
        // Retrieve the home network name saved from MainActivity
        String targetSsid = prefs.getString("home_ssid", "");

        // Execute DNS switch logic based on SSID match
        if (currentSsid != null && currentSsid.equals(targetSsid)) {
            setPrivateDNS(false); // Match found
        } else {
            setPrivateDNS(true);  // No match
        }
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