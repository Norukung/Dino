package com.group16.dinoblog

import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.util.UUID

class WireProtocolTest {
    private fun packet(payload: String = "สวัสดี 🦖\nhello") = WirePacket(PacketType.MESSAGE, message = Message(
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        payload, 1_700_000_000_000, 86_400, MessageStatus.PENDING))

    @Test fun `unicode and consecutive frames survive one byte TCP fragments`() {
        val first = packet()
        val second = WirePacket(PacketType.ACK, messageId = first.message!!.messageId)
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        WireProtocol.write(output, first)
        WireProtocol.write(output, second)
        val fragmented = object : FilterInputStream(ByteArrayInputStream(bytes.toByteArray())) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 1))
        }
        val input = DataInputStream(fragmented)
        assertEquals(first, WireProtocol.read(input))
        assertEquals(second, WireProtocol.read(input))
        assertEquals(-1, input.read())
    }

    @Test fun `unbounded frame length rejected before allocation`() {
        for (size in listOf(-1, 0, WireProtocol.MAX_FRAME_BYTES + 1, Int.MAX_VALUE)) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).writeInt(size)
            assertThrows(IllegalArgumentException::class.java) {
                WireProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
            }
        }
    }

    @Test fun `oversized payload invalid UUID and unknown version rejected`() {
        assertThrows(IllegalArgumentException::class.java) { WireProtocol.encode(packet("x".repeat(4097))) }
        assertThrows(IllegalArgumentException::class.java) { WireProtocol.encode(WirePacket(PacketType.ACK, messageId = "not-a-uuid")) }
        assertThrows(IllegalArgumentException::class.java) { WireProtocol.encode(packet().copy(version = 1)) }
    }

    @Test fun `truncated frame never becomes a message`() {
        val bytes = ByteArrayOutputStream()
        WireProtocol.write(DataOutputStream(bytes), packet())
        assertThrows(java.io.EOFException::class.java) {
            WireProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray().dropLast(3).toByteArray())))
        }
    }

    @Test fun `TTL cannot overflow expiration calculation`() {
        val valid = packet()
        assertThrows(IllegalArgumentException::class.java) {
            WireProtocol.encode(valid.copy(message = valid.message!!.copy(createdAt = Long.MAX_VALUE)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WireProtocol.encode(valid.copy(message = valid.message!!.copy(ttl = 0)))
        }
    }

    @Test fun `DTN envelope rejects malformed hops expiry and custody ACK status`() {
        val original = packet()
        val message = original.message!!
        for (invalid in listOf(message.copy(hopCount = -1), message.copy(hopLimit = 0),
            message.copy(hopCount = 9, hopLimit = 8), message.copy(hopLimit = 256),
            message.copy(expiresAt = message.createdAt), message.copy(carrierId = message.sourceId),
            message.copy(carrierId = message.destinationId), message.copy(carrierId = "not-a-uuid"))) {
            assertThrows(IllegalArgumentException::class.java) { WireProtocol.encode(original.copy(message = invalid)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WireProtocol.encode(WirePacket(PacketType.ACK, messageId = message.messageId, ackStatus = MessageStatus.FORWARDED))
        }
        val bytes = ByteArrayOutputStream()
        val custody = original.copy(message = message.copy(carrierId = UUID.randomUUID().toString(), hopCount = 1))
        val ack = WirePacket(PacketType.ACK, messageId = message.messageId, ackStatus = MessageStatus.CARRIED)
        WireProtocol.write(DataOutputStream(bytes), custody)
        WireProtocol.write(DataOutputStream(bytes), ack)
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(custody, WireProtocol.read(input))
        assertEquals(ack, WireProtocol.read(input))
    }

    private fun readJson(json: String): WirePacket {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val framed = ByteArrayOutputStream()
        DataOutputStream(framed).apply { writeInt(bytes.size); write(bytes) }
        return WireProtocol.read(DataInputStream(ByteArrayInputStream(framed.toByteArray())))
    }

    @Test fun `minimal HELLO and legacy packets use the same parser`() {
        val id = UUID.randomUUID().toString()
        assertEquals(WirePacket(PacketType.HELLO, deviceId = id),
            readJson("""{"packetType":"HELLO","deviceId":"$id"}"""))
        assertEquals(WirePacket(PacketType.HELLO, deviceId = id, deviceName = "Old peer"),
            readJson("""{"type":"HELLO","version":2,"deviceId":"$id","deviceName":"Old peer"}"""))
        val original = packet()
        val encoded = WireProtocol.encode(original).toString(Charsets.UTF_8)
        assertEquals(original, readJson(encoded.replace(",\"packetType\":\"MESSAGE\"", "")))
        assertEquals(WirePacket(PacketType.ACK, messageId = id),
            readJson("""{"type":"ACK","version":2,"messageId":"$id"}"""))
        org.junit.Assert.assertTrue(encoded.contains("\"packetType\":\"MESSAGE\""))
        org.junit.Assert.assertTrue(encoded.contains("\"type\":\"MESSAGE\""))
        org.junit.Assert.assertTrue(WireProtocol.encode(WirePacket(PacketType.HELLO, deviceId = id))
            .toString(Charsets.UTF_8).contains("\"packetType\":\"HELLO\""))
    }

    @Test fun `ambiguous missing and invalid identity packets are rejected`() {
        val id = UUID.randomUUID().toString()
        for (invalid in listOf(
            """{"type":"MESSAGE","packetType":"HELLO","deviceId":"$id"}""",
            """{"deviceId":"$id"}""",
            """{"packetType":"HELLO","deviceId":"192.168.49.1"}""",
            """{"packetType":"HELLO"}""",
            """{"packetType":"HELLO","deviceId":"$id","version":999}""",
            """{"packetType":"HELLO","deviceId":"$id","deviceName":""}""")) {
            assertThrows(IllegalArgumentException::class.java) { readJson(invalid) }
        }
    }
}
