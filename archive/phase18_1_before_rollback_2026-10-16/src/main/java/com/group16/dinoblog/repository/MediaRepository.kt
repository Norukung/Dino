package com.group16.dinoblog.repository

import android.net.Uri
import androidx.room.withTransaction
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.dtn.DtnConfig
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.model.MediaEntity
import com.group16.dinoblog.model.MediaManifest
import com.group16.dinoblog.model.MediaPolicy
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.RelayPolicy
import com.group16.dinoblog.model.RelayPolicyRules
import com.group16.dinoblog.network.MessageRejectReason
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.util.NetworkLogger
import com.group16.dinoblog.security.CryptoIdentityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.FileOutputStream
import java.util.UUID

/** Media storage and resume state share the app's existing Room database and DTN message repository. */
class MediaRepository(
    private val db: DinoblogDatabase,
    private val files: MediaFileStore,
    private val messages: MessageRepository,
    private val logger: NetworkLogger,
    private val security: CryptoIdentityManager? = null,
    private val relayEnabled: () -> Boolean,
) : MediaStore {
    private val lock = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    var onHashFailure: (String, String) -> Unit = { _, _ -> }
    var onChunkRetransmitted: (String, String, Long) -> Unit = { _, _, _ -> }
    override val media: Flow<List<MediaEntity>> = db.media().observeAll()
    override fun filePath(mediaId: String) = files.finalFile(mediaId).absolutePath

    /** Trusted tombstone cleanup; source history and complete destination content remain. */
    suspend fun cleanupRevoked(messageId: String, localId: String): Long = lock.withLock {
        val row = db.media().forMessage(messageId) ?: return@withLock 0L
        val deliveredHere = row.destinationId == localId &&
            (db.messages().get(messageId)?.status == MessageStatus.RECEIVED || db.receipts().get(messageId) != null)
        if (row.sourceId == localId || deliveredHere) return@withLock 0L
        val reclaimedBytes = if (row.isComplete) row.fileSize else files.partFile(row.mediaId).length()
        files.delete(row.mediaId)
        db.media().delete(row.mediaId)
        logger.log("DTN", "REVOKE PARTIAL/RELAY MEDIA CLEANED", messageId)
        reclaimedBytes
    }

    override suspend fun prepare(uri: Uri): PreparedImage = withContext(Dispatchers.IO) { files.prepare(uri) }
    override suspend fun prepare(uri: Uri, type: MessageContentType): PreparedImage = withContext(Dispatchers.IO) {
        if (type == MessageContentType.IMAGE) files.prepare(uri) else files.prepare(uri, type)
    }
    override suspend fun discardPrepared(prepared: PreparedImage) = withContext(Dispatchers.IO) {
        if (db.media().get(prepared.manifest.mediaId) == null) files.delete(prepared.manifest.mediaId)
    }

    override suspend fun queuePrepared(prepared: PreparedImage, source: String, destination: String,
        at: Long, ttl: Long, hopLimit: Int, copies: Int, relayPolicy: RelayPolicy): Message = lock.withLock {
        require(WireProtocol.isUuid(source) && WireProtocol.isUuid(destination) && source != destination)
        require(withContext(Dispatchers.IO) { files.verified(prepared.manifest) }) { "Prepared media is unavailable" }
        require(prepared.manifest.mediaType != MessageContentType.TEXT)
        val unsigned = Message(UUID.randomUUID().toString(), source, destination, "", at, ttl,
            MessageStatus.PENDING, hopLimit = hopLimit, copiesRemaining = copies,
            contentType = prepared.manifest.mediaType, mediaId = prepared.manifest.mediaId,
            relayPolicy = relayPolicy)
        val manifest = prepared.manifest.copy(messageId = unsigned.messageId)
        val message = security?.signMessage(unsigned, manifest) ?: unsigned
        WireProtocol.validate(WirePacket(PacketType.MESSAGE, message = message))
        WireProtocol.validateManifest(manifest, allowOnlineOnly = true)
        val row = entity(message, manifest, "1".repeat(manifest.totalChunks), true, at)
        db.withTransaction {
            check(db.media().insert(row) != -1L) { "Media identity already exists" }
            check(messages.insert(message)) { "Could not save media message" }
        }
        logger.log("DTN", "DTN MEDIA PREPARED mediaId=${manifest.mediaId} size=${manifest.fileSize} chunks=${manifest.totalChunks}", message.messageId)
        message
    }

    override suspend fun manifest(messageId: String): MediaManifest? {
        val row = db.media().forMessage(messageId) ?: return null
        if (!row.isComplete || !files.finalFile(row.mediaId).isFile ||
            files.finalFile(row.mediaId).length() != row.fileSize) return null
        return row.manifest()
    }

    override suspend fun dtnEligible(messageId: String): Boolean {
        val row = db.media().forMessage(messageId) ?: return false
        return MediaPolicy.dtnAllowed(row.mediaType, row.fileSize)
    }

    override suspend fun readChunk(mediaId: String, index: Int): ByteArray? = withContext(Dispatchers.IO) {
        db.media().get(mediaId)?.takeIf { it.isComplete }?.let { files.readChunk(it.manifest(), index) }
    }

    override suspend fun offer(message: Message, manifest: MediaManifest, localId: String,
        at: Long, relayLimit: Int, authenticatedPeerId: String?): MediaOffer = lock.withLock {
        if (db.recalls().revoke(message.messageId)?.verificationState == "VERIFIED")
            return@withLock MediaOffer.Reject(MessageRejectReason.MESSAGE_REVOKED)
        if (!RelayPolicyRules.mayAcceptFromPeer(message, authenticatedPeerId.orEmpty(), localId))
            return@withLock MediaOffer.Reject(MessageRejectReason.RELAY_POLICY_DIRECT_ONLY)
        if (manifest.mimeType !in MediaPolicy.mimes(manifest.mediaType) || message.contentType != manifest.mediaType)
            return@withLock MediaOffer.Reject(MessageRejectReason.UNSUPPORTED_MEDIA)
        if (manifest.fileSize > MediaPolicy.dtnMax(manifest.mediaType))
            return@withLock MediaOffer.Reject(MessageRejectReason.MEDIA_TOO_LARGE)
        val valid = runCatching { WireProtocol.validate(WirePacket(PacketType.MEDIA_MANIFEST,
            message = message, manifest = manifest)) }.isSuccess
        if (!valid || message.hopCount < 1 || message.hopCount > message.hopLimit ||
            message.createdAt > at + 300_000) return@withLock MediaOffer.Reject(MessageRejectReason.INVALID_MEDIA)
        val identity = security
        if (identity != null) {
            val trust = runCatching { identity.verifyMessage(message, manifest,
                acceptFirstContact = message.destinationId == localId) }.getOrNull()
                ?: return@withLock MediaOffer.Reject(MessageRejectReason.INVALID_MEDIA)
            if (message.destinationId == localId && trust == "UNKNOWN")
                return@withLock MediaOffer.Reject(MessageRejectReason.INVALID_MEDIA)
        }
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
                if (db.recalls().revoke(message.messageId)?.verificationState == "VERIFIED") return@withTransaction false
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
        localId: String, at: Long, relayLimit: Int, authenticatedPeerId: String?): MediaChunkResult = lock.withLock {
        if (db.recalls().revoke(messageId)?.verificationState == "VERIFIED")
            return@withLock MediaChunkResult.Reject(MessageRejectReason.MESSAGE_REVOKED)
        val row = db.media().get(mediaId) ?: return@withLock MediaChunkResult.Reject(MessageRejectReason.INVALID_MEDIA)
        if (row.messageId != messageId || row.isComplete || index !in 0 until row.totalChunks)
            return@withLock MediaChunkResult.Reject(MessageRejectReason.INVALID_MEDIA)
        val offeredMessage = runCatching { json.decodeFromString<Message>(row.messageJson) }.getOrNull()
            ?: return@withLock MediaChunkResult.Reject(MessageRejectReason.INVALID_MEDIA)
        if (!RelayPolicyRules.mayAcceptFromPeer(offeredMessage, authenticatedPeerId.orEmpty(), localId))
            return@withLock MediaChunkResult.Reject(MessageRejectReason.RELAY_POLICY_DIRECT_ONLY)
        if (at >= row.expiresAt || messages.receipt(messageId) != null)
            return@withLock MediaChunkResult.Reject(MessageRejectReason.EXPIRED)
        val offset = index.toLong() * row.chunkSize
        if (bytes.size != minOf(row.chunkSize.toLong(), row.fileSize - offset).toInt())
            return@withLock MediaChunkResult.Reject(MessageRejectReason.INVALID_MEDIA)
        if (row.receivedChunks[index] == '1') {
            runCatching { onChunkRetransmitted(messageId, mediaId, bytes.size.toLong()) }
            return@withLock MediaChunkResult.Progress(row.completedChunks, row.totalChunks)
        }
        val written = runCatching { withContext(Dispatchers.IO) { files.writeChunk(row.manifest(), index, bytes) } }.isSuccess
        if (!written) return@withLock MediaChunkResult.Reject(MessageRejectReason.BUFFER_FULL)
        val bits = row.receivedChunks.toCharArray().apply { this[index] = '1' }.concatToString()
        val updated = row.copy(receivedChunks = bits, lastUpdatedAt = at)
        db.media().update(updated)
        if (updated.completedChunks < updated.totalChunks) return@withLock MediaChunkResult.Progress(updated.completedChunks, updated.totalChunks)
        if (!withContext(Dispatchers.IO) { files.finish(updated.manifest()) }) {
            files.partFile(mediaId).delete()
            db.media().update(updated.copy(receivedChunks = "0".repeat(updated.totalChunks)))
            logger.log("DTN", "DTN MEDIA HASH FAILED mediaId=$mediaId", messageId)
            runCatching { onHashFailure(messageId, mediaId) }
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
        } catch (_: MessageRevokedException) {
            files.delete(mediaId); db.media().delete(mediaId)
            return@withLock MediaChunkResult.Reject(MessageRejectReason.MESSAGE_REVOKED)
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
            if ((relay && (receipt || at >= row.expiresAt || blocked || (row.isComplete && message == null))) ||
                (!row.isComplete && (receipt || at >= row.expiresAt))) {
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
                } else if (!files.partFile(row.mediaId).exists() && row.receivedChunks.contains('1')) {
                    db.media().update(row.copy(receivedChunks = "0".repeat(row.totalChunks), lastUpdatedAt = at))
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

    /** Online bytes finalize through the same Phase 11 hash and image checks. */
    suspend fun hasVerified(manifest: MediaManifest): Boolean =
        db.media().get(manifest.mediaId)?.manifest() == manifest &&
            withContext(Dispatchers.IO) { files.verified(manifest) }

    /** Online range downloads and DTN chunks share the same row, .part file and mutex. */
    suspend fun prepareOnline(message: Message, manifest: MediaManifest, localId: String, at: Long): List<Int> = lock.withLock {
        require(db.recalls().revoke(message.messageId)?.verificationState != "VERIFIED") { "MESSAGE_REVOKED" }
        require(message.destinationId == localId && message.mediaId == manifest.mediaId &&
            message.messageId == manifest.messageId && message.contentType == manifest.mediaType)
        WireProtocol.validateManifest(manifest, allowOnlineOnly = true)
        val old = db.media().get(manifest.mediaId)
        require(old == null || old.manifest() == manifest) { "Conflicting media identity" }
        require(withContext(Dispatchers.IO) {
            files.hasSpace((manifest.fileSize - files.partFile(manifest.mediaId).length()).coerceAtLeast(0))
        }) { "Not enough storage" }
        if (old == null) {
            check(db.media().insert(entity(message, manifest, "0".repeat(manifest.totalChunks), false, at)) != -1L)
        }
        val current = db.media().get(manifest.mediaId) ?: error("Missing media")
        if (current.isComplete && files.verified(manifest)) return@withLock emptyList()
        if (!files.partFile(manifest.mediaId).exists()) {
            files.partFile(manifest.mediaId).createNewFile()
            if (current.receivedChunks.contains('1')) db.media().update(current.copy(receivedChunks = "0".repeat(current.totalChunks)))
        }
        val refreshed = db.media().get(manifest.mediaId) ?: error("Missing media")
        refreshed.receivedChunks.indices.filter { refreshed.receivedChunks[it] == '0' }
    }

    suspend fun receiveOnline(message: Message, manifest: MediaManifest, bytes: ByteArray?, localId: String, at: Long): Boolean = lock.withLock {
        if (db.recalls().revoke(message.messageId)?.verificationState == "VERIFIED") return@withLock false
        if (security != null) {
            val trust = security.verifyMessage(message, manifest)
            require(trust != "UNKNOWN") { "Source identity not verified" }
        }
        if (message.destinationId != localId || message.mediaId != manifest.mediaId ||
            manifest.messageId != message.messageId ||
            at >= message.expiresAt) return@withLock false
        if (manifest.mediaType != message.contentType ||
            runCatching { WireProtocol.validateManifest(manifest, allowOnlineOnly = true) }.isFailure) return@withLock false
        val old = db.media().get(manifest.mediaId)
        if (old != null && old.manifest() != manifest) return@withLock false
        if (old != null && files.verified(manifest)) {
            val stored = message.copy(status = MessageStatus.RECEIVED)
            db.withTransaction {
                if (!old.isComplete) db.media().update(old.copy(isComplete = true,
                    receivedChunks = "1".repeat(old.totalChunks), lastUpdatedAt = at))
                messages.commitReceived(stored, DeliveryReceipt.create(stored, localId, at), localId, at, DtnConfig.MAX_RELAY_BUFFER_MESSAGES)
            }
            files.partFile(manifest.mediaId).delete()
            return@withLock true
        }
        if (bytes == null || bytes.size.toLong() != manifest.fileSize) return@withLock false
        if (!withContext(Dispatchers.IO) { files.hasSpace(bytes.size.toLong()) }) return@withLock false
        val verified = withContext(Dispatchers.IO) {
            FileOutputStream(files.partFile(manifest.mediaId)).use { it.write(bytes); it.fd.sync() }
            files.finish(manifest)
        }
        if (!verified) {
            files.partFile(manifest.mediaId).delete()
            runCatching { onHashFailure(message.messageId, manifest.mediaId) }
            return@withLock false
        }
        val stored = message.copy(status = MessageStatus.RECEIVED)
        val row = entity(stored, manifest, "1".repeat(manifest.totalChunks), true, at)
        db.withTransaction {
            if (old == null) db.media().insert(row) else db.media().update(row)
            messages.commitReceived(stored, DeliveryReceipt.create(stored, localId, at), localId, at, DtnConfig.MAX_RELAY_BUFFER_MESSAGES)
        }
        true
    }

    private fun entity(message: Message, manifest: MediaManifest, bits: String, complete: Boolean, at: Long) =
        MediaEntity(manifest.mediaId, message.messageId, message.sourceId, message.destinationId,
            manifest.mimeType, manifest.originalFileName, manifest.fileSize, manifest.sha256,
            manifest.chunkSize, manifest.totalChunks, bits, complete, message.createdAt,
            message.expiresAt, json.encodeToString(message), at, manifest.mediaType,
            manifest.durationMs, manifest.width, manifest.height)
}
