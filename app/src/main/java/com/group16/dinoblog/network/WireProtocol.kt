package com.group16.dinoblog.network

import com.group16.dinoblog.dtn.DtnConfig
import com.group16.dinoblog.model.PredictabilityEntry
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.MediaManifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
enum class PacketType { HELLO, INVENTORY, MESSAGE, ACK, MESSAGE_REJECT, DELIVERY_RECEIPT, DELIVERY_RECEIPT_ACK, PREDICTABILITY_SUMMARY, PING, PONG,
    MEDIA_MANIFEST, MEDIA_ACCEPT, MEDIA_CHUNK }

@Serializable
enum class MessageRejectReason { BUFFER_FULL, RELAY_DISABLED, MEDIA_TOO_LARGE, UNSUPPORTED_MEDIA, EXPIRED, ALREADY_DELIVERED, INVALID_MEDIA, HOP_LIMIT }

@Serializable
data class WirePacket(
    val type: PacketType,
    val version: Int = 2,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val displayName: String? = null,
    val message: Message? = null,
    val messageId: String? = null,
    /** Custody is not end-to-end delivery. Only ACK packets use this field. */
    val ackStatus: MessageStatus = MessageStatus.DELIVERED,
    val messageIds: List<String>? = null,
    val deliveredMessageIds: List<String> = emptyList(),
    val receipt: DeliveryReceipt? = null,
    val reason: MessageRejectReason? = null,
    val entries: List<PredictabilityEntry> = emptyList(),
    val relayEnabled: Boolean? = null,
    val manifest: MediaManifest? = null,
    val mediaId: String? = null,
    val chunkIndex: Int? = null,
    val dataLength: Int? = null,
    val missingChunks: List<Int>? = null,
    /** Raw bytes use the binary frame branch and are never JSON serialized. */
    @Transient val binaryData: ByteArray? = null,
)

object WireProtocol {
    const val PORT = 8988
    const val MAX_FRAME_BYTES = 65_536
    const val MAX_PAYLOAD_CHARS = 4_096
    const val MAX_TTL_SECONDS = 604_800L
    // A complete ID-only snapshot fits within the existing 64 KiB frame bound.
    const val MAX_INVENTORY_IDS = 1_000
    private const val BINARY_CHUNK_FRAME = -1
    private const val MAX_CHUNK_HEADER_BYTES = 4_096
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun isUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    }.getOrDefault(false)

    fun validateReceipt(r: DeliveryReceipt) {
        require(listOf(r.originalMessageId, r.sourceId, r.destinationId, r.createdByDeviceId).all(::isUuid)) { "Invalid receipt IDs" }
        require(r.sourceId != r.destinationId && r.createdByDeviceId == r.destinationId) { "Receipt must originate at destination" }
        require(r.deliveredAt > 0 && r.originalExpiresAt > r.deliveredAt) { "Invalid receipt timestamps" }
    }

    fun validateSummary(entries: List<PredictabilityEntry>) {
        require(entries.size <= DtnConfig.MAX_PREDICTABILITY_ENTRIES && entries.map { it.deviceId }.distinct().size == entries.size) { "Oversized or duplicate routing summary" }
        require(entries.all { isUuid(it.deviceId) && it.predictability.isFinite() && it.predictability in 0.0..1.0 }) { "Invalid predictability metadata" }
    }

    fun supportsMediaMime(type: MessageContentType, mime: String): Boolean = mime in when (type) {
            MessageContentType.IMAGE -> setOf("image/jpeg", "image/png", "image/webp")
            MessageContentType.AUDIO -> setOf("audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg", "audio/wav", "audio/x-wav", "audio/3gpp")
            MessageContentType.VIDEO -> setOf("video/mp4", "video/webm", "video/3gpp")
            MessageContentType.TEXT -> emptySet()
        }

    fun validateManifest(m: MediaManifest) {
        require(isUuid(m.messageId) && isUuid(m.mediaId)) { "Invalid media identity" }
        require(supportsMediaMime(m.contentType, m.mimeType)) { "Unsupported media MIME type" }
        require(m.originalFileName.length in 1..100 && m.originalFileName.none { Character.isISOControl(it) || it == '/' || it == '\\' }) { "Unsafe media filename" }
        require(m.fileSize in 1..DtnConfig.maxMediaBytes(m.contentType).toLong()) { "Invalid media size" }
        require(m.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid media SHA-256" }
        require(m.chunkSize == DtnConfig.IMAGE_CHUNK_BYTES && m.totalChunks ==
            ((m.fileSize + m.chunkSize - 1) / m.chunkSize).toInt()) { "Invalid media chunks" }
        require(m.durationMs == null || m.durationMs in 1..86_400_000L) { "Invalid media duration" }
        require(m.width == null || m.width in 1..20_000) { "Invalid media width" }
        require(m.height == null || m.height in 1..20_000) { "Invalid media height" }
        require(m.width == null || m.height == null || m.width.toLong() * m.height <= 100_000_000L) { "Invalid media dimensions" }
        require(m.contentType != MessageContentType.AUDIO || (m.durationMs != null && m.width == null && m.height == null)) { "Invalid audio metadata" }
        require(m.contentType != MessageContentType.VIDEO || (m.durationMs != null && m.width != null && m.height != null)) { "Invalid video metadata" }
    }

    fun validate(packet: WirePacket) {
        require(packet.version == 2) { "Unsupported protocol version; install the same Dinoblog version on both phones" }
        when (packet.type) {
            PacketType.HELLO -> {
                require(packet.deviceId?.let(::isUuid) == true) { "Invalid Device ID" }
                // Optional names are sanitized by the receiving profile layer.
            }
            PacketType.INVENTORY -> {
                require(packet.deviceId?.let(::isUuid) == true) { "Invalid inventory device ID" }
                val ids = requireNotNull(packet.messageIds) { "Missing inventory IDs" }
                require(ids.size + packet.deliveredMessageIds.size <= MAX_INVENTORY_IDS && ids.all(::isUuid) && packet.deliveredMessageIds.all(::isUuid)) { "Invalid or oversized inventory" }
                require(packet.message == null && packet.messageId == null) { "Inventory must contain only IDs" }
            }
            PacketType.MESSAGE -> {
                val m = requireNotNull(packet.message) { "Missing message" }
                require(isUuid(m.messageId) && isUuid(m.senderId) && isUuid(m.receiverId)) { "Invalid message IDs" }
                require(when (m.contentType) {
                    MessageContentType.TEXT -> m.payload.isNotBlank() && m.payload.length <= MAX_PAYLOAD_CHARS && m.mediaId == null
                    MessageContentType.IMAGE, MessageContentType.AUDIO, MessageContentType.VIDEO ->
                        m.payload.isEmpty() && m.mediaId?.let(::isUuid) == true
                }) { "Invalid message content" }
                require(m.ttl in 1..MAX_TTL_SECONDS && m.timestamp > 0 && m.timestamp <= Long.MAX_VALUE - m.ttl * 1000) { "Invalid TTL/timestamp" }
                require(m.expiresAt == m.createdAt + m.ttl * 1000) { "Invalid expiration" }
                require(m.hopLimit in 1..255 && m.hopCount in 0..m.hopLimit) { "Invalid hop count/limit" }
                require(m.copiesRemaining in 1..DtnConfig.MAX_SPRAY_COPIES) { "Invalid spray copy budget" }
                require(m.sourceId != m.destinationId) { "Source equals destination" }
                require(m.carrierId == null || (isUuid(m.carrierId) && m.carrierId != m.sourceId && m.carrierId != m.destinationId)) { "Invalid carrier" }
                require(m.status == MessageStatus.PENDING) { "Invalid wire status" }
            }
            PacketType.ACK -> {
                require(packet.messageId?.let(::isUuid) == true) { "Invalid message ACK" }
                require(packet.ackStatus in listOf(MessageStatus.DELIVERED, MessageStatus.CARRIED)) { "Invalid ACK status" }
            }
            PacketType.PREDICTABILITY_SUMMARY -> {
                require(packet.deviceId?.let(::isUuid) == true) { "Invalid routing summary identity" }
                require(packet.message == null && packet.receipt == null && packet.messageId == null) { "Routing summary is metadata only" }
                validateSummary(packet.entries)
            }
            PacketType.MESSAGE_REJECT -> {
                require(packet.messageId?.let(::isUuid) == true && packet.reason != null) { "Invalid MESSAGE rejection" }
                require(packet.message == null) { "Rejection must not include payload" }
            }
            PacketType.MEDIA_MANIFEST -> {
                val m = requireNotNull(packet.message) { "Missing media message" }
                require(m.contentType != MessageContentType.TEXT) { "Manifest requires media message" }
                validate(packet.copy(type = PacketType.MESSAGE, manifest = null))
                val media = requireNotNull(packet.manifest) { "Missing media manifest" }
                validateManifest(media)
                require(media.messageId == m.messageId && media.mediaId == m.mediaId && media.contentType == m.contentType) { "Media/message identity mismatch" }
            }
            PacketType.MEDIA_ACCEPT -> {
                require(packet.messageId?.let(::isUuid) == true && packet.mediaId?.let(::isUuid) == true) { "Invalid media acceptance" }
                val missing = requireNotNull(packet.missingChunks) { "Missing resume state" }
                require(missing.size <= (DtnConfig.MAX_MEDIA_BYTES + DtnConfig.IMAGE_CHUNK_BYTES - 1) / DtnConfig.IMAGE_CHUNK_BYTES &&
                    missing.distinct().size == missing.size && missing.all { it >= 0 }) { "Invalid resume indices" }
            }
            PacketType.MEDIA_CHUNK -> {
                require(packet.messageId?.let(::isUuid) == true && packet.mediaId?.let(::isUuid) == true) { "Invalid chunk identity" }
                require(packet.chunkIndex != null && packet.chunkIndex >= 0 && packet.chunkIndex <
                    (DtnConfig.MAX_MEDIA_BYTES + DtnConfig.IMAGE_CHUNK_BYTES - 1) / DtnConfig.IMAGE_CHUNK_BYTES) { "Invalid chunk index" }
                require(packet.dataLength != null && packet.dataLength in 1..DtnConfig.IMAGE_CHUNK_BYTES &&
                    packet.binaryData?.size == packet.dataLength) { "Invalid chunk bytes" }
                require(packet.message == null && packet.manifest == null) { "Chunk header contains bundle" }
            }
            PacketType.DELIVERY_RECEIPT -> validateReceipt(requireNotNull(packet.receipt))
            PacketType.DELIVERY_RECEIPT_ACK -> require(packet.messageId?.let(::isUuid) == true) { "Invalid receipt ACK" }
            PacketType.PING, PacketType.PONG -> Unit
        }
    }

    fun encode(packet: WirePacket): ByteArray {
        validate(packet)
        val fields = if (packet.type == PacketType.HELLO) {
            JsonObject(buildMap {
                put("type", JsonPrimitive(packet.type.name))
                put("version", JsonPrimitive(packet.version))
                put("deviceId", JsonPrimitive(packet.deviceId))
                packet.displayName?.let { put("displayName", JsonPrimitive(it)) }
                packet.relayEnabled?.let { put("relayEnabled", JsonPrimitive(it)) }
                packet.deviceName?.let { put("deviceName", JsonPrimitive(it)) }
            })
        } else if (packet.type == PacketType.INVENTORY) {
            // No payload, message envelope, or ACK status is advertised in inventory.
            JsonObject(mapOf("type" to JsonPrimitive(packet.type.name), "version" to JsonPrimitive(packet.version),
                "deviceId" to JsonPrimitive(packet.deviceId), "messageIds" to json.encodeToJsonElement(packet.messageIds),
                "deliveredMessageIds" to json.encodeToJsonElement(packet.deliveredMessageIds)))
        } else JsonObject(json.encodeToJsonElement(packet).jsonObject.filterKeys { it != "relayEnabled" })
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
        if (packet.type == PacketType.MEDIA_CHUNK) {
            require(bytes.size <= MAX_CHUNK_HEADER_BYTES) { "Chunk header too large" }
            val binary = requireNotNull(packet.binaryData)
            output.writeInt(BINARY_CHUNK_FRAME)
            output.writeInt(bytes.size)
            output.writeInt(binary.size)
            output.write(bytes)
            output.write(binary)
        } else {
            output.writeInt(bytes.size)
            output.write(bytes)
        }
        output.flush()
    }

    fun read(input: DataInputStream): WirePacket {
        val size = input.readInt()
        if (size == BINARY_CHUNK_FRAME) {
            val headerSize = input.readInt()
            val binarySize = input.readInt()
            require(headerSize in 1..MAX_CHUNK_HEADER_BYTES && binarySize in 1..DtnConfig.IMAGE_CHUNK_BYTES) { "Invalid binary frame length" }
            val header = ByteArray(headerSize)
            val binary = ByteArray(binarySize)
            input.readFully(header)
            input.readFully(binary)
            return decode(header).copy(binaryData = binary).also {
                require(it.type == PacketType.MEDIA_CHUNK) { "Unexpected binary frame type" }
                validate(it)
            }
        }
        require(size in 1..MAX_FRAME_BYTES) { "Invalid frame length: $size" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return decode(bytes).also { if (it.type != PacketType.MEDIA_MANIFEST) validate(it) }
    }

    private fun decode(bytes: ByteArray): WirePacket {
        val fields = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val legacyType = fields["type"]
        val packetType = fields["packetType"]
        require(legacyType != null || packetType != null) { "Missing packet type" }
        require(legacyType == null || packetType == null || legacyType == packetType) { "Conflicting packet types" }
        val normalized = if (legacyType == null) JsonObject(fields + ("type" to requireNotNull(packetType))) else fields
        val safeFields = if ((normalized["type"] as? JsonPrimitive)?.content == PacketType.HELLO.name) {
            JsonObject(normalized.filter { (key, value) ->
                when (key) {
                    "displayName", "deviceName" -> value is JsonPrimitive && value.isString
                    "relayEnabled" -> value is JsonPrimitive && !value.isString && value.content in setOf("true", "false")
                    else -> true
                }
            })
        } else normalized
        return json.decodeFromJsonElement<WirePacket>(safeFields)
    }
}
