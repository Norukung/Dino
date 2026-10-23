package com.group16.dinoblog

import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

class InventoryProtocolTest {
    private fun id() = UUID.randomUUID().toString()
    @Test fun `inventory frame is ID only and round trips empty and nonempty snapshots`() {
        for (ids in listOf(emptyList(), listOf(id(), id()))) {
            val packet = WirePacket(PacketType.INVENTORY, deviceId = id(), messageIds = ids)
            val encoded = WireProtocol.encode(packet).toString(Charsets.UTF_8)
            assertTrue(encoded.contains("\"packetType\":\"INVENTORY\""))
            assertFalse(encoded.contains("\"message\":"))
            assertFalse(encoded.contains("payload"))
            assertFalse(encoded.contains("ackStatus"))
            val bytes = ByteArrayOutputStream()
            WireProtocol.write(DataOutputStream(bytes), packet)
            assertEquals(packet, WireProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))))
        }
    }
    @Test fun `invalid IDs missing lists oversized arrays and message envelopes are rejected`() {
        val packet = WirePacket(PacketType.INVENTORY, deviceId = id(), messageIds = listOf(id()))
        for (invalid in listOf(packet.copy(deviceId = "IP"), packet.copy(messageIds = null),
            packet.copy(messageIds = listOf("MSG001")), packet.copy(messageIds = List(1001) { id() }),
            packet.copy(message = Message(id(), id(), id(), "secret payload", 1000, 60, MessageStatus.PENDING)))) {
            assertThrows(IllegalArgumentException::class.java) { WireProtocol.encode(invalid) }
        }
    }
    @Test fun `maximum complete inventory fits existing frame without raising frame limit`() {
        val packet = WirePacket(PacketType.INVENTORY, deviceId = id(), messageIds = List(WireProtocol.MAX_INVENTORY_IDS) { id() })
        assertTrue(WireProtocol.encode(packet).size < WireProtocol.MAX_FRAME_BYTES)
        WireProtocol.validate(packet.copy(messageIds = listOf(id(), id())))
    }
}
