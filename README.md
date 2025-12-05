# DNS Auto Switcher

A lightweight Android application that automatically toggles "Private DNS" settings based on your Wi-Fi connection.

## Why this exists

Let's consider a scenario where you have [Pi-hole](https://github.com/pi-hole/pi-hole) set up in your home, and you want to use it instead of your Private DNS provider while connected to your local network. However, at the same time, you want to enable your Private DNS when outside your home network. Well, this is what this app is for, it automates the switch between them.

Since Android does not natively allow you to disable "Private DNS" for specific Wi-Fi networks while keeping it enabled for mobile data, this app solves that problem by running a background service that detects your connection state.

- **At Home:** Disables Private DNS (allowing you to use a local Pi-hole).
- **Away:** Enables Private DNS (using NextDNS, AdGuard) for security on public networks.

## Setup & Installation

### 1. Install the App

Build the APK using [Android Studio](https://developer.android.com/studio) or download the [release](https://github.com/rickmschulz/DNSAutoSwitcher/releases/) and install it on your device.

### 2. Grant Permissions (Crucial)

Because changing system settings is a secure action, you must grant the app permission via [ADB](https://developer.android.com/tools/adb) **once** after installation.

Connect your phone to your PC and run:

```
adb shell pm grant com.rickmschulz.dnsautoswitcher android.permission.WRITE_SECURE_SETTINGS
```

> [!TIP]
> If you need help with ADB, this [page](https://developer.android.com/tools/adb) can help you.

### 3. Configure

1. Open the app.
2. Enter your **Home Wi-Fi SSID** (e.g., `MyHomeWiFi`).
3. Enter your **Private DNS Hostname** (e.g., `12345.dns.nextdns.io` or `dns.adguard-dns.com`).
4. Click **Save & Start Service**.

## Architecture

- **DNSService.java:** A foreground service that listens for network changes using `ConnectivityManager.NetworkCallback`.
- **BootReceiver.java:** Automatically restarts the monitoring service when the phone reboots.
- **Permissions:** Uses `WRITE_SECURE_SETTINGS` to modify `Settings.Global.private_dns_mode`.

## Note

This app targets Android 9.0 (Pie) and higher, as the Private DNS feature was introduced in API 28.

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**. This ensures that the software remains free and open source for everyone. You are free to use, modify, and distribute this software in compliance with the license terms.
