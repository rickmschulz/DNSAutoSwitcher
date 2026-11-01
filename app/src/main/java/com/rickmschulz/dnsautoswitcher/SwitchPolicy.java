package com.rickmschulz.dnsautoswitcher;

import java.util.List;

/**
 * Every decision the service makes, as pure functions: no Android types, no settings writes, no
 * prefs. The whole state machine can therefore be unit tested on the host, which is where the
 * odd cases (unreadable SSID, edited hostname, stopping mid-session) actually broke before.
 */
final class SwitchPolicy {

    enum State {
        /** On the configured network, whatever it is (home, work, a hotspot): use the local resolver. */
        LOCAL,
        /** No Wi-Fi, or a different network: use the configured provider. */
        REMOTE,
        /** Not enough information: the caller must leave the current setting untouched. */
        UNKNOWN
    }

    static final String MODE_OFF = "off";
    static final String MODE_HOSTNAME = "hostname";

    private static final String UNKNOWN_SSID = "<unknown ssid>";

    private SwitchPolicy() {
    }

    /**
     * @param configuredSsids every network that should be treated as local, not just one: a phone
     *                        may be at home, at work or on a hotspot, and each of those can want
     *                        the local resolver.
     */
    static State decide(List<String> configuredSsids, String providerHostname,
                        boolean wifiConnected, String currentSsid) {
        if (isEmpty(providerHostname)) {
            // Nothing to enable, so there is no state to be in.
            return State.UNKNOWN;
        }
        if (!wifiConnected) {
            return State.REMOTE;
        }
        if (isEmptyList(configuredSsids)) {
            return State.REMOTE;
        }
        String ssid = normalizeSsid(currentSsid);
        if (ssid == null) {
            // The framework reports <unknown ssid> until the network details settle. Treating that
            // as "away" is what used to switch filtering on while sitting at home.
            return State.UNKNOWN;
        }
        return isConfigured(configuredSsids, ssid) ? State.LOCAL : State.REMOTE;
    }

    /** Whether the given network is one of the configured ones. Blank and null entries are ignored. */
    static boolean isConfigured(List<String> configuredSsids, String ssid) {
        if (isEmptyList(configuredSsids) || ssid == null) {
            return false;
        }
        String wanted = ssid.trim();
        if (wanted.isEmpty()) {
            return false;
        }
        for (String candidate : configuredSsids) {
            if (candidate != null && wanted.equals(candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    /** @return the Private DNS mode to store, or null when nothing should be written. */
    static String desiredMode(State state) {
        switch (state) {
            case LOCAL:
                return MODE_OFF;
            case REMOTE:
                return MODE_HOSTNAME;
            default:
                return null;
        }
    }

    /** Strips the quotes the framework adds and rejects the placeholder it returns when unreadable. */
    static String normalizeSsid(String raw) {
        if (raw == null) {
            return null;
        }
        String ssid = raw.trim();
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        if (ssid.isEmpty() || UNKNOWN_SSID.equals(ssid)) {
            return null;
        }
        return ssid;
    }

    /**
     * The provider has to be written whenever it differs, not only when the mode changes:
     * editing the hostname while already in "hostname" mode used to leave the old provider in
     * place until the next home/away transition.
     */
    static boolean providerNeedsWrite(String currentSpecifier, String providerHostname) {
        return !isEmpty(providerHostname) && !providerHostname.equals(currentSpecifier);
    }

    static boolean modeNeedsWrite(String currentMode, String desiredMode) {
        return desiredMode != null && !desiredMode.equals(currentMode);
    }

    /** Stopping must undo the change this service made, and only that change. */
    static boolean shouldRestoreOnStop(String lastAppliedMode, String providerHostname) {
        return MODE_OFF.equals(lastAppliedMode) && !isEmpty(providerHostname);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static boolean isEmptyList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return true;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
