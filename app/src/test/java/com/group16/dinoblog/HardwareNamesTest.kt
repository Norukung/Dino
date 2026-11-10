package com.group16.dinoblog

import com.group16.dinoblog.model.DiscoveredPeer
import com.group16.dinoblog.model.HardwareNames
import com.group16.dinoblog.model.NearbyPeerLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareNamesTest {
    @Test fun fullWifiNameAndSeparateDinoName() {
        val peer = DiscoveredPeer(HardwareNames.normalize("Galaxy A55")!!, "02:00:00:00:00:01", "Available")
        assertEquals("Galaxy A55", peer.name)
        assertEquals("Nirut09", NearbyPeerLabels.title(peer, peer.address, "Nirut09"))
        assertEquals("Galaxy A55", NearbyPeerLabels.title(peer, null, null))
    }

    @Test fun duplicateAndChangedNamesKeepDistinctAddressIdentity() {
        val first = DiscoveredPeer("Galaxy A55", "02:00:00:00:00:01", "Available")
        val second = DiscoveredPeer("Galaxy A55", "02:00:00:00:00:02", "Available")
        assertEquals(2, listOf(first, second).distinctBy { it.address }.size)
        assertEquals(first.address, first.copy(name = "William Phone").address)
    }

    @Test fun unavailableAndControlCharacters() {
        assertNull(HardwareNames.normalize(null))
        assertNull(HardwareNames.normalize("\n\u0001"))
        assertEquals("Somchok's Galaxy", HardwareNames.normalize("Somchok's\nGalaxy"))
        val unknown = DiscoveredPeer(HardwareNames.normalize(null) ?: HardwareNames.UNAVAILABLE, "02", "Available")
        assertEquals("Device name unavailable", unknown.name)
    }
}
