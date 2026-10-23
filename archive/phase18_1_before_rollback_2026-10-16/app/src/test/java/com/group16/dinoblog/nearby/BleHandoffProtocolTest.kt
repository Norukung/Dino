package com.group16.dinoblog.nearby

import com.group16.dinoblog.repository.NearbyConnectionMethod as Method
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class BleHandoffProtocolTest {
    @Test fun requestIsBoundedAndContainsOnlyOneTimeSessionAndTransportPreference() {
        val id = UUID.randomUUID()
        val bytes = BleHandoffProtocol.encodeRequest(BleHandoffProtocol.Request(id, Method.AUTO))
        assertEquals(18, bytes.size)
        assertEquals(BleHandoffProtocol.Request(id, Method.AUTO), BleHandoffProtocol.decodeRequest(bytes))
        assertNull(BleHandoffProtocol.decodeRequest(bytes + 0))
        assertNull(BleHandoffProtocol.decodeRequest(bytes.clone().also { it[0] = 9 }))
        assertNull(BleHandoffProtocol.decodeRequest(bytes.clone().also { it[17] = 9 }))
    }

    @Test fun autoAndForcedSelectionRemainBounded() {
        assertEquals(Method.WIFI_DIRECT, BleHandoffProtocol.select(Method.AUTO, Method.AUTO, true))
        assertEquals(Method.BLUETOOTH, BleHandoffProtocol.select(Method.AUTO, Method.AUTO, false))
        assertEquals(Method.BLUETOOTH, BleHandoffProtocol.select(Method.BLUETOOTH, Method.AUTO, true))
        assertNull(BleHandoffProtocol.select(Method.WIFI_DIRECT, Method.BLUETOOTH, true))
        assertNull(BleHandoffProtocol.select(Method.WIFI_DIRECT, Method.AUTO, false))
    }

    @Test fun untrustedOfferCannotOverrideForcedTransport() {
        val wifi = BleHandoffProtocol.decodeOffer(BleHandoffProtocol.accept(Method.WIFI_DIRECT,
            "02:11:22:33:44:55", true))!!
        val bluetooth = BleHandoffProtocol.decodeOffer(BleHandoffProtocol.accept(Method.BLUETOOTH,
            null, true))!!
        assertFalse(BleHandoffProtocol.acceptsOffer(Method.BLUETOOTH, wifi))
        assertFalse(BleHandoffProtocol.acceptsOffer(Method.WIFI_DIRECT, bluetooth))
        assertTrue(BleHandoffProtocol.acceptsOffer(Method.AUTO, wifi))
        assertTrue(BleHandoffProtocol.acceptsOffer(Method.AUTO, bluetooth))
        assertFalse(BleHandoffProtocol.acceptsOffer(Method.AUTO,
            BleHandoffProtocol.decodeOffer(BleHandoffProtocol.reject())!!))
    }

    @Test fun offerUsesTenBytesAndWifiEndpointIsOnlyAConnectionHint() {
        val wifi = BleHandoffProtocol.decodeOffer(BleHandoffProtocol.accept(Method.WIFI_DIRECT,
            "02:11:22:33:44:55", true))!!
        assertEquals("02:11:22:33:44:55", wifi.wifiAddress)
        assertTrue(wifi.bluetoothSupported)
        assertEquals(Method.WIFI_DIRECT, wifi.transport)
        assertEquals(10, BleHandoffProtocol.accept(Method.BLUETOOTH, null, true).size)
        assertTrue(BleHandoffProtocol.decodeOffer(BleHandoffProtocol.pending())!!.pending)
        assertFalse(BleHandoffProtocol.decodeOffer(BleHandoffProtocol.reject())!!.accepted)
    }

    @Test fun helloCarriesOptionalHandoffCorrelationWithoutChangingDeviceIdentity() {
        val peerId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val output = ByteArrayOutputStream()
        WireProtocol.write(DataOutputStream(output), WirePacket(PacketType.HELLO,
            deviceId = peerId, handoffSessionId = sessionId))
        val hello = WireProtocol.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        assertEquals(peerId, hello.deviceId)
        assertEquals(sessionId, hello.handoffSessionId)
        assertTrue(runCatching { WireProtocol.validate(WirePacket(PacketType.HELLO,
            deviceId = peerId, handoffSessionId = "not-a-uuid")) }.isFailure)
    }
}
