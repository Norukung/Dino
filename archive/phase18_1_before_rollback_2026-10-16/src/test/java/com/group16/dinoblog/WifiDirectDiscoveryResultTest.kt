package com.group16.dinoblog

import com.group16.dinoblog.model.NearbyWifiCorrelation
import com.group16.dinoblog.model.WifiDirectDeviceNames
import com.group16.dinoblog.model.WifiDirectDiscoveryResult
import com.group16.dinoblog.model.WifiDirectPeerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiDirectDiscoveryResultTest {
    @Test fun blankAndUntrustedPlatformNamesHaveSafeDisplayValues() {
        assertEquals("Wi-Fi Direct device", WifiDirectDeviceNames.sanitize(null))
        assertEquals("Wi-Fi Direct device", WifiDirectDeviceNames.sanitize(" \n\t "))
        assertEquals("Galaxy A55", WifiDirectDeviceNames.sanitize(" \nGalaxy\r\tA55\u202e "))
        assertEquals("Galaxy A55", WifiDirectDeviceNames.sanitize("Galaxy\u2028A55"))
        assertEquals("🙂".repeat(48) + "…", WifiDirectDeviceNames.sanitize("🙂".repeat(49)))
    }

    @Test fun duplicateNamesDoNotCreateANameBasedHandoff() {
        val first = WifiDirectDiscoveryResult("Galaxy A55", "endpoint-one", WifiDirectPeerStatus.AVAILABLE)
        val second = WifiDirectDiscoveryResult("Galaxy A55", "endpoint-two", WifiDirectPeerStatus.AVAILABLE)
        val current = listOf(first, second)
        assertEquals(2, current.map { it.addressInternal }.distinct().size)
        assertNull(NearbyWifiCorrelation.forHandoff("ble-token", null, current, null))
        assertNull(NearbyWifiCorrelation.forHandoff(null, "endpoint-two", current, null))
        assertEquals(second, NearbyWifiCorrelation.forHandoff("ble-token", "endpoint-two", current, null))
        assertEquals(first, NearbyWifiCorrelation.forHandoff("ble-token", "endpoint-one", emptyList(), first))
        assertNull(NearbyWifiCorrelation.forHandoff("ble-token", "endpoint-one", emptyList(), second))
    }
}
