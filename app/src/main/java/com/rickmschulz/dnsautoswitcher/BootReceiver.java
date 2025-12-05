package com.rickmschulz.dnsautoswitcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Start the "Brain" service as soon as the phone boots up
            Intent serviceIntent = new Intent(context, DNSService.class);
            context.startForegroundService(serviceIntent);
        }
    }
}