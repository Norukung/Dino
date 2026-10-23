package com.group16.dinoblog.network

import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

@Serializable
enum class PacketType { HELLO, MESSAGE, ACK, PING, PONG }

@Serializable
data class WirePacket(
    val type: PacketType,
    val version: Int = 1,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val message: Message? = null,
    val messageId: String? = null,
)

object WireProtocol {
    const val PORT = 8988
    const val MAX_FRAME_BYTES = 65_536
    const val MAX_PAYLOAD_CHARS = 4_096
    const val MAX_TTL_SECONDS = 604_800L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun isUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    }.getOrDefault(false)

    fun validate(packet: WirePacket) {
        require(packet.version == 1) { "Unsupported protocol version" }
        when (packet.type) {
            PacketType.HELLO -> {
                require(packet.deviceId?.let(::isUuid) == true) { "Invalid Device ID" }
                require(!packet.deviceName.isNullOrBlank() && packet.deviceName.length <= 80) { "Invalid name" }
            }
            PacketType.MESSAGE -> {
                val m = requireNotNull(packet.message) { "Missing message" }
                require(isUuid(m.messageId) && isUuid(m.senderId) && isUuid(m.receiverId)) { "Invalid message IDs" }
                require(m.payload.isNotBlank() && m.payload.length <= MAX_PAYLOAD_CHARS) { "Invalid payload" }
                require(m.ttl in 1..MAX_TTL_SECONDS && m.timestamp > 0 && m.timestamp <= Long.MAX_VALUE - m.ttl * 1000) { "Invalid TTL/timestamp" }
                require(m.status == MessageStatus.PENDING) { "Invalid wire status" }
            }
            PacketType.ACK -> require(packet.messageId?.let(::isUuid) == true) { "Invalid ACK" }
            PacketType.PING, PacketType.PONG -> Unit
        }
    }

    fun encode(packet: WirePacket): ByteArray {
        validate(packet)
        return json.encodeToString(packet).toByteArray(Charsets.UTF_8).also {
            require(it.size in 1..MAX_FRAME_BYTES) { "Frame too large" }
        }
    }

    // TCP is a byte stream, not a packet stream. A 4-byte big-endian length prefix
    // and readFully() handle split/coalesced packets and Unicode/newlines safely.
    fun write(output: DataOutputStream, packet: WirePacket) {
        val bytes = encode(packet)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun read(input: DataInputStream): WirePacket {
        val size = input.readInt()
        require(size in 1..MAX_FRAME_BYTES) { "Invalid frame length: $size" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return json.decodeFromString<WirePacket>(bytes.toString(Charsets.UTF_8)).also(::validate)
    }
}
