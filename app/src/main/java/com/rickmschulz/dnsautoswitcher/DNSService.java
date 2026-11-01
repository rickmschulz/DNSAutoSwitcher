package com.rickmschulz.dnsautoswitcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import java.util.Locale;

/**
 * Keeps Private DNS in the state that matches the current Wi-Fi network.
 *
 * Connected to the configured home SSID the mode is switched to "off" so the local resolver is
 * used; anywhere else it is switched back to "hostname" with the configured provider.
 *
 * The decisions themselves live in {@link SwitchPolicy}, the work of applying them in
 * {@link Reconciler}, so the periodic watchdog can do the same job when this service is not alive.
 */
public class DNSService extends Service {

    private static final String TAG = Reconciler.TAG;

    static final String PREFS = Reconciler.PREFS;
    static final String KEY_HOME_SSID = Reconciler.KEY_HOME_SSID;
    static final String KEY_PRIVATE_DNS = Reconciler.KEY_PRIVATE_DNS;
    static final String KEY_ENABLED = Reconciler.KEY_ENABLED;
    static final String KEY_ALIVE = Reconciler.KEY_ALIVE;
    static final String KEY_LAST_APPLIED = Reconciler.KEY_LAST_APPLIED;
    static final String KEY_LAST_SSID = Reconciler.KEY_LAST_SSID;
    static final String KEY_LAST_STATE = Reconciler.KEY_LAST_STATE;
    static final String KEY_LAST_CHECK = Reconciler.KEY_LAST_CHECK;

    static final String ACTION_STOP = "com.rickmschulz.dnsautoswitcher.action.STOP";
    /** Re-read the network from scratch, e.g. after a permission that was missing is granted. */
    static final String ACTION_REFRESH = "com.rickmschulz.dnsautoswitcher.action.REFRESH";
    /** Re-apply the notification preference without disturbing the monitoring. */
    static final String ACTION_APPLY_NOTIFICATION =
            "com.rickmschulz.dnsautoswitcher.action.APPLY_NOTIFICATION";

    private static final String CHANNEL_ID = "dns_autoswitcher";
    private static final int NOTIFICATION_ID = 1;

    private static final String MODE_HOSTNAME = Reconciler.MODE_HOSTNAME;

    /** Network callbacks arrive in bursts; wait until the connection has settled. */
    private static final long SETTLE_MS = 900L;

    /**
     * When a decision cannot be made the network details are usually just late: right after
     * connecting, Android reports the Wi-Fi name as unreadable, and no further callback arrives
     * once it becomes readable. Without these retries the switch waits for the next event, which
     * on a reconnection that already happened may be minutes or hours away.
     */
    private static final long[] UNKNOWN_RETRY_MS = {1_500L, 4_000L, 8_000L, 15_000L};

    /**
     * One confirming look after every decision. A connection reports several callbacks and the
     * first evaluation can land before the Wi-Fi network exists at all, which yields "away" and,
     * with no further callback, would stay wrong until the next event.
     */
    private static final long VERIFY_MS = 8_000L;

    /**
     * A cheap periodic re-check while the service lives. The watchdog job covers being killed
     * outright; this covers callbacks that never arrive.
     */
    private static final long SAFETY_NET_MS = 120_000L;

    /**
     * A service can be killed without onDestroy running, which would otherwise leave "alive" set
     * forever. The heartbeat is written on every evaluation, so an old one means it is not running.
     */
    static final long STALE_MS = 10 * 60 * 1000L;

    private ConnectivityManager connectivityManager;
    private SharedPreferences prefs;
    private HandlerThread workerThread;
    private Handler worker;
    private ConnectivityManager.NetworkCallback networkCallback;
    private ConnectivityManager.NetworkCallback defaultCallback;
    private Runnable reconcileTask;
    /** How many of {@link #UNKNOWN_RETRY_MS} are still available for the current evaluation. */
    private int retriesLeft;
    /** Whether the current evaluation is the confirming look. */
    private boolean verifying;
    /** Set when no network subscription could be established at all. */
    private boolean monitoringFailed;
    /** Which Wi-Fi permissions the callbacks were registered with. */
    private String registeredPermissions = "";
    /** Whether this instance is currently in the foreground with a notification. */
    private boolean foreground;

    private final Runnable safetyNet = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "safety check");
            if (worker != null) {
                worker.postDelayed(this, SAFETY_NET_MS);
                worker.post(DNSService.this::refreshRegistration);
            }
            scheduleReconcile();
        }
    };

    static boolean hasPermission(android.content.Context context) {
        return Reconciler.hasPermission(context);
    }

    static boolean canReadWifiName(android.content.Context context) {
        return Reconciler.canReadWifiName(context);
    }

    static boolean showsNotification(android.content.Context context) {
        return Reconciler.prefs(context).getBoolean(Reconciler.KEY_SHOW_NOTIFICATION, false);
    }

    /**
     * Start monitoring in whichever shape the user asked for. A foreground service is what the
     * system protects; a plain service is what leaves no notification, at the cost of being stopped
     * sooner - which the periodic watchdog then covers.
     */
    static void start(android.content.Context context) {
        Intent intent = new Intent(context, DNSService.class);
        if (showsNotification(context)) {
            try {
                context.startForegroundService(intent);
                return;
            } catch (RuntimeException e) {
                Log.e(TAG, "The system refused a foreground service start", e);
                return;
            }
        }
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            // Background starts are limited; the persisted watchdog still applies the setting.
            Log.w(TAG, "The system refused a background service start", e);
        }
    }

    /** Apply the notification preference to a running instance without restarting it. */
    static void applyNotificationSetting(android.content.Context context) {
        try {
            context.startService(new Intent(context, DNSService.class)
                    .setAction(ACTION_APPLY_NOTIFICATION));
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not apply the notification setting", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "onCreate");
        prefs = Reconciler.prefs(this);
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        // The notification is opt-in. Without it the service runs as an ordinary started service,
        // and the periodic job is what keeps the setting right if the system stops it.
        updateForegroundMode();
        markAlive(true);

        workerThread = new HandlerThread("dns-switch");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        reconcileTask = this::reconcile;

        registerCallbacks();

        // If an OEM power manager kills this service, the watchdog is what still applies the
        // right setting, so it is scheduled from here and kept independently of this process.
        WatchdogJobService.schedule(this);

        // Apply the right state straight away instead of waiting for the next network change.
        worker.post(reconcileTask);
        worker.postDelayed(safetyNet, SAFETY_NET_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand action=" + (intent == null ? "null" : intent.getAction()));
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply();
            WatchdogJobService.cancel(this);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_APPLY_NOTIFICATION.equals(intent.getAction())) {
            updateForegroundMode();
        }

        if (intent != null && ACTION_REFRESH.equals(intent.getAction()) && worker != null) {
            retriesLeft = UNKNOWN_RETRY_MS.length;
            verifying = false;
            worker.post(this::refreshRegistration);
        }

        // A restart (from the boot receiver, an update, or the watchdog) must re-evaluate instead
        // of waiting for the next network event.
        if (worker != null) {
            worker.post(reconcileTask);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        if (connectivityManager != null) {
            unregister(networkCallback);
            unregister(defaultCallback);
        }

        if (worker != null) {
            if (reconcileTask != null) {
                worker.removeCallbacks(reconcileTask);
            }
            worker.removeCallbacks(safetyNet);
        }

        // Never leave the device with Private DNS disabled: if we were the one that turned it off,
        // put the configured provider back before going away.
        Reconciler.restoreOnStop(this);

        markAlive(false);
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);

        if (!prefs.getBoolean(KEY_ENABLED, false)) {
            WatchdogJobService.cancel(this);
        }

        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private ConnectivityManager.NetworkCallback locationCallback() {
        return new ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            @Override
            public void onAvailable(Network network) {
                scheduleReconcile();
            }

            @Override
            public void onLost(Network network) {
                scheduleReconcile();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                scheduleReconcile();
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                scheduleReconcile();
            }
        };
    }

    private ConnectivityManager.NetworkCallback plainCallback() {
        return new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                scheduleReconcile();
            }

            @Override
            public void onLost(Network network) {
                scheduleReconcile();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                scheduleReconcile();
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                scheduleReconcile();
            }
        };
    }

    private boolean registerWifiCallback(NetworkRequest request) {
        try {
            // Delivering on the worker keeps the binder calls to Settings off the main thread.
            connectivityManager.registerNetworkCallback(request, networkCallback, worker);
            Log.i(TAG, "Wi-Fi network callback registered");
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not register the Wi-Fi network callback", e);
            return false;
        }
    }

    private void registerCallbacks() {
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        // FLAG_INCLUDE_LOCATION_INFO is what makes the SSID readable from the callback's
        // NetworkCapabilities on Android 12 and newer. Without it getSSID() reports
        // "<unknown ssid>" even with every permission granted, and the switch never happens.
        // The int constructor only exists from API 31, and a few builds refuse the flagged
        // subscription outright, so fall back to the plain one instead of monitoring nothing.
        networkCallback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? locationCallback()
                : plainCallback();

        registeredPermissions = Reconciler.wifiPermissionFingerprint(this);
        Log.i(TAG, "registering with wifiPermissions=" + registeredPermissions);

        if (!registerWifiCallback(request)) {
            Log.w(TAG, "Retrying the Wi-Fi registration without the location flag");
            networkCallback = plainCallback();
            if (!registerWifiCallback(request)) {
                monitoringFailed = true;
                Log.e(TAG, "No network callback could be registered; relying on the periodic check");
            }
        }

        // A second subscription with no transport filter: it fires for handovers between mobile
        // data and Wi-Fi, so a change is seen even if the Wi-Fi registration is the one that broke.
        defaultCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                scheduleReconcile();
            }

            @Override
            public void onLost(Network network) {
                scheduleReconcile();
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(defaultCallback, worker);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not register the default network callback", e);
        }
    }

    /**
     * A callback registered before the Wi-Fi permission was granted keeps reporting a redacted
     * name: the redaction is decided when the network is described to the callback, not per read.
     * Granting the permission therefore does nothing until the subscription is made again, which
     * is why the app used to need a manual restart.
     */
    private void refreshRegistration() {
        if (monitoringFailed) {
            return;
        }
        String permissions = Reconciler.wifiPermissionFingerprint(this);
        if (permissions.equals(registeredPermissions)) {
            return;
        }

        Log.i(TAG, "Wi-Fi permissions changed " + registeredPermissions + " -> " + permissions
                + ", re-registering callbacks");
        unregister(networkCallback);
        unregister(defaultCallback);
        registerCallbacks();
    }

    private void unregister(@Nullable ConnectivityManager.NetworkCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(callback);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered.
        }
    }

    private void scheduleReconcile() {
        Log.d(TAG, "network event");
        retriesLeft = UNKNOWN_RETRY_MS.length;
        verifying = false;
        worker.removeCallbacks(reconcileTask);
        worker.postDelayed(reconcileTask, SETTLE_MS);
    }

    private void reconcile() {
        boolean confirming = verifying;
        verifying = false;

        SwitchPolicy.State state = Reconciler.evaluate(this);
        updateNotification();

        if (state != null) {
            retriesLeft = 0;
            if (!confirming) {
                // Look once more shortly, in case this evaluation ran before the network existed.
                verifying = true;
                Log.d(TAG, "confirming the decision in " + VERIFY_MS + " ms");
                worker.removeCallbacks(reconcileTask);
                worker.postDelayed(reconcileTask, VERIFY_MS);
            }
            return;
        }

        // Not enough information yet. Try again shortly instead of waiting for the next network
        // event, which for a connection that has already settled may never come.
        if (retriesLeft > 0) {
            long delay = UNKNOWN_RETRY_MS[UNKNOWN_RETRY_MS.length - retriesLeft];
            retriesLeft--;
            Log.i(TAG, "not enough information, checking again in " + delay + " ms");
            worker.removeCallbacks(reconcileTask);
            worker.postDelayed(reconcileTask, delay);
        } else {
            Log.w(TAG, "giving up on this evaluation; waiting for the next network event");
        }
    }

    private void markAlive(boolean alive) {
        prefs.edit().putBoolean(KEY_ALIVE, alive).apply();
    }

    /** Turn the persistent notification on or off to match the preference, live. */
    private void updateForegroundMode() {
        boolean wanted = showsNotification(this);
        if (wanted == foreground) {
            if (wanted) {
                updateNotification();
            }
            return;
        }

        if (wanted) {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, buildNotification(statusText()));
            foreground = true;
            Log.i(TAG, "notification on");
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            foreground = false;
            Log.i(TAG, "notification off");
        }
    }

    private void updateNotification() {
        if (!foreground) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(statusText()));
        }
    }

    private String statusText() {
        if (!Reconciler.hasPermission(this)) {
            return getString(R.string.notification_missing_permission);
        }
        if (monitoringFailed) {
            return getString(R.string.notification_monitoring_failed);
        }
        if (SwitchPolicy.State.UNKNOWN.name().toLowerCase(Locale.ROOT)
                .equals(prefs.getString(KEY_LAST_STATE, ""))) {
            return getString(R.string.notification_unreadable_ssid);
        }
        String configured = prefs.getString(KEY_HOME_SSID, "");
        String seen = prefs.getString(KEY_LAST_SSID, "");

        if (MODE_HOSTNAME.equals(Reconciler.currentMode(this))) {
            return configured.isEmpty()
                    ? getString(R.string.notification_remote_generic)
                    : getString(R.string.notification_remote, configured);
        }
        return seen.isEmpty()
                ? getString(R.string.notification_local_generic)
                : getString(R.string.notification_local, seen);
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                // IMPORTANCE_MIN is hidden entirely by several OEMs, so the service would look
                // dead. LOW is still silent but visible.
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, DNSService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openPending)
                .addAction(0, getString(R.string.notification_action_stop), stopPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                // Without this the notification is held back for ~10s after startForeground().
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }
}
