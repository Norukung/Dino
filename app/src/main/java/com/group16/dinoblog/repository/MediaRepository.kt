package com.group16.dinoblog.repository

import android.net.Uri
import androidx.room.withTransaction
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.dtn.DtnConfig
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.model.MediaEntity
import com.group16.dinoblog.model.MediaManifest
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.network.MessageRejectReason
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Media storage and resume state share the app's existing Room database and DTN message repository. */
class MediaRepository(
    private val db: DinoblogDatabase,
    private val files: MediaFileStore,
    private val messages: MessageRepository,
    private val logger: NetworkLogger,
    private val relayEnabled: () -> Boolean,
) : MediaStore {
    private val lock = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    override val media: Flow<List<MediaEntity>> = db.media().observeAll()
    override fun filePath(mediaId: String) = files.finalFile(mediaId).absolutePath
    override suspend fun verifiedForPlayback(mediaId: String): Boolean = withContext(Dispatchers.IO) {
        db.media().get(mediaId)?.takeIf { it.isComplete }?.let { files.verified(it.manifest()) } == true
    }

    override suspend fun prepare(uri: Uri, type: MessageContentType): PreparedMedia = withContext(Dispatchers.IO) { files.prepare(uri, type) }
    override suspend fun discardPrepared(prepared: PreparedImage) = withContext(Dispatchers.IO) {
        if (db.media().get(prepared.manifest.mediaId) == null) files.delete(prepared.manifest.mediaId)
    }

    override suspend fun queuePrepared(prepared: PreparedImage, source: String, destination: String,
        at: Long, ttl: Long, hopLimit: Int, copies: Int): Message = lock.withLock {
        require(WireProtocol.isUuid(source) && WireProtocol.isUuid(destination) && source != destination)
        require(withContext(Dispatchers.IO) { files.verified(prepared.manifest) }) { "Prepared image is unavailable" }
        val message = Message(UUID.randomUUID().toString(), source, destination, "", at, ttl,
            MessageStatus.PENDING, hopLimit = hopLimit, copiesRemaining = copies,
            contentType = prepared.manifest.contentType, mediaId = prepared.manifest.mediaId)
        val manifest = prepared.manifest.copy(messageId = message.messageId)
        WireProtocol.validate(WirePacket(PacketType.MEDIA_MANIFEST, message = message, manifest = manifest))
        val row = entity(message, manifest, "1".repeat(manifest.totalChunks), true, at)
        db.withTransaction {
            check(db.media().insert(row) != -1L) { "Image identity already exists" }
            check(messages.insert(message)) { "Could not save image message" }
        }
        logger.log("DTN", "DTN MEDIA PREPARED mediaId=${manifest.mediaId} size=${manifest.fileSize} chunks=${manifest.totalChunks}", message.messageId)
        message
    }

    override suspend fun manifest(messageId: String): MediaManifest? {
        val row = db.media().forMessage(messageId) ?: return null
        if (!row.isComplete || !withContext(Dispatchers.IO) { files.verified(row.manifest()) }) return null
        return row.manifest()
    }

    override suspend fun readChunk(mediaId: String, index: Int): ByteArray? = withContext(Dispatchers.IO) {
        db.media().get(mediaId)?.takeIf { it.isComplete }?.let { files.readChunk(it.manifest(), index) }
    }

    override suspend fun offer(message: Message, manifest: MediaManifest, localId: String,
        at: Long, relayLimit: Int): MediaOffer = lock.withLock {
        if (!WireProtocol.supportsMediaMime(manifest.contentType, manifest.mimeType))
            return@withLock MediaOffer.Reject(MessageRejectReason.UNSUPPORTED_MEDIA)
        if (manifest.fileSize > DtnConfig.maxMediaBytes(manifest.contentType))
            return@withLock MediaOffer.Reject(MessageRejectReason.MEDIA_TOO_LARGE)
        val valid = runCatching { WireProtocol.validate(WirePacket(PacketType.MEDIA_MANIFEST,
            message = message, manifest = manifest)) }.isSuccess
        if (!valid || message.hopCount < 1 || message.hopCount > message.hopLimit ||
            message.createdAt > at + 300_000) return@withLock MediaOffer.Reject(MessageRejectReason.INVALID_MEDIA)
        if (message.destinationId != localId && message.hopCount >= message.hopLimit)
            return@withLock MediaOffer.Reject(MessageRejectReason.HOP_LIMIT)
        if (at >= message.expiresAt) return@withLock MediaOffer.Reject(MessageRejectReason.EXPIRED)
        val existing = db.media().forMessage(message.messageId)
        if (existing != null) {
            val savedMessage = runCatching { json.decodeFromString<Message>(existing.messageJson) }.getOrNull()
            if (existing.mediaId != manifest.mediaId || existing.manifest() != manifest ||
                savedMessage == null || !DeliveryEngine.sameBundle(savedMessage, message))
                return@withLock MediaOffer.Reject(MessageRejectReason.INVALID_MEDIA)
            if (existing.isComplete && files.verified(manifest)) return@withLock MediaOffer.Have
        }
        val known = messages.receipt(message.messageId)
        if (known != null) return@withLock MediaOffer.Reject(MessageRejectReason.ALREADY_DELIVERED)
        val stored = messages.get(message.messageId)
        if (message.sourceId == localId && stored == null)
            return@withLock MediaOffer.Reject(MessageRejectReason.INVALID_MEDIA)
        if (stored != null && !DeliveryEngine.sameBundle(stored, message))
            return@withLock MediaOffer.Reject(MessageRejectReason.INVALID_MEDIA)
        if (message.destinationId != localId && message.sourceId != localId && !relayEnabled())
            return@withLock MediaOffer.Reject(MessageRejectReason.RELAY_DISABLED)
        val additional = if (existing == null) manifest.fileSize else
            (manifest.fileSize - files.partFile(manifest.mediaId).length()).coerceAtLeast(0)
        if (!files.hasSpace(additional)) return@withLock MediaOffer.Reject(MessageRejectReason.BUFFER_FULL)
        if (existing == null) {
            val admitted = db.withTransaction {
                val relay = message.destinationId != localId && message.sourceId != localId
                if (relay) {
                    val bytes = db.media().relayBytes(localId, at)
                    val count = messages.relayBufferUsage(localId, at) + db.media().partialRelayCount(localId, at)
                    if (bytes + manifest.fileSize > DtnConfig.MAX_RELAY_MEDIA_BYTES || count >= relayLimit || !relayEnabled()) return@withTransaction false
                }
                db.media().insert(entity(message, manifest, "0".repeat(manifest.totalChunks), false, at)) != -1L
            }
            if (!admitted) return@withLock MediaOffer.Reject(if (!relayEnabled()) MessageRejectReason.RELAY_DISABLED else MessageRejectReason.BUFFER_FULL)
        }
        val current = db.media().get(manifest.mediaId) ?: return@withLock MediaOffer.Reject(MessageRejectReason.INVALID_MEDIA)
        if (current.isComplete && !files.verified(manifest)) {
            files.delete(manifest.mediaId)
            db.media().update(current.copy(isComplete = false, receivedChunks = "0".repeat(manifest.totalChunks), lastUpdatedAt = at))
        }
        val partial = files.partFile(manifest.mediaId)
        if (current.receivedChunks.all { it == '1' } && !current.isComplete) {
            partial.delete()
            db.media().update(current.copy(receivedChunks = "0".repeat(current.totalChunks), lastUpdatedAt = at))
        }
        if (!partial.exists()) {
            if (!runCatching { partial.createNewFile() }.getOrDefault(false) && !partial.exists()) {
                if (existing == null) db.media().delete(manifest.mediaId)
                return@withLock MediaOffer.Reject(MessageRejectReason.BUFFER_FULL)
            }
            db.media().get(manifest.mediaId)?.let {
                if (it.receivedChunks.contains('1')) db.media().update(it.copy(receivedChunks = "0".repeat(it.totalChunks), lastUpdatedAt = at))
            }
        }
        // A different complete carrier may finish this partial file. Its successful
        // branch metadata (hop and copies) must be the metadata committed with ACK.
        db.media().get(manifest.mediaId)?.let {
            if (!it.isComplete) db.media().update(it.copy(messageJson = json.encodeToString(message), lastUpdatedAt = at))
        }
        val state = db.media().get(manifest.mediaId) ?: return@withLock MediaOffer.Reject(MessageRejectReason.INVALID_MEDIA)
        logger.log("DTN", "DTN MEDIA MANIFEST ACCEPT mediaId=${manifest.mediaId} missing=${state.totalChunks - state.completedChunks}", message.messageId)
        MediaOffer.Accept(state.receivedChunks.indices.filter { state.receivedChunks[it] == '0' })
    }

    override suspend fun receiveChunk(messageId: String, mediaId: String, index: Int, bytes: ByteArray,
        localId: String, at: Long, relayLimit: Int): MediaChunkResult = lock.withLock {
        val row = db.media().get(mediaId) ?: return@withLock MediaChunkResult.Reject(MessageRejectReason.INVALID_MEDIA)
        if (row.messageId != messageId || row.isComplete || index !in 0 until row.totalChunks)
            return@withLock MediaChunkResult.Reject(MessageRejectReason.INVALID_MEDIA)
        if (at >= row.expiresAt || messages.receipt(messageId) != null)
            return@withLock MediaChunkResult.Reject(MessageRejectReason.EXPIRED)
        val offset = index.toLong() * row.chunkSize
        if (bytes.size != minOf(row.chunkSize.toLong(), row.fileSize - offset).toInt())
            return@withLock MediaChunkResult.Reject(MessageRejectReason.INVALID_MEDIA)
        if (row.receivedChunks[index] == '1') return@withLock MediaChunkResult.Progress(row.completedChunks, row.totalChunks)
        val written = runCatching { withContext(Dispatchers.IO) { files.writeChunk(row.manifest(), index, bytes) } }.isSuccess
        if (!written) return@withLock MediaChunkResult.Reject(MessageRejectReason.BUFFER_FULL)
        val bits = row.receivedChunks.toCharArray().apply { this[index] = '1' }.concatToString()
        val updated = row.copy(receivedChunks = bits, lastUpdatedAt = at)
        db.media().update(updated)
        if (updated.completedChunks < updated.totalChunks) return@withLock MediaChunkResult.Progress(updated.completedChunks, updated.totalChunks)
        if (!withContext(Dispatchers.IO) { files.finish(updated.manifest()) }) {
            files.delete(mediaId)
            db.media().delete(mediaId)
            logger.log("DTN", "DTN MEDIA VERIFICATION FAILED mediaId=$mediaId; incomplete transfer removed", messageId)
            return@withLock MediaChunkResult.Reject(MessageRejectReason.INVALID_MEDIA)
        }
        val message = json.decodeFromString<Message>(updated.messageJson)
        val stored = message.copy(status = if (message.destinationId == localId) MessageStatus.RECEIVED else MessageStatus.CARRIED)
        val receipt = if (message.destinationId == localId) DeliveryReceipt.create(stored, localId, at) else null
        try {
            db.withTransaction {
                db.media().update(updated.copy(isComplete = true))
                messages.commitReceived(stored, receipt, localId, at, relayLimit)
            }
        } catch (_: RelayDisabledException) {
            files.delete(mediaId); db.media().delete(mediaId)
            return@withLock MediaChunkResult.Reject(MessageRejectReason.RELAY_DISABLED)
        } catch (_: RelayBufferFullException) {
            files.delete(mediaId); db.media().delete(mediaId)
            return@withLock MediaChunkResult.Reject(MessageRejectReason.BUFFER_FULL)
        }
        logger.log("DTN", "DTN MEDIA HASH VERIFIED mediaId=$mediaId", messageId)
        MediaChunkResult.Complete(stored)
    }

    override suspend fun cleanup(localId: String, at: Long): Int = lock.withLock { cleanupLocked(localId, at) }
    private suspend fun cleanupLocked(localId: String, at: Long): Int {
        var removed = 0
        for (row in db.media().all()) {
            val relay = row.sourceId != localId && row.destinationId != localId
            val receipt = messages.receipt(row.messageId) != null
            val message = messages.get(row.messageId)
            val blocked = message?.let { it.hopCount >= it.hopLimit } == true
            val emptyAndIdle = !row.isComplete && row.completedChunks == 0 &&
                at - row.lastUpdatedAt >= DtnConfig.EMPTY_MEDIA_PART_TIMEOUT_MS
            if ((relay && (receipt || at >= row.expiresAt || blocked || (row.isComplete && message == null))) ||
                (!row.isComplete && (receipt || at >= row.expiresAt || emptyAndIdle))) {
                files.delete(row.mediaId)
                db.media().delete(row.mediaId)
                logger.log("DTN", "DTN MEDIA PAYLOAD RECLAIMED mediaId=${row.mediaId}", row.messageId)
                removed++
            }
        }
        return removed
    }

    override suspend fun recover(localId: String, at: Long) = lock.withLock {
        val rows = db.media().all()
        for (row in rows) {
            if (row.isComplete && !files.verified(row.manifest())) {
                files.delete(row.mediaId)
                db.media().update(row.copy(isComplete = false, receivedChunks = "0".repeat(row.totalChunks), lastUpdatedAt = at))
            } else if (!row.isComplete) {
                if (files.finalFile(row.mediaId).exists() && files.verified(row.manifest())) {
                    val message = json.decodeFromString<Message>(row.messageJson)
                    val stored = message.copy(status = if (message.destinationId == localId) MessageStatus.RECEIVED else MessageStatus.CARRIED)
                    val receipt = if (message.destinationId == localId && at < message.expiresAt) DeliveryReceipt.create(stored, localId, at) else null
                    runCatching {
                        db.withTransaction {
                            db.media().update(row.copy(isComplete = true, receivedChunks = "1".repeat(row.totalChunks)))
                            messages.commitReceived(stored, receipt, localId, at, DtnConfig.MAX_RELAY_BUFFER_MESSAGES)
                        }
                    }.onFailure { logger.log("DTN", "DTN MEDIA RECOVERY PENDING mediaId=${row.mediaId}", row.messageId) }
                } else if (!files.partFile(row.mediaId).exists()) {
                    // Nothing remains to resume. A future manifest can create a fresh row.
                    files.delete(row.mediaId)
                    db.media().delete(row.mediaId)
                }
            }
        }
        cleanupLocked(localId, at)
        val known = db.media().all().map { it.mediaId.lowercase() }.toSet()
        files.directory.listFiles()?.filter { file ->
            val id = file.name.substringBeforeLast('.')
            file.extension in setOf("part", "media") && id !in known
        }?.forEach { it.delete() }
        Unit
    }

    override suspend fun relayBytes(localId: String, at: Long) = db.media().relayBytes(localId, at)

    private fun entity(message: Message, manifest: MediaManifest, bits: String, complete: Boolean, at: Long) =
        MediaEntity(manifest.mediaId, message.messageId, message.sourceId, message.destinationId,
            manifest.mimeType, manifest.originalFileName, manifest.fileSize, manifest.sha256,
            manifest.chunkSize, manifest.totalChunks, bits, complete, message.createdAt,
            message.expiresAt, json.encodeToString(message), at,
            manifest.contentType, manifest.durationMs, manifest.width, manifest.height)
}
