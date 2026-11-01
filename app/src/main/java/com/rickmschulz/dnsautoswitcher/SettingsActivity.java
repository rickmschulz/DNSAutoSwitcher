package com.rickmschulz.dnsautoswitcher;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * The one setting that is not part of the main flow: whether the monitoring service shows a
 * persistent notification.
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        SystemBars.pad(findViewById(R.id.settings_root));

        MaterialSwitch notificationSwitch = findViewById(R.id.switch_notification);
        TextView state = findViewById(R.id.text_notification_state);

        notificationSwitch.setChecked(DNSService.showsNotification(this));
        state.setText(notificationSwitch.isChecked()
                ? R.string.settings_notification_state_on
                : R.string.settings_notification_state_off);

        notificationSwitch.setOnCheckedChangeListener((button, checked) -> {
            Reconciler.prefs(this).edit()
                    .putBoolean(Reconciler.KEY_SHOW_NOTIFICATION, checked)
                    .apply();
            state.setText(checked
                    ? R.string.settings_notification_state_on
                    : R.string.settings_notification_state_off);

            // Android 13+ silently drops a foreground notification when notifications are not
            // allowed, which would look like the setting doing nothing at all.
            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 201);
            }

            // Applies to a running instance immediately: no restart, no lost evaluation.
            DNSService.applyNotificationSetting(this);
        });
    }
}
