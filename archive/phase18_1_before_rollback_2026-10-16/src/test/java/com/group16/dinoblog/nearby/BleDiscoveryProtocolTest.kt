package com.group16.dinoblog.nearby

import org.junit.Assert.*
import org.junit.Test

class BleDiscoveryProtocolTest {
    @Test fun scanResponseFitsLegacyLimitAndNeverContainsIdentity() {
        assertEquals(13, BleDiscoveryProtocol.MAX_NEARBY_NAME_BYTES)
        assertEquals(31, BleDiscoveryProtocol.SCAN_RESPONSE_OVERHEAD_BYTES +
            BleDiscoveryProtocol.encodeName("William Alexander", true).size)
        assertEquals(31, BleDiscoveryProtocol.ADVERTISEMENT_DATA_BYTES + 3)
        val data = BleDiscoveryProtocol.encodeName("William Alexander", true)
        assertEquals("William Alexa", BleDiscoveryProtocol.decodeName(data))
        assertEquals("DinoBlog User", BleDiscoveryProtocol.decodeName(BleDiscoveryProtocol.encodeName("", true)))
        assertEquals(19, BleDiscoveryProtocol.SCAN_RESPONSE_OVERHEAD_BYTES +
            BleDiscoveryProtocol.encodeName("William", false).size)
    }

    @Test fun namesAreUtf8SafeAndInvalidRemoteNameFallsBack() {
        val thai = BleDiscoveryProtocol.encodeName("วรฤทธิ์", true)
        assertNotNull(BleDiscoveryProtocol.decodeName(thai))
        assertTrue(thai.size <= 13)
        val emoji = BleDiscoveryProtocol.encodeName("Alex 😀", true)
        assertEquals("Alex 😀", BleDiscoveryProtocol.decodeName(emoji))
        assertEquals("Alex", BleDiscoveryProtocol.decodeName(BleDiscoveryProtocol.encodeName("Alex 👩‍💻", true)))
        assertEquals("DinoBlog User", BleDiscoveryProtocol.decodeName(BleDiscoveryProtocol.encodeName("👨‍👩‍👧‍👦", true)))
        val long = BleDiscoveryProtocol.encodeName("ก".repeat(32), true)
        assertEquals("ก".repeat(4), BleDiscoveryProtocol.decodeName(long))
        assertNull(BleDiscoveryProtocol.decodeName(byteArrayOf(0xc3.toByte(), 0x28)))
        assertNull(BleDiscoveryProtocol.decodeName(byteArrayOf(10)))
        assertNull(BleDiscoveryProtocol.decodeName(ByteArray(14) { 65 }))
        assertNull(BleDiscoveryProtocol.decodeName(BleDiscoveryProtocol.encodeName("William", false)))
        val primary = BleDiscoveryProtocol.encode(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals("0102030405060708", BleDiscoveryProtocol.decode(primary, byteArrayOf(0xc3.toByte()))?.token)
        assertNull(BleDiscoveryProtocol.decode(primary, byteArrayOf(0xc3.toByte()))?.nearbyDisplayName)
    }

    @Test fun duplicateNamesKeepSeparateTokensAndScanResponseUpdatesSameRow() {
        val tracker = NearbyResultTracker()
        val tokenA = "010203040506a7f2"
        val tokenB = "01020304050655d1"
        tracker.observe(DiscoveryAdvertisement(tokenA, 1, 3), -60, 1)
        tracker.observe(DiscoveryAdvertisement(tokenB, 1, 3), -70, 1)
        tracker.updateName(tokenA, BleDiscoveryProtocol.encodeName("William", true), 2)
        tracker.updateName(tokenB, BleDiscoveryProtocol.encodeName("William", true), 2)
        assertEquals(2, tracker.snapshot().size)
        assertEquals(setOf("A7F2", "55D1"), tracker.snapshot().map { it.shortCode }.toSet())
        assertEquals(setOf("William"), tracker.snapshot().mapNotNull { it.nearbyDisplayName }.toSet())
        tracker.updateName(tokenA, BleDiscoveryProtocol.encodeName("Will", true), 3)
        assertEquals("Will", tracker.snapshot().first { it.discoveryToken == tokenA }.nearbyDisplayName)
        tracker.updateName(tokenA, BleDiscoveryProtocol.encodeName("Will", false), 4)
        assertNull(tracker.snapshot().first { it.discoveryToken == tokenA }.nearbyDisplayName)
    }

    @Test fun payloadIsExactlyTenBytesAndVersioned() {
        val token = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val bytes = BleDiscoveryProtocol.encode(token)
        assertEquals(10, bytes.size)
        assertEquals(28, BleDiscoveryProtocol.ADVERTISEMENT_DATA_BYTES)
        assertEquals(31, BleDiscoveryProtocol.MAX_LEGACY_ADVERTISEMENT_BYTES)
        assertEquals("0102030405060708", BleDiscoveryProtocol.decode(bytes)?.token)
        assertEquals(3, BleDiscoveryProtocol.decode(bytes)?.flags)
        assertNull(BleDiscoveryProtocol.decode(bytes + 0))
        assertNull(BleDiscoveryProtocol.decode(bytes.copyOf(9)))
        assertNull(BleDiscoveryProtocol.decode(bytes.clone().also { it[0] = 2 }))
        assertNull(BleDiscoveryProtocol.decode(bytes.clone().also { it[9] = 4 }))
        assertNull(BleDiscoveryProtocol.decode(null))
    }

    @Test fun randomTokensRotateAndDoNotDeriveFromStableIdentity() {
        val first = BleDiscoveryProtocol.newToken()
        val second = BleDiscoveryProtocol.newToken()
        assertEquals(8, first.size)
        assertFalse(first.contentEquals(second))
        assertEquals(16, BleDiscoveryProtocol.run { first.toHex() }.length)
    }

    @Test fun repeatedResultsDeduplicateSmoothSortAndExpire() {
        val tracker = NearbyResultTracker()
        val a = DiscoveryAdvertisement("aaaaaaaaaaaaaaaa", 1, 3)
        val b = DiscoveryAdvertisement("bbbbbbbbbbbbbbbb", 1, 3)
        val c = DiscoveryAdvertisement("cccccccccccccccc", 1, 3)
        tracker.observe(a, -60, 1_000)
        tracker.observe(a, -80, 2_000)
        tracker.observe(b, -55, 2_000)
        tracker.observe(c, -90, 2_000)
        assertEquals(3, tracker.snapshot().size)
        assertEquals(-65.0, tracker.snapshot().first { it.discoveryToken == a.token }.smoothedRssi, 0.001)
        assertEquals(SignalLevel.MEDIUM, tracker.snapshot().first { it.discoveryToken == a.token }.signalLevel)
        assertEquals(b.token, tracker.snapshot().first().discoveryToken)
        assertEquals(3, tracker.expire(2_000 + BleDiscoveryProtocol.RESULT_EXPIRY_MS))
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test fun thresholdsUseSignalCategoriesNotDistance() {
        val tracker = NearbyResultTracker()
        assertEquals(SignalLevel.STRONG, tracker.observe(DiscoveryAdvertisement("a", 1, 0), -60, 0).signalLevel)
        assertEquals(SignalLevel.MEDIUM, tracker.observe(DiscoveryAdvertisement("b", 1, 0), -75, 0).signalLevel)
        assertEquals(SignalLevel.WEAK, tracker.observe(DiscoveryAdvertisement("c", 1, 0), -76, 0).signalLevel)
    }
}
