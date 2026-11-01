package com.rickmschulz.dnsautoswitcher;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.util.Log;

import java.util.Locale;

/**
 * Applies the right Private DNS state for the network the device is on.
 *
 * Static and Context-driven so that both the foreground service and the periodic watchdog job can
 * call it: the watchdog exists precisely for the case where an OEM power manager has killed the
 * service, which means it must not depend on the service being alive.
 */
final class Reconciler {

    static final String TAG = "DNSService";

    static final String PREFS = "DNSAutoSwitcherPrefs";
    static final String KEY_HOME_SSID = "home_ssid";
    static final String KEY_PRIVATE_DNS = "privatedns_id";
    static final String KEY_ENABLED = "service_enabled";
    static final String KEY_ALIVE = "service_alive";
    static final String KEY_LAST_APPLIED = "last_applied_mode";
    static final String KEY_LAST_SSID = "last_seen_ssid";
    static final String KEY_LAST_STATE = "last_state";
    static final String KEY_LAST_CHECK = "last_check_millis";
    /** Opt-in: keep the monitoring service in the foreground with a persistent notification. */
    static final String KEY_SHOW_NOTIFICATION = "show_notification";

    static final String MODE_OFF = SwitchPolicy.MODE_OFF;
    static final String MODE_HOSTNAME = SwitchPolicy.MODE_HOSTNAME;

    private static final String SETTING_MODE = "private_dns_mode";
    private static final String SETTING_SPECIFIER = "private_dns_specifier";

    private Reconciler() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * True when the app may write Settings.Global. Granted once over ADB; a plain runtime request
     * cannot obtain it, so every call site has to cope with it being missing.
     */
    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Whether the Wi-Fi name can be read at all. Android 13+ normally asks only for
     * NEARBY_WIFI_DEVICES, but several OEM builds - MIUI and HyperOS among them - still redact the
     * name without location, so either permission counts.
     */
    static boolean canReadWifiName(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Which of the Wi-Fi permissions are held, so a change in either one is noticed. */
    static String wifiPermissionFingerprint(Context context) {
        return (granted(context, Manifest.permission.NEARBY_WIFI_DEVICES) ? "1" : "0")
                + (granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ? "1" : "0")
                + (granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) ? "1" : "0");
    }

    private static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    static final class Observation {
        final boolean onWifi;
        final String ssid;

        Observation(boolean onWifi, String ssid) {
            this.onWifi = onWifi;
            this.ssid = ssid;
        }
    }

    /**
     * What the network layer currently looks like. Two independent sources: the connectivity
     * stack, and WifiManager - because the callback's capabilities can be redacted while
     * WifiManager still knows the name.
     */
    static Observation observe(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network wifi = findWifiNetwork(cm);
        String ssid = wifi == null ? null : ssidFromCapabilities(cm, wifi);

        if (ssid == null) {
            String fromWifiManager = ssidFromWifiManager(context);
            if (fromWifiManager != null) {
                return new Observation(true, fromWifiManager);
            }
        }
        return new Observation(wifi != null, ssid);
    }

    /**
     * Observe, decide, write, and record what happened. Returns the state it decided on, or null
     * when there was not enough information to act.
     */
    static SwitchPolicy.State evaluate(Context context) {
        SharedPreferences prefs = prefs(context);
        Observation seen = observe(context);

        String homeSsid = prefs.getString(KEY_HOME_SSID, "");
        SwitchPolicy.State state = SwitchPolicy.decide(homeSsid,
                prefs.getString(KEY_PRIVATE_DNS, ""), seen.onWifi, seen.ssid);

        Log.i(TAG, "onWifi=" + seen.onWifi + " ssid=" + seen.ssid
                + " home=" + homeSsid + " -> " + state);

        prefs.edit()
                .putString(KEY_LAST_SSID, seen.ssid == null ? "" : seen.ssid)
                .putString(KEY_LAST_STATE, state.name().toLowerCase(Locale.ROOT))
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .apply();

        String desired = SwitchPolicy.desiredMode(state);
        if (desired == null) {
            Log.w(TAG, "Leaving Private DNS untouched: could not read the Wi-Fi name");
            return null;
        }

        applyMode(context, desired);
        return state;
    }

    static void applyMode(Context context, String desired) {
        SharedPreferences prefs = prefs(context);
        String hostname = prefs.getString(KEY_PRIVATE_DNS, "");

        try {
            if (MODE_HOSTNAME.equals(desired)) {
                if (hostname.isEmpty()) {
                    return;
                }
                // Written independently of the mode: editing the hostname while already in
                // "hostname" mode used to leave the old provider in place until the next switch.
                String specifier = Settings.Global.getString(context.getContentResolver(), SETTING_SPECIFIER);
                if (SwitchPolicy.providerNeedsWrite(specifier, hostname)) {
                    Settings.Global.putString(context.getContentResolver(), SETTING_SPECIFIER, hostname);
                    Log.i(TAG, "Private DNS provider set to " + hostname);
                }
            }

            String current = currentMode(context);
            if (SwitchPolicy.modeNeedsWrite(current, desired)) {
                Settings.Global.putString(context.getContentResolver(), SETTING_MODE, desired);
                Log.i(TAG, "Private DNS mode " + current + " -> " + desired);
            }

            prefs.edit().putString(KEY_LAST_APPLIED, desired).apply();
        } catch (SecurityException e) {
            Log.e(TAG, "Missing WRITE_SECURE_SETTINGS; run the adb command from the app", e);
        }
    }

    static String currentMode(Context context) {
        try {
            String mode = Settings.Global.getString(context.getContentResolver(), SETTING_MODE);
            return mode == null ? MODE_OFF : mode;
        } catch (SecurityException e) {
            return MODE_OFF;
        }
    }

    /** Put back the provider this app switched off, and only that. */
    static void restoreOnStop(Context context) {
        SharedPreferences prefs = prefs(context);
        if (SwitchPolicy.shouldRestoreOnStop(prefs.getString(KEY_LAST_APPLIED, ""),
                prefs.getString(KEY_PRIVATE_DNS, ""))) {
            applyMode(context, MODE_HOSTNAME);
        }
    }

    private static Network findWifiNetwork(ConnectivityManager cm) {
        if (cm == null) {
            return null;
        }

        Network active = cm.getActiveNetwork();
        if (isWifi(cm, active)) {
            return active;
        }

        try {
            for (Network network : cm.getAllNetworks()) {
                if (isWifi(cm, network)) {
                    return network;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not enumerate networks", e);
        }
        return null;
    }

    private static boolean isWifi(ConnectivityManager cm, Network network) {
        if (network == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private static String ssidFromCapabilities(ConnectivityManager cm, Network network) {
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null) {
            return null;
        }
        WifiInfo info = (WifiInfo) caps.getTransportInfo();
        return SwitchPolicy.normalizeSsid(info == null ? null : info.getSSID());
    }

    private static String ssidFromWifiManager(Context context) {
        try {
            WifiManager wifiManager =
                    (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return null;
            }
            WifiInfo info = wifiManager.getConnectionInfo();
            return SwitchPolicy.normalizeSsid(info == null ? null : info.getSSID());
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot read the SSID without the location / nearby Wi-Fi permission", e);
        }
        return null;
    }
}
