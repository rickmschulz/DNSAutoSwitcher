package com.rickmschulz.dnsautoswitcher;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Applies the right Private DNS state every so often, whether or not the foreground service
 * survived.
 *
 * The foreground service is the fast path, but an OEM power manager can kill it and, in doing so,
 * stop network callbacks from ever arriving again - the app then looks alive while switching
 * nothing. A persisted periodic job is the one mechanism those managers still honour, so it does
 * the work itself rather than depending on the service.
 */
public class WatchdogJobService extends JobService {

    private static final String TAG = Reconciler.TAG;
    private static final int JOB_ID = 4711;
    /** The shortest period JobScheduler allows. Doze may stretch it further. */
    private static final long INTERVAL_MS = 15 * 60 * 1000L;

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, WatchdogJobService.class))
                .setPeriodic(INTERVAL_MS)
                // Survives reboots without needing the boot receiver to have run.
                .setPersisted(true)
                // No network or charging constraint: this must run on the state of the device,
                // and waiting for conditions would delay the switch further.
                .setRequiresDeviceIdle(false)
                .build();
        try {
            int result = scheduler.schedule(job);
            Log.i(TAG, "watchdog schedule result=" + result);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not schedule the watchdog", e);
        }
    }

    static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) {
            scheduler.cancel(JOB_ID);
            Log.i(TAG, "watchdog cancelled");
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "watchdog tick");

        SharedPreferences prefs = Reconciler.prefs(this);
        if (!prefs.getBoolean(Reconciler.KEY_ENABLED, false)) {
            cancel(this);
            return false;
        }

        // Off the main thread: this writes to Settings through the system service.
        new Thread(() -> {
            try {
                Reconciler.evaluate(getApplicationContext());
            } catch (RuntimeException e) {
                Log.e(TAG, "watchdog evaluation failed", e);
            }
            revivingIfStale();
            jobFinished(params, false);
        }, "watchdog").start();

        return true;
    }

    /**
     * If the service has not evaluated anything for a while it is probably dead, so ask for it
     * back: that restores instant switching instead of the 15 minute fallback. The setting has
     * already been applied by now, so a refusal costs nothing.
     */
    private void revivingIfStale() {
        long lastCheck = Reconciler.prefs(this).getLong(Reconciler.KEY_LAST_CHECK, 0L);
        if (System.currentTimeMillis() - lastCheck < DNSService.STALE_MS) {
            return;
        }
        Log.i(TAG, "watchdog asked for the service to come back");
        DNSService.start(this);
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
