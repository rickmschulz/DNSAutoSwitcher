package com.rickmschulz.dnsautoswitcher;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.textfield.TextInputEditText;

public class MainActivity extends AppCompatActivity {

    private TextInputEditText inputSSID;
    private TextInputEditText inputDNS;
    private Button btnAction;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Request Notification Permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        inputSSID = findViewById(R.id.input_ssid);
        inputDNS = findViewById(R.id.input_dns_id);
        btnAction = findViewById(R.id.btn_save);

        prefs = getSharedPreferences("DNSAutoSwitcherPrefs", MODE_PRIVATE);
        inputSSID.setText(prefs.getString("home_ssid", ""));
        inputDNS.setText(prefs.getString("privatedns_id", ""));

        // Perform initial check
        syncServiceState();
        updateUI();

        btnAction.setOnClickListener(v -> {
            if (DNSService.isRunning) {
                stopService();
            } else {
                saveAndStartService();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // "Reality Check": Every time the screen appears, check if the service is actually alive
        syncServiceState();
        updateUI();
    }

    /**
     * Checks if the service is actually running in the Android System
     * and updates the static flag to match reality.
     */
    private void syncServiceState() {
        boolean actuallyRunning = false;
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (DNSService.class.getName().equals(service.service.getClassName())) {
                actuallyRunning = true;
                break;
            }
        }
        DNSService.isRunning = actuallyRunning;
    }

    private void saveAndStartService() {
        String ssid = inputSSID.getText().toString().trim();
        String dnsId = inputDNS.getText().toString().trim();

        if (ssid.isEmpty() || dnsId.isEmpty()) {
            Toast.makeText(this, "Please fill in both fields", Toast.LENGTH_SHORT).show();
            return;
        }

        prefs.edit()
                .putString("home_ssid", ssid)
                .putString("privatedns_id", dnsId)
                .apply();

        Intent serviceIntent = new Intent(this, DNSService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Assume start was successful for immediate UI feedback
        DNSService.isRunning = true;
        updateUI();
        Toast.makeText(this, "Service Started!", Toast.LENGTH_SHORT).show();
    }

    private void stopService() {
        Intent serviceIntent = new Intent(this, DNSService.class);
        stopService(serviceIntent);

        // Update flag immediately
        DNSService.isRunning = false;
        updateUI();
        Toast.makeText(this, "Service Stopped", Toast.LENGTH_SHORT).show();
    }

    private void updateUI() {
        if (DNSService.isRunning) {
            btnAction.setText("STOP SERVICE");
            btnAction.setBackgroundColor(Color.parseColor("#D32F2F")); // Red
            inputSSID.setEnabled(false);
            inputDNS.setEnabled(false);
        } else {
            btnAction.setText("SAVE & START SERVICE");
            btnAction.setBackgroundColor(Color.parseColor("#1976D2")); // Blue
            inputSSID.setEnabled(true);
            inputDNS.setEnabled(true);
        }
    }
}