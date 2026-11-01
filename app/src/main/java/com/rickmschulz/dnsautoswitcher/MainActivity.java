package com.rickmschulz.dnsautoswitcher;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.core.view.ViewCompat;

import com.google.android.material.textfield.TextInputEditText;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_NOTIFICATIONS = 101;
    private static final int REQUEST_WIFI = 102;
    private static final int REQUEST_BACKGROUND_LOCATION = 103;

    /**
     * The service writes what it sees to preferences, and the permission is granted from outside
     * the app, so the screen has to re-read them on its own while it is visible.
     */
    private static final long UI_TICK_MS = 2000L;

    private TextInputEditText inputSSID;
    private TextInputEditText inputDNS;
    private Button btnAction;
    private TextView textStatus;
    private TextView textObserved;
    private TextView textHelp;
    private SharedPreferences prefs;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiTick = new Runnable() {
        @Override
        public void run() {
            updateUI();
            uiHandler.postDelayed(this, UI_TICK_MS);
        }
    };

    /** Asking for background location repeatedly on every resume is pure nagging. */
    private boolean backgroundLocationAsked;
    /** Only asked for when the SSID really turns out to be unreadable. */
    private boolean locationFallbackAsked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SystemBars.pad(findViewById(R.id.main_root));

        inputSSID = findViewById(R.id.input_ssid);
        inputDNS = findViewById(R.id.input_dns_id);
        btnAction = findViewById(R.id.btn_save);
        findViewById(R.id.btn_settings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        textStatus = findViewById(R.id.text_status);
        textObserved = findViewById(R.id.text_observed);
        textHelp = findViewById(R.id.text_help);

        TextView textVersion = findViewById(R.id.text_version);
        textVersion.setText(getString(R.string.version_label, getVersionName(), getVersionCodeLong()));

        prefs = getSharedPreferences(DNSService.PREFS, MODE_PRIVATE);
        inputSSID.setText(prefs.getString(DNSService.KEY_HOME_SSID, ""));
        inputDNS.setText(prefs.getString(DNSService.KEY_PRIVATE_DNS, ""));

        btnAction.setOnClickListener(v -> {
            if (isServiceActive() && !isServiceStale()) {
                stopMonitoring();
            } else if (isServiceSwitchedOn()) {
                // Enabled but not running: a killed service leaves no trace, so offer a restart
                // rather than a Stop button that would appear to do nothing.
                restartMonitoring();
            } else {
                saveAndStartService();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestRequiredPermissions();
        updateUI();

        // Covers a permission granted from the system settings while the app was in the
        // background: the service notices the change on its own timer, this just makes it
        // immediate when the screen comes back.
        if (hasWifiPermission()) {
            refreshService();
        }

        // Re-read while visible: the service decides on a worker thread and writes preferences,
        // so the screen would otherwise keep showing what was true when it was opened - which
        // made "Wi-Fi seen" look stuck on the network the phone had already left.
        uiHandler.removeCallbacks(uiTick);
        uiHandler.postDelayed(uiTick, UI_TICK_MS);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(uiTick);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Granting the Wi-Fi permission does not un-redact a callback that was registered
        // without it, so tell the service to subscribe again instead of making the user restart it.
        if (requestCode == REQUEST_WIFI && hasWifiPermission()) {
            refreshService();
        }
        updateUI();
    }

    private void refreshService() {
        if (!isServiceSwitchedOn()) {
            return;
        }
        Intent intent = new Intent(this, DNSService.class).setAction(DNSService.ACTION_REFRESH);
        try {
            startService(intent);
        } catch (RuntimeException ignored) {
            // The service is not running; nothing to refresh.
        }
    }

    /**
     * Read from the installed package rather than BuildConfig, so the label always matches the
     * APK that is actually running - which is the whole point of showing it.
     */
    private String getVersionName() {
        PackageInfo info = installedPackage();
        return info == null ? "?" : String.valueOf(info.versionName);
    }

    private long getVersionCodeLong() {
        PackageInfo info = installedPackage();
        return info == null ? 0L : PackageInfoCompat.getLongVersionCode(info);
    }

    @SuppressWarnings("deprecation") // getPackageInfo(String, int) is the only form below API 33
    private PackageInfo installedPackage() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }

        if (!hasWifiPermission()) {
            ActivityCompat.requestPermissions(this, wifiPermissions(), REQUEST_WIFI);
            return;
        }

        // Android 13+ should be satisfied by NEARBY_WIFI_DEVICES, but several OEM builds still
        // redact the SSID without location. Ask for it only when the service reports that it
        // cannot read the name, and only once, instead of putting a location prompt in everyone's
        // way on first launch.
        if (!locationFallbackAsked
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && "unknown".equals(prefs.getString(DNSService.KEY_LAST_STATE, ""))) {
            locationFallbackAsked = true;
            Toast.makeText(this, R.string.toast_location_fallback, Toast.LENGTH_LONG).show();
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_WIFI);
            return;
        }

        // Up to Android 12 the SSID is only readable in the background with background location.
        if (!backgroundLocationAsked
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            backgroundLocationAsked = true;
            Toast.makeText(this, R.string.toast_background_location, Toast.LENGTH_LONG).show();
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    REQUEST_BACKGROUND_LOCATION);
        }
    }

    /** Android 13 replaced the location gate for SSID reads with a normal nearby-devices permission. */
    private String[] wifiPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.NEARBY_WIFI_DEVICES};
        }
        return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
    }

    /**
     * Android 13+ normally gates the SSID behind NEARBY_WIFI_DEVICES, but some OEM builds still
     * redact it without location, so either one counts as "we have a way in".
     */
    private boolean hasWifiPermission() {
        return DNSService.canReadWifiName(this);
    }

    private boolean isServiceSwitchedOn() {
        return prefs.getBoolean(DNSService.KEY_ENABLED, false);
    }

    private boolean isServiceActive() {
        return isServiceSwitchedOn() && prefs.getBoolean(DNSService.KEY_ALIVE, false);
    }

    /**
     * A service killed by the system never runs onDestroy, so "alive" stays set and the screen
     * would happily claim it is running. The heartbeat written on every evaluation is the real
     * signal: if it is old, nothing has been evaluated for a while.
     */
    private boolean isServiceStale() {
        if (!isServiceSwitchedOn()) {
            return false;
        }
        long lastCheck = prefs.getLong(DNSService.KEY_LAST_CHECK, 0L);
        return lastCheck == 0L || System.currentTimeMillis() - lastCheck > DNSService.STALE_MS;
    }

    private void restartMonitoring() {
        prefs.edit().putBoolean(DNSService.KEY_ENABLED, true).apply();
        DNSService.start(this);

        updateUI();
        btnAction.postDelayed(this::updateUI, 500);
        Toast.makeText(this, R.string.toast_restarted, Toast.LENGTH_SHORT).show();
    }

    private void saveAndStartService() {
        if (!DNSService.hasPermission(this)) {
            updateUI();
            return;
        }

        String ssid = inputSSID.getText() == null ? "" : inputSSID.getText().toString().trim();
        String dnsId = inputDNS.getText() == null ? "" : inputDNS.getText().toString().trim();

        if (ssid.isEmpty() || dnsId.isEmpty()) {
            Toast.makeText(this, R.string.toast_fill_both_fields, Toast.LENGTH_SHORT).show();
            return;
        }

        prefs.edit()
                .putString(DNSService.KEY_HOME_SSID, ssid)
                .putString(DNSService.KEY_PRIVATE_DNS, dnsId)
                .putBoolean(DNSService.KEY_ENABLED, true)
                .apply();

        DNSService.start(this);

        updateUI();
        // The service reports itself alive from its own onCreate, which runs after this handler.
        btnAction.postDelayed(this::updateUI, 500);
        Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show();
    }

    private void stopMonitoring() {
        prefs.edit().putBoolean(DNSService.KEY_ENABLED, false).apply();
        stopService(new Intent(this, DNSService.class));

        updateUI();
        btnAction.postDelayed(this::updateUI, 500);
        Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show();
    }

    private void updateUI() {
        boolean active = isServiceActive();
        boolean canWrite = DNSService.hasPermission(this);
        boolean canReadSsid = hasWifiPermission();

        boolean stale = isServiceStale();
        boolean switchedOn = isServiceSwitchedOn();

        btnAction.setText(active && !stale ? R.string.action_stop
                : switchedOn ? R.string.action_restart
                : R.string.action_start);
        ViewCompat.setBackgroundTintList(btnAction, ColorStateList.valueOf(
                ContextCompat.getColor(this, active ? R.color.state_active : R.color.state_idle)));
        // Without the ADB permission nothing the service does can work, so do not pretend it can.
        btnAction.setEnabled(canWrite);
        inputSSID.setEnabled(!active);
        inputDNS.setEnabled(!active);

        // The instructions exist only to get the permission granted, so they disappear the moment
        // it is - and the status line never repeats what that block already says.
        textHelp.setVisibility(canWrite ? View.GONE : View.VISIBLE);

        if (!canWrite) {
            textStatus.setText(R.string.status_missing_permission);
            textStatus.setTextColor(ContextCompat.getColor(this, R.color.state_active));
        } else if (!canReadSsid) {
            textStatus.setText(R.string.status_missing_wifi_permission);
            textStatus.setTextColor(ContextCompat.getColor(this, R.color.state_active));
        } else if (stale) {
            textStatus.setText(R.string.status_stale);
            textStatus.setTextColor(ContextCompat.getColor(this, R.color.state_active));
        } else {
            textStatus.setText(active ? R.string.status_running : R.string.status_stopped);
            textStatus.setTextColor(ContextCompat.getColor(this,
                    active ? R.color.state_idle : R.color.state_muted));
        }

        showWhatTheServiceSees(active);
    }

    /**
     * The service can decide to do nothing - for instance when Android will not reveal the Wi-Fi
     * name - and "nothing happened" is impossible to tell apart from "the service is dead". This
     * line says which network was seen, so that case is visible instead of silent.
     */
    private void showWhatTheServiceSees(boolean active) {
        textObserved.setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active) {
            return;
        }

        String ssid = prefs.getString(DNSService.KEY_LAST_SSID, "");
        String state = prefs.getString(DNSService.KEY_LAST_STATE, "");

        if ("unknown".equals(state)) {
            textObserved.setText(R.string.observed_unreadable);
            textObserved.setTextColor(ContextCompat.getColor(this, R.color.state_active));
        } else if (ssid.isEmpty()) {
            textObserved.setText(R.string.observed_none);
            textObserved.setTextColor(ContextCompat.getColor(this, R.color.state_muted));
        } else {
            textObserved.setText(getString(R.string.observed_ssid, ssid));
            textObserved.setTextColor(ContextCompat.getColor(this, R.color.state_muted));
        }
    }
}
