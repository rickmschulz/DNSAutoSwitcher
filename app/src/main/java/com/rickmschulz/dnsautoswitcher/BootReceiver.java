package com.rickmschulz.dnsautoswitcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean shouldStart = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!shouldStart) {
            return;
        }

        SharedPreferences prefs =
                context.getSharedPreferences(DNSService.PREFS, Context.MODE_PRIVATE);

        // Only come back if the user actually switched the service on. Booting a device where the
        // app was installed but never started must not leave a permanent notification behind.
        if (!prefs.getBoolean(DNSService.KEY_ENABLED, false)) {
            return;
        }

        // Start in whichever shape the user chose: foreground with a notification, or plain.
        DNSService.start(context);
    }
}
