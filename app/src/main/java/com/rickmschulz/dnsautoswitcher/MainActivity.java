package com.rickmschulz.dnsautoswitcher;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.PackageInfoCompat;

import com.google.android.material.textfield.TextInputEditText;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_NOTIFICATIONS = 101;
    private static final int REQUEST_WIFI = 102;
    private static final int REQUEST_BACKGROUND_LOCATION = 103;

    private TextInputEditText inputSSID;
    private TextInputEditText inputDNS;
    private Button btnAction;
    private TextView textStatus;
    private SharedPreferences prefs;

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
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQUEST_WIFI);
            return;
        }

        // From Android 10 the SSID is only readable in the background with background location.
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

    private boolean hasWifiPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
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
        btnAction.setBackgroundColor(Color.parseColor(active ? "#D32F2F" : "#1976D2"));
        // Without the ADB permission nothing the service does can work, so do not pretend it can.
        btnAction.setEnabled(canWrite);
        inputSSID.setEnabled(!active);
        inputDNS.setEnabled(!active);

        if (!canWrite) {
            textStatus.setText(R.string.status_missing_permission);
            textStatus.setTextColor(Color.parseColor("#D32F2F"));
        } else if (!canReadSsid) {
            textStatus.setText(R.string.status_missing_wifi_permission);
            textStatus.setTextColor(Color.parseColor("#D32F2F"));
        } else {
            textStatus.setText(active ? R.string.status_running : R.string.status_stopped);
            textStatus.setTextColor(Color.parseColor(active ? "#1976D2" : "#666666"));
        }
    }
}
