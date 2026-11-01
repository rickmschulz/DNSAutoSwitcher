# DNS Auto Switcher

A lightweight Android application that automatically toggles "Private DNS" settings based on your Wi-Fi connection.

## Why this exists

Let's consider a scenario where you have [Pi-hole](https://github.com/pi-hole/pi-hole) set up in your home, and you want to use it instead of your Private DNS provider while connected to your local network. However, at the same time, you want to enable your Private DNS when outside your home network. Well, this is what this app is for, it automates the switch between them.

Since Android does not natively allow you to disable "Private DNS" for specific Wi-Fi networks while keeping it enabled for mobile data, this app solves that problem by running a background service that detects your connection state.

- **On your configured networks:** Disables Private DNS, so your local resolver answers.
- **On any other network:** Enables Private DNS (NextDNS, AdGuard and others), so filtering still applies away from it.

You can configure more than one network: home, an office, a studio, a regular hotspot. Any of them counts as local. They do not have to belong to the same place, and the app only compares Wi-Fi names, so two networks with the same name are indistinguishable to it.

## Setup & Installation

### 1. Install the App

Build the APK using [Android Studio](https://developer.android.com/studio) or download the release and install it on your device.

### 2. Grant Permissions (Crucial)

Because changing system settings is a secure action, you must grant the app permission via [ADB](https://developer.android.com/tools/adb) **once** after installation.

Connect your phone to your PC and run:

```
adb shell pm grant com.rickmschulz.dnsautoswitcher android.permission.WRITE_SECURE_SETTINGS
```

> [!TIP]
> If you need help with ADB, this [page](https://developer.android.com/tools/adb) can help you.

> [!IMPORTANT]
> **Xiaomi, Redmi and POCO (MIUI / HyperOS):** the command above is rejected unless you enable a second developer setting. Without it `pm grant` fails with `java.lang.SecurityException: grantRuntimePermission: Neither user 2000 nor current process has android.permission.GRANT_RUNTIME_PERMISSIONS`. The ROM has removed that permission from the shell user, so nothing the app can do fixes it.
>
> 1. Enable `Developer options` (tap the MIUI version seven times).
> 2. In `Developer options`, enable **`USB debugging`**.
> 3. Also enable **`USB debugging (Security settings)`**, described as *"Allow granting permissions and simulating input via USB debugging"*. Signing in to a Mi account may be needed to unlock the toggle.
> 4. Reconnect USB, accept the debugging prompt, and run the `pm grant` command again.
>
> Verify with `adb shell dumpsys package com.rickmschulz.dnsautoswitcher | grep WRITE_SECURE_SETTINGS`, which should report `granted=true`.

To ensure the app works reliably in the background, open the app's settings page and:

- Disable `Manage app if unused`.
- Set Battery usage to `Unrestricted`.
- Grant the permission needed to read the Wi-Fi name: on Android 13 and newer that is `Nearby devices`. If the app reports that it cannot read the name, also allow `Location`, which several phone makers still require.
- On Android 12 and older, allow `Location` and choose `Allow all the time` when asked, because the service reads the name while running in the background.

#### Why Does the App Need Location Permission? 

Google requires any app that reads the connected Wi-Fi network's SSID or BSSID to hold a permission. Without it, Android returns `<unknown ssid>` instead of the actual network name, and the app cannot tell the network you configured from any other. Since DNS Auto Switcher identifies it by its SSID, that permission is required for it to detect when you are connected to your Wi-Fi and toggle Private DNS accordingly.

Which permission that is depends on the Android version:

- **Android 13 and newer:** `NEARBY_WIFI_DEVICES`, declared with `neverForLocation`, requested as a normal runtime permission with no "allow all the time" step. Location is requested only if the name still cannot be read, because some manufacturer builds, MIUI and HyperOS among them, keep hiding the name without it.
- **Android 12 and older:** `ACCESS_FINE_LOCATION`, plus `ACCESS_BACKGROUND_LOCATION` because the service reads the SSID while running in the background.

One detail that matters: Android decides whether to hide the Wi-Fi name at the moment the app subscribes to network changes. Granting the permission afterwards changes nothing until the app subscribes again. The app notices the change and subscribes again by itself, within about two seconds when the screen is open and within two minutes otherwise.

#### "Allow all the time" is not optional

Android treats the Wi-Fi name as location data. An app that is not on screen may only read it when Location is set to `Allow all the time`; with `Allow only while using the app` the name comes back unreadable as soon as you leave the app.

The visible symptom is specific and worth recognising: switching **to** Private DNS works, because leaving Wi-Fi needs no name at all, but switching **back off** when you reconnect does nothing until you open the app again. The app detects this, says so on screen, and offers a button straight to its own permission screen.

Android 11 and later do not let an app request that mode with a dialog. It has to be chosen in the app's settings: `App info`, `Permissions`, `Location`, `Allow all the time`. The app asks once after the first start when the mode is missing.

### 3. Configure

1. Open the app.
2. Type a **Wi-Fi name** and press **Add network**. Repeat for every network that should use your local resolver, for example `MyHomeWiFi` and your office SSID. **Use current** fills in the network the phone is on right now.
3. Enter your **Private DNS Hostname** (e.g., `12345.dns.nextdns.io` or `dns.adguard-dns.com`).
4. Click **Save & Start Service**.

The network list can be edited while the app is running. Adding or removing a network takes effect straight away.

The **Settings** screen holds one option: whether the app shows a persistent notification while it monitors. It is off by default. See [Reliability](#reliability) for what that choice costs.

## Reliability

Android stops background services, and some phone makers are far more aggressive about it than others. The app uses three things to cope:

- **Network callbacks.** While the service is running it is told about every Wi-Fi change and switches immediately.
- **A periodic check.** A persisted job runs at least every 15 minutes. It applies the correct setting itself, so the switch still happens even if the system killed the service. It also asks for the service back. Android does not allow that job to run more often than every 15 minutes, so without the service the switch can lag by up to 15 minutes.
- **The optional notification.** Android protects a service that shows a notification far better than a silent one. The notification is off by default and can be switched on in Settings. Without it the periodic check is doing the work, which is why the lag above is worth knowing about.

If switching is slow rather than wrong, the service is being stopped by the system. On MIUI and HyperOS, set the app to `No restrictions` under Battery saver and enable it under Autostart.

## How to test it

The failure that hurts is the one nobody notices: the switch works while the app is open and silently stops once it is closed. So the release gate is the screen-off case, not the visible one.

Run this before publishing any release, on a real phone, with the app installed over the previous version:

1. **On the configured network, app open.** Private DNS should be off within a second of the app opening.
2. **On the configured network, app closed.** Turn Wi-Fi off, wait, turn it on. Private DNS should go on and back off without the app being opened.
3. **Screen off.** Repeat step 2 with the screen off the whole time. This is the case a background location permission decides.
4. **Another network.** Join a network that is not in the list. Private DNS should be on within a second.
5. **Force stop.** Force stop the app from its settings, then change networks. Nothing has to switch, but the app must say it is not updating rather than claim it is running.
6. **Reboot.** After restarting the phone, the app should be monitoring again without being opened, and the version shown should be the one that was installed.
7. **Update.** Install a new build over the old one. Monitoring should resume without a reboot.

If step 2 or 3 fails while everything else passes, read the status line in the app: it distinguishes a missing permission from the phone hiding the Wi-Fi name, and those have different fixes.

## Architecture

- **SwitchPolicy.java:** The decisions, as pure functions with no Android dependency: is this one of the configured networks, which Private DNS mode does that imply, and does anything need writing. Unit tested on the host.
- **Reconciler.java:** Reads the current network, asks the policy, and writes `Settings.Global.private_dns_mode` and `private_dns_specifier`. Static, so both the service and the periodic job can use it.
- **DNSService.java:** The fast path. Listens for network changes with `ConnectivityManager.NetworkCallback`, debounces the burst that arrives on every connect, and re-evaluates. Runs in the foreground with a notification, or as an ordinary service if the notification is switched off.
- **WatchdogJobService.java:** The slow path. A persisted job that applies the same decision every 15 minutes, so a killed service does not mean a wrong setting.
- **BootReceiver.java:** Starts monitoring after a reboot or an app update, but only if the user had switched it on.
- **MainActivity.java / SettingsActivity.java:** Configuration, live state, and the notification preference.
- **Permissions:** `WRITE_SECURE_SETTINGS`, granted once over ADB, is what allows writing those two global settings.

## Changelog

**v1.2**

All credit for this release goes to [@tanc](https://github.com/tanc) for completely refactoring the core background service for modern Android reliability and building the new multiple-network feature.

* **Added:** any number of Wi-Fi networks can be configured, and all of them count as local. The single network of earlier versions is migrated automatically.
* **Added:** "Use current" adds the network the phone is connected to.
* **Changed:** the notification says how many networks are configured instead of naming a single one.
* **Fixed:** the version now comes from the git tag instead of being edited by hand, and is shown inside the app.
* **Fixed:** the service restarts after an app update, not only after a reboot.
* **Fixed:** an unknown SSID no longer disables Private DNS while at home.
* **Fixed:** editing the Private DNS hostname takes effect immediately.
* **Fixed:** stopping the service restores Private DNS instead of leaving it off.
* **Changed:** fewer permissions on Android 13+, a state line and Stop action in the notification, and a release build that is minified.
* **Changed:** the ADB instructions disappear from the app once the permission is granted.
* **Fixed:** the switch keeps working when the system stops the service, using a periodic job that applies the setting itself.
* **Fixed:** the app notices a newly granted Wi-Fi permission and subscribes again, instead of needing a manual restart.
* **Fixed:** the status line follows the current network instead of showing what was true when the screen was opened.
* **Fixed:** disconnecting from Wi-Fi no longer reports the previous network as still connected.
* **Changed:** the persistent notification is opt-in, controlled from a new Settings screen.
* **Changed:** wording no longer assumes the configured network is a home network.
* **Changed:** both screens keep clear of the status bar on Android 15 and later, where apps are drawn edge to edge.
* **Changed:** the switching rules are covered by unit tests.
* **Docs:** the README explains that MIUI and HyperOS need "USB debugging (Security settings)" enabled before `pm grant` is accepted.
* **Fixed:** a reconnection that happened while the app was in the background is now recognised, instead of waiting for the next network change.
* **Fixed:** the app explains when Location is set to "only while using the app", which hides the Wi-Fi name from a background service, and links to the screen where that is changed.

**v1.1**
* **Fixed:** The app was only partially working as intended. This release fixes the underlying issues and restores full functionality.
* *Note: This version is undergoing active testing over the next few days to ensure long-term stability.*

## Note

This app targets Android 9.0 (Pie) and higher, as the Private DNS feature was introduced in API 28.

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**. This ensures that the software remains free and open source for everyone. You are free to use, modify, and distribute this software in compliance with the license terms.
