package com.rickmschulz.dnsautoswitcher;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.textfield.TextInputEditText;

public class MainActivity extends AppCompatActivity {

    private TextInputEditText inputSSID;
    private TextInputEditText inputDNS;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Views
        inputSSID = findViewById(R.id.input_ssid);
        inputDNS = findViewById(R.id.input_dns_id);
        Button btnSave = findViewById(R.id.btn_save);

        // Load saved settings
        prefs = getSharedPreferences("DNSAutoSwitcherPrefs", MODE_PRIVATE);
        inputSSID.setText(prefs.getString("home_ssid", ""));
        inputDNS.setText(prefs.getString("privatedns_id", ""));

        // Save Button Logic
        btnSave.setOnClickListener(v -> {
            String ssid = inputSSID.getText().toString().trim();
            String dnsId = inputDNS.getText().toString().trim();

            if (ssid.isEmpty() || dnsId.isEmpty()) {
                Toast.makeText(this, "Please fill in both fields", Toast.LENGTH_SHORT).show();
                return;
            }

            // Save to storage
            prefs.edit()
                    .putString("home_ssid", ssid)
                    .putString("privatedns_id", dnsId)
                    .apply();

            // Start the Service
            Intent serviceIntent = new Intent(this, DNSService.class);
            startForegroundService(serviceIntent);

            Toast.makeText(this, "Settings Saved & Service Started!", Toast.LENGTH_SHORT).show();
        });
    }
}