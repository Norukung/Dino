package com.group16.dinoblog

import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.MediaManifest
import com.group16.dinoblog.dtn.DtnConfig
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test fun `audio and video share manifest and binary chunk protocol`() {
        for ((type, mime) in listOf(MessageContentType.AUDIO to "audio/mpeg", MessageContentType.VIDEO to "video/mp4")) {
            val mediaId = UUID.randomUUID().toString()
            val message = Message(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "", 1_700_000_000_000, 86_400, MessageStatus.PENDING,
                hopCount = 1, contentType = type, mediaId = mediaId)
            val manifest = MediaManifest(message.messageId, mediaId, mime, "full-name.media", 131_073,
                "a".repeat(64), DtnConfig.IMAGE_CHUNK_BYTES, 2, type, 1_000,
                if (type == MessageContentType.VIDEO) 320 else null,
                if (type == MessageContentType.VIDEO) 240 else null)
            val packet = WirePacket(PacketType.MEDIA_MANIFEST, message = message, manifest = manifest)
            val stream = ByteArrayOutputStream()
            WireProtocol.write(DataOutputStream(stream), packet)
            assertEquals(packet, WireProtocol.read(DataInputStream(ByteArrayInputStream(stream.toByteArray()))))
            val chunk = WirePacket(PacketType.MEDIA_CHUNK, messageId = message.messageId,
                mediaId = mediaId, chunkIndex = 0, dataLength = 3, binaryData = byteArrayOf(0, 1, -1))
            val chunkStream = ByteArrayOutputStream()
            WireProtocol.write(DataOutputStream(chunkStream), chunk)
            val decoded = WireProtocol.read(DataInputStream(ByteArrayInputStream(chunkStream.toByteArray())))
            assertTrue(decoded.binaryData!!.contentEquals(chunk.binaryData!!))
        }
    }

    @Test fun `media type size and filename conflicts are rejected before chunks`() {
        val id = UUID.randomUUID().toString()
        val audio = Message(id, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "",
            1_700_000_000_000, 60, MessageStatus.PENDING, contentType = MessageContentType.AUDIO,
            mediaId = UUID.randomUUID().toString())
        val manifest = MediaManifest(id, audio.mediaId!!, "audio/mpeg", "audio.mp3",
            DtnConfig.MAX_AUDIO_BYTES.toLong() + 1, "a".repeat(64), DtnConfig.IMAGE_CHUNK_BYTES,
            (DtnConfig.MAX_AUDIO_BYTES / DtnConfig.IMAGE_CHUNK_BYTES) + 1,
            MessageContentType.AUDIO, 1_000)
        assertThrows(IllegalArgumentException::class.java) {
            WireProtocol.encode(WirePacket(PacketType.MEDIA_MANIFEST, message = audio, manifest = manifest))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WireProtocol.encode(WirePacket(PacketType.MEDIA_MANIFEST, message = audio,
                manifest = manifest.copy(fileSize = 3, totalChunks = 1, originalFileName = "../audio.mp3")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WireProtocol.encode(WirePacket(PacketType.MEDIA_MANIFEST, message = audio,
                manifest = manifest.copy(fileSize = 3, totalChunks = 1, contentType = MessageContentType.VIDEO)))
        }
    }

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
        for (size in listOf(-2, 0, WireProtocol.MAX_FRAME_BYTES + 1, Int.MAX_VALUE)) {
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

    @Test fun `binary chunks remain framed separately from JSON packets`() {
        val messageId = UUID.randomUUID().toString()
        val mediaId = UUID.randomUUID().toString()
        val raw = ByteArray(DtnConfig.IMAGE_CHUNK_BYTES) { (it % 251).toByte() }
        val chunk = WirePacket(PacketType.MEDIA_CHUNK, messageId = messageId, mediaId = mediaId,
            chunkIndex = 0, dataLength = raw.size, binaryData = raw)
        val control = WirePacket(PacketType.ACK, messageId = messageId)
        val bytes = ByteArrayOutputStream()
        WireProtocol.write(DataOutputStream(bytes), chunk)
        WireProtocol.write(DataOutputStream(bytes), control)
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        val decoded = WireProtocol.read(input)
        assertEquals(PacketType.MEDIA_CHUNK, decoded.type)
        org.junit.Assert.assertArrayEquals(raw, decoded.binaryData)
        assertEquals(control, WireProtocol.read(input))
        assertEquals(-1, input.read())
        org.junit.Assert.assertFalse(WireProtocol.encode(chunk).toString(Charsets.UTF_8).contains("binaryData"))
    }

    @Test fun `image manifest validates size type and message identity`() {
        val message = Message(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), "", 1_700_000_000_000, 86_400, MessageStatus.PENDING,
            contentType = MessageContentType.IMAGE, mediaId = UUID.randomUUID().toString())
        val manifest = MediaManifest(message.messageId, message.mediaId!!, "image/jpeg", "image.jpg", 3,
            "a".repeat(64), DtnConfig.IMAGE_CHUNK_BYTES, 1)
        WireProtocol.validate(WirePacket(PacketType.MEDIA_MANIFEST, message = message, manifest = manifest))
        for (bad in listOf(manifest.copy(fileSize = -1), manifest.copy(fileSize = DtnConfig.MAX_IMAGE_BYTES + 1L),
            manifest.copy(mimeType = "video/mp4"), manifest.copy(originalFileName = "../image.jpg"),
            manifest.copy(messageId = UUID.randomUUID().toString()), manifest.copy(totalChunks = 2))) {
            assertThrows(IllegalArgumentException::class.java) {
                WireProtocol.validate(WirePacket(PacketType.MEDIA_MANIFEST, message = message, manifest = bad))
            }
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
            """{"packetType":"HELLO","deviceId":"$id","version":999}""")) {
            assertThrows(IllegalArgumentException::class.java) { readJson(invalid) }
        }
        assertEquals("", readJson("""{"packetType":"HELLO","deviceId":"$id","deviceName":""}""").deviceName)
    }
}
