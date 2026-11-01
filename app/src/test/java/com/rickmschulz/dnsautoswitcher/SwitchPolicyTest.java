package com.rickmschulz.dnsautoswitcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Covers the paths that were wrong before (an unreadable SSID, a hostname edited while already
 * away, stopping at the configured network), and the several-networks case.
 */
public class SwitchPolicyTest {

    private static final String HOME = "HomeNet";
    private static final String WORK = "OfficeWiFi";
    private static final String DNS = "12345.dns.nextdns.io";

    private static final List<String> ONE = Collections.singletonList(HOME);
    private static final List<String> TWO = Arrays.asList(HOME, WORK);

    // decide()

    @Test
    public void noWifiMeansRemote() {
        assertEquals(SwitchPolicy.State.REMOTE,
                SwitchPolicy.decide(ONE, DNS, false, null));
    }

    @Test
    public void configuredSsidMeansLocal() {
        assertEquals(SwitchPolicy.State.LOCAL,
                SwitchPolicy.decide(ONE, DNS, true, HOME));
    }

    @Test
    public void quotedConfiguredSsidStillMatches() {
        assertEquals(SwitchPolicy.State.LOCAL,
                SwitchPolicy.decide(ONE, DNS, true, "\"" + HOME + "\""));
    }

    @Test
    public void otherNetworkMeansRemote() {
        assertEquals(SwitchPolicy.State.REMOTE,
                SwitchPolicy.decide(ONE, DNS, true, "CafeWiFi"));
    }

    @Test
    public void anyConfiguredNetworkIsLocal() {
        assertEquals(SwitchPolicy.State.LOCAL, SwitchPolicy.decide(TWO, DNS, true, HOME));
        assertEquals(SwitchPolicy.State.LOCAL, SwitchPolicy.decide(TWO, DNS, true, WORK));
    }

    @Test
    public void aNetworkThatIsNotOnTheListIsRemote() {
        assertEquals(SwitchPolicy.State.REMOTE, SwitchPolicy.decide(TWO, DNS, true, "CafeWiFi"));
    }

    @Test
    public void blankEntriesInTheListAreIgnored() {
        List<String> messy = Arrays.asList("", "   ", null, WORK);
        assertEquals(SwitchPolicy.State.LOCAL, SwitchPolicy.decide(messy, DNS, true, WORK));
        // A blank current SSID is as unreadable as <unknown ssid>, so nothing is decided.
        assertEquals(SwitchPolicy.State.UNKNOWN, SwitchPolicy.decide(messy, DNS, true, "  "));
    }

    @Test
    public void aListOfOnlyBlanksBehavesLikeAnEmptyList() {
        List<String> blanks = Arrays.asList("", "   ", null);
        assertEquals(SwitchPolicy.State.REMOTE, SwitchPolicy.decide(blanks, DNS, true, HOME));
    }

    @Test
    public void unknownSsidLeavesTheSettingAlone() {
        assertEquals(SwitchPolicy.State.UNKNOWN,
                SwitchPolicy.decide(TWO, DNS, true, "<unknown ssid>"));
        assertNull(SwitchPolicy.desiredMode(SwitchPolicy.State.UNKNOWN));
    }

    @Test
    public void nullSsidLeavesTheSettingAlone() {
        assertEquals(SwitchPolicy.State.UNKNOWN,
                SwitchPolicy.decide(TWO, DNS, true, null));
    }

    @Test
    public void ssidIsComparedTrimmed() {
        assertEquals(SwitchPolicy.State.LOCAL,
                SwitchPolicy.decide(ONE, DNS, true, "  " + HOME + "  "));
    }

    @Test
    public void configuredEntriesAreComparedTrimmedToo() {
        List<String> padded = Collections.singletonList("  " + WORK + " ");
        assertEquals(SwitchPolicy.State.LOCAL, SwitchPolicy.decide(padded, DNS, true, WORK));
    }

    @Test
    public void withoutAProviderNothingIsDecided() {
        assertEquals(SwitchPolicy.State.UNKNOWN,
                SwitchPolicy.decide(TWO, "", true, "CafeWiFi"));
        assertEquals(SwitchPolicy.State.UNKNOWN,
                SwitchPolicy.decide(TWO, null, false, null));
    }

    @Test
    public void withoutAConfiguredNetworkEveryNetworkIsRemote() {
        assertEquals(SwitchPolicy.State.REMOTE,
                SwitchPolicy.decide(Collections.emptyList(), DNS, true, "CafeWiFi"));
        assertEquals(SwitchPolicy.State.REMOTE,
                SwitchPolicy.decide(null, DNS, true, "CafeWiFi"));
    }

    // isConfigured()

    @Test
    public void isConfiguredMatchesAnyEntry() {
        assertTrue(SwitchPolicy.isConfigured(TWO, HOME));
        assertTrue(SwitchPolicy.isConfigured(TWO, WORK));
        assertFalse(SwitchPolicy.isConfigured(TWO, "CafeWiFi"));
        assertFalse(SwitchPolicy.isConfigured(TWO, null));
        assertFalse(SwitchPolicy.isConfigured(Collections.emptyList(), HOME));
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
