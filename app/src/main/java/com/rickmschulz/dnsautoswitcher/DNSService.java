package com.rickmschulz.dnsautoswitcher;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import java.util.Locale;

/**
 * Keeps Private DNS in the state that matches the current Wi-Fi network.
 *
 * Connected to the configured home SSID the mode is switched to "off" so the local resolver is
 * used; anywhere else it is switched back to "hostname" with the configured provider.
 *
 * All decisions run on a worker thread after a short settle delay, because the framework delivers
 * onAvailable/onCapabilitiesChanged/onLinkPropertiesChanged in a burst when a network connects and
 * the SSID is often still unknown in the first callback.
 */
// Deprecated Wi-Fi APIs are kept on purpose: they are the only fallback on API 28-32, where
// NetworkCapabilities.getTransportInfo() does not exist.
@SuppressWarnings("deprecation")
public class DNSService extends Service {

    private static final String TAG = "DNSService";

    static final String PREFS = "DNSAutoSwitcherPrefs";
    static final String KEY_HOME_SSID = "home_ssid";
    static final String KEY_PRIVATE_DNS = "privatedns_id";
    /** User intent: should the service run at all. Survives reboot and app updates. */
    static final String KEY_ENABLED = "service_enabled";
    /** Whether an instance is currently alive. Written by the service only. */
    static final String KEY_ALIVE = "service_alive";
    /** The mode this service last wrote, so stopping it can undo its own change only. */
    static final String KEY_LAST_APPLIED = "last_applied_mode";
    /** What the last evaluation actually saw, so "it did nothing" is never invisible. */
    static final String KEY_LAST_SSID = "last_seen_ssid";
    static final String KEY_LAST_STATE = "last_state";

    static final String ACTION_STOP = "com.rickmschulz.dnsautoswitcher.action.STOP";

    private static final String CHANNEL_ID = "dns_autoswitcher";
    private static final int NOTIFICATION_ID = 1;

    /** Private DNS modes as stored in Settings.Global.private_dns_mode. */
    private static final String MODE_OFF = SwitchPolicy.MODE_OFF;
    private static final String MODE_HOSTNAME = SwitchPolicy.MODE_HOSTNAME;

    private static final String SETTING_MODE = "private_dns_mode";
    private static final String SETTING_SPECIFIER = "private_dns_specifier";

    /** Network callbacks arrive in bursts; wait until the connection has settled. */
    private static final long SETTLE_MS = 900L;

    private ConnectivityManager connectivityManager;
    private SharedPreferences prefs;
    private HandlerThread workerThread;
    private Handler worker;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Runnable reconcileTask;

    /**
     * True when the app may write Settings.Global. Granted once over ADB; a plain runtime request
     * cannot obtain it, so every call site has to cope with it being missing.
     */
    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification(statusText()));
        markAlive(true);

        workerThread = new HandlerThread("dns-switch");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        reconcileTask = this::reconcile;

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        // FLAG_INCLUDE_LOCATION_INFO is what makes the SSID readable from the callback's
        // NetworkCapabilities on Android 12 and newer. Without it getSSID() reports
        // "<unknown ssid>" even with every permission granted, and the switch never happens.
        // (The int constructor only exists from API 31, hence the two shapes.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            networkCallback = new ConnectivityManager.NetworkCallback(
                    ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                @Override
                public void onAvailable(Network network) {
                    scheduleReconcile();
                }

                @Override
                public void onLost(Network network) {
                    scheduleReconcile();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    scheduleReconcile();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    scheduleReconcile();
                }
            };
        } else {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    scheduleReconcile();
                }

                @Override
                public void onLost(Network network) {
                    scheduleReconcile();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    scheduleReconcile();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    scheduleReconcile();
                }
            };
        }

        try {
            // Delivering on the worker keeps the binder calls to Settings off the main thread.
            connectivityManager.registerNetworkCallback(request, networkCallback, worker);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not register the network callback", e);
        }

        // Apply the right state straight away instead of waiting for the next network change.
        worker.post(reconcileTask);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
        }

        if (worker != null && reconcileTask != null) {
            worker.removeCallbacks(reconcileTask);
        }

        // Never leave the device with Private DNS disabled: if we were the one that turned it off,
        // put the configured provider back before going away.
        if (SwitchPolicy.shouldRestoreOnStop(prefs.getString(KEY_LAST_APPLIED, ""),
                prefs.getString(KEY_PRIVATE_DNS, ""))) {
            applyPrivateDns(MODE_HOSTNAME);
        }

        markAlive(false);
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);

        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void scheduleReconcile() {
        worker.removeCallbacks(reconcileTask);
        worker.postDelayed(reconcileTask, SETTLE_MS);
    }

    private void reconcile() {
        Network wifi = findWifiNetwork();
        String ssid = wifi == null ? null : currentSsid(wifi);

        String homeSsid = prefs.getString(KEY_HOME_SSID, "");
        SwitchPolicy.State state = SwitchPolicy.decide(homeSsid, prefs.getString(KEY_PRIVATE_DNS, ""),
                wifi != null, ssid);

        // Always on record: an unreadable SSID used to make the service do nothing at all with no
        // trace anywhere, which is indistinguishable from a service that is not running.
        Log.i(TAG, "wifi=" + (wifi != null) + " ssid=" + ssid
                + " home=" + homeSsid + " -> " + state);
        prefs.edit()
                .putString(KEY_LAST_SSID, ssid == null ? "" : ssid)
                .putString(KEY_LAST_STATE, state.name().toLowerCase(Locale.ROOT))
                .apply();

        String desired = SwitchPolicy.desiredMode(state);
        if (desired == null) {
            // Not enough information: leave the current setting alone rather than guess, but the
            // notification says so instead of looking healthy.
            Log.w(TAG, "Leaving Private DNS untouched: could not read the Wi-Fi name");
            updateNotification();
            return;
        }
        applyPrivateDns(desired);
    }

    @Nullable
    private Network findWifiNetwork() {
        if (connectivityManager == null) {
            return null;
        }
        for (Network network : connectivityManager.getAllNetworks()) {
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return network;
            }
        }
        return null;
    }

    @Nullable
    private String currentSsid(Network network) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
            if (caps != null) {
                WifiInfo info = (WifiInfo) caps.getTransportInfo();
                String ssid = SwitchPolicy.normalizeSsid(info == null ? null : info.getSSID());
                if (ssid != null) {
                    return ssid;
                }
            }
        }

        try {
            WifiManager wifiManager =
                    (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                return SwitchPolicy.normalizeSsid(wifiManager.getConnectionInfo().getSSID());
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot read the SSID without the location / nearby Wi-Fi permission", e);
        }
        return null;
    }

    private void applyPrivateDns(String desired) {
        String hostname = prefs.getString(KEY_PRIVATE_DNS, "");

        try {
            if (MODE_HOSTNAME.equals(desired)) {
                if (hostname.isEmpty()) {
                    return;
                }
                // Written independently of the mode: editing the hostname while already in
                // "hostname" mode used to leave the old provider in place until the next switch.
                String specifier = Settings.Global.getString(getContentResolver(), SETTING_SPECIFIER);
                if (SwitchPolicy.providerNeedsWrite(specifier, hostname)) {
                    Settings.Global.putString(getContentResolver(), SETTING_SPECIFIER, hostname);
                    Log.i(TAG, "Private DNS provider set to " + hostname);
                }
            }

            String current = currentMode();
            if (SwitchPolicy.modeNeedsWrite(current, desired)) {
                Settings.Global.putString(getContentResolver(), SETTING_MODE, desired);
                Log.i(TAG, "Private DNS mode " + current + " -> " + desired);
            }

            prefs.edit().putString(KEY_LAST_APPLIED, desired).apply();
        } catch (SecurityException e) {
            Log.e(TAG, "Missing WRITE_SECURE_SETTINGS; run the adb command from the app", e);
        }

        updateNotification();
    }

    private String currentMode() {
        try {
            String mode = Settings.Global.getString(getContentResolver(), SETTING_MODE);
            return mode == null ? MODE_OFF : mode;
        } catch (SecurityException e) {
            return MODE_OFF;
        }
    }

    private void markAlive(boolean alive) {
        prefs.edit().putBoolean(KEY_ALIVE, alive).apply();
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(statusText()));
        }
    }

    private String statusText() {
        if (!hasPermission(this)) {
            return getString(R.string.notification_missing_permission);
        }
        if (SwitchPolicy.State.UNKNOWN.name().toLowerCase(Locale.ROOT)
                .equals(prefs.getString(KEY_LAST_STATE, ""))) {
            return getString(R.string.notification_unreadable_ssid);
        }
        return MODE_HOSTNAME.equals(currentMode())
                ? getString(R.string.notification_away)
                : getString(R.string.notification_home);
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                // IMPORTANCE_MIN is hidden entirely by several OEMs, so the service would look
                // dead. LOW is still silent but visible.
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, DNSService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openPending)
                .addAction(0, getString(R.string.notification_action_stop), stopPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                // Without this the notification is held back for ~10s after startForeground().
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }
}
