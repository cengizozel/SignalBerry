package com.example.signalberry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Pure key-resolution rules — the part that has cost us the most regressions. */
public class PeerKeyTest {

    private static final String UUID = "0f0bbaee-6fd7-4b35-a803-2479e9676df9";

    @Test public void numberStripsToDigits() {
        assertEquals("15551234567", PeerKey.normalize("+1 (555) 123-4567"));
    }

    @Test public void uuidLowercases() {
        assertEquals(UUID, PeerKey.normalize("0F0BBAEE-6FD7-4B35-A803-2479E9676DF9"));
    }

    @Test public void groupKeyIsVerbatim() {
        // base64 is case- and symbol-significant; must not be touched
        String g = "group:WdZrxff4vPcDxPgk9WlwQO97g8LRP+7X9nOeElXbQps=";
        assertEquals(g, PeerKey.normalize(g));
    }

    @Test public void pniServiceIdKeysAsUuid() {
        // the 2026-06 dual-thread bug: PNI:<uuid> must become the bare uuid,
        // never a digit-soup fake number
        assertEquals(UUID, PeerKey.normalize("PNI:" + UUID.toUpperCase()));
        assertEquals(UUID, PeerKey.normalize("ACI:" + UUID));
    }

    @Test public void overlongDigitStringIsNotAPhoneNumber() {
        // a 19-digit string (uuid hex stripped to digits) exceeds E.164 — keep
        // it as-is rather than minting a bogus phone thread
        String soup = "0067435803247996769";
        assertEquals(soup, PeerKey.normalize(soup));
    }

    @Test public void resolvePrefersNumberOverUuid() {
        assertEquals("15551234567",
                PeerKey.resolve("+15551234567", UUID, new HashMap<>()));
    }

    @Test public void resolveTranslatesUuidThroughLearnedMap() {
        Map<String, String> map = new HashMap<>();
        map.put(UUID, "15550009999");
        assertEquals("15550009999", PeerKey.resolve("", UUID, map));
    }

    @Test public void resolveUuidUnmappedStaysUuid() {
        assertEquals(UUID, PeerKey.resolve(null, UUID, new HashMap<>()));
    }

    @Test public void resolveGroupWins() {
        String g = "group:abc+/=";
        assertEquals(g, PeerKey.resolve(g, null, new HashMap<>()));
    }

    @Test public void resolveUuidInNumberSlotNotDigitStripped() {
        // some envelopes put a service id in the number field
        assertEquals(UUID, PeerKey.resolve("PNI:" + UUID, "", new HashMap<>()));
    }

    @Test public void isUuidKeyDiscriminates() {
        assertTrue(PeerKey.isUuidKey(UUID));
        assertFalse(PeerKey.isUuidKey("15551234567"));
        assertFalse(PeerKey.isUuidKey("group:abc"));
        assertFalse(PeerKey.isUuidKey(null));
    }
}
