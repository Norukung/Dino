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
        assertThrows(IllegalArgumentException::class.java) { WireProtocol.encode(packet().copy(version = 2)) }
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
            WireProtocol.encode(valid.copy(message = valid.message!!.copy(timestamp = Long.MAX_VALUE)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WireProtocol.encode(valid.copy(message = valid.message!!.copy(ttl = 0)))
        }
    }
}
