package com.group16.dinoblog.network

import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.DeliveryReceipt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

@Serializable
enum class PacketType { HELLO, INVENTORY, MESSAGE, ACK, PING, PONG, DELIVERY_RECEIPT }

@Serializable
data class WirePacket(
    val type: PacketType,
    val version: Int = 2,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val message: Message? = null,
    val messageId: String? = null,
    /** Custody is not end-to-end delivery. Only ACK packets use this field. */
    val ackStatus: MessageStatus = MessageStatus.DELIVERED,
    val messageIds: List<String>? = null,
    // Null means a Phase 1-5 peer; [] explicitly advertises receipt support.
    val deliveryReceiptIds: List<String>? = null,
    val receipt: DeliveryReceipt? = null,
    val receiptId: String? = null,
    val ackFor: PacketType = PacketType.MESSAGE,
)

object WireProtocol {
    const val PORT = 8988
    const val MAX_FRAME_BYTES = 65_536
    const val MAX_PAYLOAD_CHARS = 4_096
    const val MAX_TTL_SECONDS = 604_800L
    // A complete ID-only snapshot fits within the existing 64 KiB frame bound.
    const val MAX_INVENTORY_IDS = 1_000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun isUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    }.getOrDefault(false)

    fun validate(packet: WirePacket) {
        require(packet.version == 2) { "Unsupported protocol version; install the same Dinoblog version on both phones" }
        when (packet.type) {
            PacketType.HELLO -> {
                require(packet.deviceId?.let(::isUuid) == true) { "Invalid Device ID" }
                // Identity is solely the UUID; the friendly name is optional metadata.
                require(packet.deviceName == null || (packet.deviceName.isNotBlank() && packet.deviceName.length <= 80)) { "Invalid name" }
            }
            PacketType.INVENTORY -> {
                require(packet.deviceId?.let(::isUuid) == true) { "Invalid inventory device ID" }
                val ids = requireNotNull(packet.messageIds) { "Missing inventory IDs" }
                val receiptIds = packet.deliveryReceiptIds.orEmpty()
                require(ids.size + receiptIds.size <= MAX_INVENTORY_IDS && ids.all(::isUuid) && receiptIds.all(::isUuid)) { "Invalid or oversized inventory" }
                require(packet.message == null && packet.messageId == null && packet.receipt == null && packet.receiptId == null) { "Inventory must contain only IDs" }
            }
            PacketType.MESSAGE -> {
                val m = requireNotNull(packet.message) { "Missing message" }
                require(isUuid(m.messageId) && isUuid(m.senderId) && isUuid(m.receiverId)) { "Invalid message IDs" }
                require(m.payload.isNotBlank() && m.payload.length <= MAX_PAYLOAD_CHARS) { "Invalid payload" }
                require(m.ttl in 1..MAX_TTL_SECONDS && m.timestamp > 0 && m.timestamp <= Long.MAX_VALUE - m.ttl * 1000) { "Invalid TTL/timestamp" }
                require(m.expiresAt == m.createdAt + m.ttl * 1000) { "Invalid expiration" }
                require(m.hopLimit in 1..255 && m.hopCount in 0..m.hopLimit) { "Invalid hop count/limit" }
                require(m.sourceId != m.destinationId) { "Source equals destination" }
                require(m.carrierId == null || (isUuid(m.carrierId) && m.carrierId != m.sourceId && m.carrierId != m.destinationId)) { "Invalid carrier" }
                require(m.status == MessageStatus.PENDING) { "Invalid wire status" }
            }
            PacketType.ACK -> {
                require(packet.ackFor in listOf(PacketType.MESSAGE, PacketType.DELIVERY_RECEIPT)) { "Invalid ACK target" }
                if (packet.ackFor == PacketType.MESSAGE) {
                    require(packet.messageId?.let(::isUuid) == true && packet.receiptId == null) { "Invalid message ACK" }
                } else {
                    require(packet.receiptId?.let(::isUuid) == true && packet.messageId == null &&
                        packet.ackStatus == MessageStatus.CARRIED) { "Invalid receipt storage ACK" }
                }
                require(packet.ackStatus in listOf(MessageStatus.DELIVERED, MessageStatus.CARRIED)) { "Invalid ACK status" }
            }
            PacketType.DELIVERY_RECEIPT -> {
                val r = requireNotNull(packet.receipt) { "Missing delivery receipt" }
                require(isUuid(r.originalMessageId) && isUuid(r.originalSourceId) && isUuid(r.originalDestinationId) &&
                    r.originalSourceId != r.originalDestinationId && r.receiptId == DeliveryReceipt.idFor(r.originalMessageId)) { "Invalid receipt identity" }
                require(r.deliveredAt > 0 && r.createdAt >= r.deliveredAt && r.expiresAt > r.createdAt &&
                    r.expiresAt - r.createdAt <= DeliveryReceipt.MAX_LIFETIME_MILLIS) { "Invalid receipt lifetime" }
                require(r.hopLimit in 1..255 && r.hopCount in 0..r.hopLimit) { "Invalid receipt hop budget" }
                require(packet.message == null && packet.messageId == null) { "Receipt must not contain MESSAGE" }
            }
            PacketType.PING, PacketType.PONG -> Unit
        }
    }

    fun encode(packet: WirePacket): ByteArray {
        validate(packet)
        val fields = if (packet.type == PacketType.INVENTORY) {
            // No payload, message envelope, or ACK status is advertised in inventory.
            JsonObject(mapOf("type" to JsonPrimitive(packet.type.name), "version" to JsonPrimitive(packet.version),
                "deviceId" to JsonPrimitive(packet.deviceId), "messageIds" to json.encodeToJsonElement(packet.messageIds)) +
                (packet.deliveryReceiptIds?.let { mapOf("deliveryReceiptIds" to json.encodeToJsonElement(it)) } ?: emptyMap()))
        } else if (packet.type == PacketType.DELIVERY_RECEIPT) {
            JsonObject(mapOf("type" to JsonPrimitive(packet.type.name), "version" to JsonPrimitive(packet.version),
                "receipt" to json.encodeToJsonElement(packet.receipt)))
        } else json.encodeToJsonElement(packet).jsonObject
        // Keep legacy "type" for existing version-2 peers while also publishing
        // the explicit packetType discriminator. This is the same packet protocol.
        val envelope = JsonObject(fields + ("packetType" to JsonPrimitive(packet.type.name)))
        return envelope.toString().toByteArray(Charsets.UTF_8).also {
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
        val fields = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val legacyType = fields["type"]
        val packetType = fields["packetType"]
        require(legacyType != null || packetType != null) { "Missing packet type" }
        require(legacyType == null || packetType == null || legacyType == packetType) { "Conflicting packet types" }
        val normalized = if (legacyType == null) JsonObject(fields + ("type" to requireNotNull(packetType))) else fields
        return json.decodeFromJsonElement<WirePacket>(normalized).also(::validate)
    }
}
