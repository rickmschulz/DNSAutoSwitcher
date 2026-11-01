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
     * The ADB permission is granted from outside the app, so the screen has to notice it on its
     * own. Polling runs only while the permission is missing and the screen is visible.
     */
    private static final long PERMISSION_POLL_MS = 2000L;

    private TextInputEditText inputSSID;
    private TextInputEditText inputDNS;
    private Button btnAction;
    private TextView textStatus;
    private TextView textHelp;
    private SharedPreferences prefs;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable permissionPoll = new Runnable() {
        @Override
        public void run() {
            updateUI();
            if (!DNSService.hasPermission(MainActivity.this)) {
                uiHandler.postDelayed(this, PERMISSION_POLL_MS);
            }
        }
    };

    /** Asking for background location repeatedly on every resume is pure nagging. */
    private boolean backgroundLocationAsked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputSSID = findViewById(R.id.input_ssid);
        inputDNS = findViewById(R.id.input_dns_id);
        btnAction = findViewById(R.id.btn_save);
        textStatus = findViewById(R.id.text_status);
        textHelp = findViewById(R.id.text_help);

        TextView textVersion = findViewById(R.id.text_version);
        textVersion.setText(getString(R.string.version_label, getVersionName(), getVersionCodeLong()));

        prefs = getSharedPreferences(DNSService.PREFS, MODE_PRIVATE);
        inputSSID.setText(prefs.getString(DNSService.KEY_HOME_SSID, ""));
        inputDNS.setText(prefs.getString(DNSService.KEY_PRIVATE_DNS, ""));

        btnAction.setOnClickListener(v -> {
            if (isServiceActive()) {
                stopMonitoring();
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

        uiHandler.removeCallbacks(permissionPoll);
        uiHandler.postDelayed(permissionPoll, PERMISSION_POLL_MS);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(permissionPoll);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updateUI();
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

    private boolean hasWifiPermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.NEARBY_WIFI_DEVICES
                : Manifest.permission.ACCESS_FINE_LOCATION;
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isServiceActive() {
        return prefs.getBoolean(DNSService.KEY_ENABLED, false)
                && prefs.getBoolean(DNSService.KEY_ALIVE, false);
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

        startForegroundService(new Intent(this, DNSService.class));

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

        btnAction.setText(active ? R.string.action_stop : R.string.action_start);
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
        } else {
            textStatus.setText(active ? R.string.status_running : R.string.status_stopped);
            textStatus.setTextColor(ContextCompat.getColor(this,
                    active ? R.color.state_idle : R.color.state_muted));
        }
    }
}
