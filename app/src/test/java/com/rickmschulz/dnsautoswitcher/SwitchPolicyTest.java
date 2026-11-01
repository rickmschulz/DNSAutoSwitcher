package com.rickmschulz.dnsautoswitcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers the paths that were wrong before: an unreadable SSID, a hostname edited while already
 * away, and stopping the service at home.
 */
public class SwitchPolicyTest {

    private static final String HOME = "HomeNet";
    private static final String DNS = "12345.dns.nextdns.io";

    // decide()

    @Test
    public void noWifiMeansRemote() {
        assertEquals(SwitchPolicy.State.REMOTE,
                SwitchPolicy.decide(HOME, DNS, false, null));
    }

    @Test
    public void configuredSsidMeansLocal() {
        assertEquals(SwitchPolicy.State.LOCAL,
                SwitchPolicy.decide(HOME, DNS, true, HOME));
    }

    @Test
    public void quotedHomeSsidStillMatches() {
        assertEquals(SwitchPolicy.State.LOCAL,
                SwitchPolicy.decide(HOME, DNS, true, "\"" + HOME + "\""));
    }

    @Test
    public void otherNetworkMeansRemote() {
        assertEquals(SwitchPolicy.State.REMOTE,
                SwitchPolicy.decide(HOME, DNS, true, "CafeWiFi"));
    }

    @Test
    public void unknownSsidLeavesTheSettingAlone() {
        assertEquals(SwitchPolicy.State.UNKNOWN,
                SwitchPolicy.decide(HOME, DNS, true, "<unknown ssid>"));
        assertNull(SwitchPolicy.desiredMode(SwitchPolicy.State.UNKNOWN));
    }

    @Test
    public void nullSsidLeavesTheSettingAlone() {
        assertEquals(SwitchPolicy.State.UNKNOWN,
                SwitchPolicy.decide(HOME, DNS, true, null));
    }

    @Test
    public void ssidIsComparedTrimmed() {
        assertEquals(SwitchPolicy.State.LOCAL,
                SwitchPolicy.decide(HOME, DNS, true, "  " + HOME + "  "));
    }

    @Test
    public void withoutAProviderNothingIsDecided() {
        assertEquals(SwitchPolicy.State.UNKNOWN,
                SwitchPolicy.decide(HOME, "", true, "CafeWiFi"));
        assertEquals(SwitchPolicy.State.UNKNOWN,
                SwitchPolicy.decide(HOME, null, false, null));
    }

    @Test
    public void withoutAConfiguredSsidEveryNetworkIsRemote() {
        assertEquals(SwitchPolicy.State.REMOTE,
                SwitchPolicy.decide("", DNS, true, "CafeWiFi"));
    }

    // desiredMode()

    @Test
    public void localTurnsPrivateDnsOffAndRemoteTurnsItOn() {
        assertEquals("off", SwitchPolicy.desiredMode(SwitchPolicy.State.LOCAL));
        assertEquals("hostname", SwitchPolicy.desiredMode(SwitchPolicy.State.REMOTE));
    }

    // normalizeSsid()

    @Test
    public void normalizeStripsQuotes() {
        assertEquals(HOME, SwitchPolicy.normalizeSsid("\"" + HOME + "\""));
    }

    @Test
    public void normalizeRejectsPlaceholders() {
        assertNull(SwitchPolicy.normalizeSsid("<unknown ssid>"));
        assertNull(SwitchPolicy.normalizeSsid("   "));
        assertNull(SwitchPolicy.normalizeSsid(null));
    }

    // writes

    @Test
    public void providerIsWrittenWhenItDiffersEvenInHostnameMode() {
        assertTrue(SwitchPolicy.providerNeedsWrite("old.dns.nextdns.io", DNS));
        assertFalse(SwitchPolicy.providerNeedsWrite(DNS, DNS));
        assertFalse(SwitchPolicy.providerNeedsWrite(null, ""));
    }

    @Test
    public void modeIsOnlyWrittenWhenItDiffers() {
        assertTrue(SwitchPolicy.modeNeedsWrite("hostname", "off"));
        assertFalse(SwitchPolicy.modeNeedsWrite("off", "off"));
        assertFalse(SwitchPolicy.modeNeedsWrite("off", null));
    }

    // stop

    @Test
    public void stoppingRestoresOnlyWhatWasSwitchedOff() {
        assertTrue(SwitchPolicy.shouldRestoreOnStop("off", DNS));
        assertFalse(SwitchPolicy.shouldRestoreOnStop("hostname", DNS));
        assertFalse(SwitchPolicy.shouldRestoreOnStop("", DNS));
        assertFalse(SwitchPolicy.shouldRestoreOnStop("off", ""));
    }
}
