package com.group16.dinoblog.online

import android.content.Context
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.dtn.DtnConfig
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.model.MediaManifest
import com.group16.dinoblog.model.MediaPolicy
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.RelayPolicy
import com.group16.dinoblog.model.OnlineMessageState
import com.group16.dinoblog.model.Revoke
import com.group16.dinoblog.model.RevokeOutcome
import com.group16.dinoblog.model.RevokeResult
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.repository.MediaRepository
import com.group16.dinoblog.repository.MessageRepository
import com.group16.dinoblog.repository.RecallRepository
import com.group16.dinoblog.util.NetworkLogger
import com.group16.dinoblog.experiment.ExperimentManager
import com.group16.dinoblog.security.CryptoIdentityManager
import com.group16.dinoblog.security.CanonicalSignatures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.math.min
import java.io.RandomAccessFile

data class ServerState(
    val status: String = "DISCONNECTED",
    val serverUrl: String = "",
    val authenticatedDevice: String? = null,
    val cursor: Long = 0,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val webSocketState: String = "DISCONNECTED",
    val lastTransport: String = "DTN",
)

/** Mailbox sync sits beside DTN. All bundle and receipt writes use the existing repositories. */
class TransportCoordinator(context: Context, private val localId: String, private val displayName: () -> String,
    private val db: DinoblogDatabase, private val messages: MessageRepository,
    private val media: MediaRepository, private val scope: CoroutineScope, private val logger: NetworkLogger,
    private val metrics: ExperimentManager? = null,
    private val security: CryptoIdentityManager? = null,
    private val recalls: RecallRepository? = null) {
    private val api = OnlineApi(context, metrics)
    private val appContext = context.applicationContext
    private val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow(ServerState(serverUrl = api.baseUrl, cursor = api.cursor))
    val state = mutableState.asStateFlow()
    private val mutableMediaProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val mediaProgress = mutableMediaProgress.asStateFlow()
    val onlineMessages = db.onlineMessages().observeAll()
    private var loop: Job? = null
    private var webSocketJob: Job? = null
    @Volatile private var keyLookupUnavailableUntil: Long = 0
    private val webSocket = OnlineWebSocket()

    fun setDebugUrl(url: String) {
        webSocket.close()
        api.setDebugUrl(url)
        mutableState.value = ServerState(serverUrl = api.baseUrl)
        wake()
    }

    fun start() {
        if (loop?.isActive == true) return
        scheduleBackgroundSync()
        loop = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                val success = runCatching { syncOnce() }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    false
                }
                delay(if (success) 15_000L else backoff.also { backoff = min(it * 2, 30_000L) })
                if (success) backoff = 1_000L
            }
        }
        webSocketJob = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                if (state.value.status != "ONLINE" || api.token == null) {
                    delay(1_000L)
                    continue
                }
                val connectionStartedAt = System.currentTimeMillis()
                try {
                    mutableState.value = mutableState.value.copy(webSocketState = "CONNECTING")
                    webSocket.listen(api.baseUrl, requireNotNull(api.token)) {
                        mutableState.value = mutableState.value.copy(webSocketState = "CONNECTED")
                        wake()
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* REST polling remains authoritative. */ }
                finally {
                    if (System.currentTimeMillis() - connectionStartedAt >= 30_000L) backoff = 1_000L
                    mutableState.value = mutableState.value.copy(webSocketState = "DISCONNECTED")
                }
                delay(backoff)
                backoff = min(backoff * 2, 30_000L)
            }
        }
    }

    fun stop() {
        loop?.cancel(); loop = null
        webSocket.close(); webSocketJob?.cancel(); webSocketJob = null
        mutableState.value = mutableState.value.copy(status = "DISCONNECTED", webSocketState = "DISCONNECTED")
    }

    fun wake() {
        scheduleBackgroundSync()
        scope.launch { syncOnce() }
    }

    private fun scheduleBackgroundSync() {
        if (api.baseUrl.isBlank()) return
        runCatching { OnlineSyncJob.schedule(appContext) }.onFailure { error ->
            // An OS scheduler failure must not stop foreground REST sync or DTN.
            logger.log("ONLINE", "Background sync unavailable: ${error.javaClass.simpleName}")
        }
    }

    suspend fun tryUploadFirst(message: Message): Boolean {
        if (api.baseUrl.isBlank()) return false
        OnlineSyncJob.schedule(appContext)
        withContext(Dispatchers.IO) { syncOnce() }
        return accepted(message.messageId)
    }

    suspend fun accepted(id: String): Boolean = db.onlineMessages().get(id)?.state == "SERVER_ACCEPTED"

    suspend fun syncOnce(): Boolean = lock.withLock {
        if (api.baseUrl.isBlank()) {
            mutableState.value = mutableState.value.copy(status = "DISCONNECTED", lastError = "Configure server URL")
            return@withLock false
        }
        val wasOnline = mutableState.value.status == "ONLINE"
        mutableState.value = mutableState.value.copy(status = if (wasOnline) "ONLINE" else "CONNECTING",
            serverUrl = api.baseUrl)
        try {
            register()
            bindIdentity()
            val health = api.json("GET", "/api/v1/health")
            require(health["ok"]?.jsonPrimitive?.content == "true" &&
                health["deviceId"]?.jsonPrimitive?.content == localId) { "Server authentication mismatch" }
            mutableState.value = mutableState.value.copy(status = "ONLINE", authenticatedDevice = localId, lastError = null)
            if (!wasOnline) {
                logger.log("ONLINE", "ONLINE SERVER CONNECTED")
                logger.log("ONLINE", "TRANSPORT SWITCH TO ONLINE")
            }
            recheckPendingReceipts()
            recalls?.revalidatePending()
            uploadRevokes()
            uploadOwnMessages()
            synchronize()
            uploadRevokeResults()
            uploadReceipts()
            lookupHeldReceipts()
            lookupHeldRevokes()
            mutableState.value = mutableState.value.copy(cursor = api.cursor, lastSyncAt = System.currentTimeMillis())
            true
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val unavailable = error is java.io.IOException ||
                (error is OnlineHttpException && (error.status >= 500 || error.status == 401))
            mutableState.value = mutableState.value.copy(status = if (unavailable) "DISCONNECTED" else "DEGRADED",
                lastError = error.message?.take(120), lastTransport = "DTN")
            logger.log("ONLINE", "ONLINE SERVER DISCONNECTED ${error.javaClass.simpleName}")
            logger.log("ONLINE", "TRANSPORT FALLBACK TO DTN")
            false
        }
    }

    private fun register() {
        val token = api.token
        val registration = if (token == null && security != null) {
            val challenge = api.json("POST", "/api/v1/devices/registration-challenge",
                buildJsonObject { put("deviceId", localId) }.toString(), authenticated = false).string("challenge")
            val proof = security.sign(CanonicalSignatures.registration(localId, challenge, security.fingerprint))
            Triple(challenge, proof, security)
        } else null
        val payload = buildJsonObject {
            put("deviceId", localId); put("displayName", displayName()); put("appVersion", appVersion)
            registration?.let { (challenge, proof, identity) ->
                put("challenge", challenge); put("signatureVersion", 1)
                put("publicKey", identity.publicKey); put("publicKeyFingerprint", identity.fingerprint)
                put("proofSignature", proof)
            }
        }.toString()
        val response = api.json("POST", "/api/v1/devices/register", payload, authenticated = token != null)
        response["token"]?.jsonPrimitive?.content?.let(api::storeToken)
    }

    private fun bindIdentity() {
        val identity = security ?: return
        try {
            val existing = api.json("GET", "/api/v1/devices/$localId/key")
            require(existing.string("publicKeyFingerprint") == identity.fingerprint) { "IDENTITY_KEY_MISMATCH" }
            return
        } catch (error: OnlineHttpException) {
            if (error.status != 404) throw error
        }
        val challenge = api.json("POST", "/api/v1/devices/key-challenge")
        val nonce = challenge.string("challenge")
        val proof = identity.sign(CanonicalSignatures.binding(localId, nonce, identity.fingerprint))
        api.json("POST", "/api/v1/devices/key-binding", buildJsonObject {
            put("publicKey", identity.publicKey); put("publicKeyFingerprint", identity.fingerprint)
            put("challenge", nonce); put("signatureVersion", 1); put("proofSignature", proof)
        }.toString())
    }

    /** A null result means the server is unavailable or has no binding; local pinning still applies. */
    suspend fun serverBinding(deviceId: String): String? {
        if (!WireProtocol.isUuid(deviceId) || api.token == null || api.baseUrl.isBlank()) return null
        if (state.value.status != "ONLINE" && System.currentTimeMillis() < keyLookupUnavailableUntil) return null
        return try { api.json("GET", "/api/v1/devices/$deviceId/key")["publicKey"]?.jsonPrimitive?.content }
        catch (error: OnlineHttpException) {
            if (error.status != 404) keyLookupUnavailableUntil = System.currentTimeMillis() + 10_000
            null
        }
        catch (_: Exception) {
            keyLookupUnavailableUntil = System.currentTimeMillis() + 10_000
            null
        }
    }

    private suspend fun trustServerIdentity(deviceId: String) {
        val identity = security ?: return
        val key = serverBinding(deviceId) ?: return
        require(identity.pin(deviceId, key, "SERVER") != "KEY_MISMATCH") { "IDENTITY_KEY_MISMATCH" }
        messages.revalidatePendingReceipts(deviceId)
        recalls?.revalidatePending(deviceId)
    }

    private suspend fun recheckPendingReceipts() {
        val destinations = db.identities().pending().mapNotNull { pending ->
            runCatching { json.decodeFromString<DeliveryReceipt>(pending.receiptJson).destinationId }.getOrNull()
        }.distinct().take(100)
        for (destination in destinations) trustServerIdentity(destination)
    }

    private suspend fun uploadOwnMessages() {
        for (message in messages.messages.first()) {
            if (recalls?.revoked(message.messageId) == true) continue
            if (message.sourceId != localId || message.status in setOf(MessageStatus.DELIVERED, MessageStatus.EXPIRED) ||
                message.expiresAt <= System.currentTimeMillis() || messages.receipt(message.messageId) != null ||
                accepted(message.messageId) || (security != null && message.signatureVersion !in setOf(1, 2))) continue
            if (security?.destinationBlocked(message.destinationId) == true) continue
            if (recalls?.markAttempted(message.messageId) == false) continue
            try {
                val uploadStarted = System.currentTimeMillis()
                metrics?.record("ONLINE_UPLOAD_STARTED", message, transport = "ONLINE")
                db.onlineMessages().save(OnlineMessageState(message.messageId, "PENDING_UPLOAD"))
                logger.log("ONLINE", "ONLINE MESSAGE QUEUED", message.messageId)
                if (message.contentType != MessageContentType.TEXT) uploadMedia(message)
                if (recalls?.revoked(message.messageId) == true) {
                    mutableMediaProgress.value = mutableMediaProgress.value - message.messageId
                    continue
                }
                val response = api.json("POST", "/api/v1/messages", buildJsonObject {
                    put("messageId", message.messageId); put("sourceId", message.sourceId)
                    put("destinationId", message.destinationId); put("contentType", message.contentType.name)
                    put("payload", message.payload); message.mediaId?.let { put("mediaId", it) }
                    put("createdAt", message.createdAt); put("expiresAt", message.expiresAt)
                    put("hopLimit", message.hopLimit)
                    put("relayPolicy", message.relayPolicy.name)
                    message.signatureVersion?.let { put("signatureVersion", it) }
                    message.originPublicKey?.let { put("originPublicKey", it) }
                    message.originPublicKeyFingerprint?.let { put("originPublicKeyFingerprint", it) }
                    message.contentDigest?.let { put("contentDigest", it) }
                    message.originSignature?.let { put("originSignature", it) }
                    message.signedMediaMimeType?.let { put("signedMediaMimeType", it) }
                    message.signedMediaFileSize?.let { put("signedMediaFileSize", it) }
                    message.signedMediaSha256?.let { put("signedMediaSha256", it) }
                }.toString())
                require(response["status"]?.jsonPrimitive?.content == "SERVER_ACCEPTED")
                db.onlineMessages().save(OnlineMessageState(message.messageId, "SERVER_ACCEPTED", System.currentTimeMillis()))
                mutableMediaProgress.value = mutableMediaProgress.value - message.messageId
                mutableState.value = mutableState.value.copy(lastTransport = "ONLINE")
                if (response["duplicate"]?.jsonPrimitive?.content == "true")
                    logger.log("ONLINE", "ONLINE MESSAGE DUPLICATE_ACCEPTED", message.messageId)
                logger.log("ONLINE", "ONLINE MESSAGE SERVER_ACCEPTED", message.messageId)
                metrics?.record("ONLINE_SERVER_ACCEPTED", message, transport = "ONLINE",
                    result = if (response["duplicate"]?.jsonPrimitive?.content == "true") "IDEMPOTENT" else "ACCEPTED",
                    durationMs = System.currentTimeMillis() - uploadStarted)
            } catch (error: OnlineHttpException) {
                metrics?.record(if (error.status in 400..499) "TRANSFER_REJECTED" else "TRANSFER_FAILED",
                    message, transport = "ONLINE", reason = error.code)
                mutableMediaProgress.value = mutableMediaProgress.value - message.messageId
                db.onlineMessages().save(OnlineMessageState(message.messageId, "UPLOAD_FAILED"))
                mutableState.value = mutableState.value.copy(lastTransport = "DTN")
                logger.log("ONLINE", "ONLINE MESSAGE UPLOAD_FAILED ${error.code}", message.messageId)
                if (error.status >= 500 || error.status == 401) throw error
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                metrics?.record("TRANSFER_FAILED", message, transport = "ONLINE",
                    reason = error.javaClass.simpleName)
                mutableMediaProgress.value = mutableMediaProgress.value - message.messageId
                db.onlineMessages().save(OnlineMessageState(message.messageId, "UPLOAD_FAILED"))
                mutableState.value = mutableState.value.copy(lastTransport = "DTN")
                if (error is java.io.IOException) throw error
                logger.log("ONLINE", "ONLINE MESSAGE UPLOAD_FAILED ${error.javaClass.simpleName}", message.messageId)
            }
        }
    }

    private suspend fun uploadMedia(message: Message) {
        val manifest = media.manifest(message.messageId) ?: error("Media unavailable")
        require(manifest.mediaType == message.contentType && manifest.fileSize <= MediaPolicy.onlineMax(message.contentType))
        logger.log("ONLINE", "ONLINE ${message.contentType} UPLOAD START", message.messageId)
        val uploadStarted = System.currentTimeMillis()
        metrics?.record("MEDIA_TRANSFER_STARTED", message, transport = "ONLINE", bytes = manifest.fileSize)
        val session = api.json("POST", "/api/v1/media/upload-session", buildJsonObject {
            put("mediaId", manifest.mediaId); put("messageId", message.messageId)
            put("mediaType", manifest.mediaType.name); put("mimeType", manifest.mimeType)
            put("fileSize", manifest.fileSize); put("sha256", manifest.sha256)
            manifest.durationMs?.let { put("durationMs", it) }
            manifest.width?.let { put("width", it) }; manifest.height?.let { put("height", it) }
        }.toString())
        var offset = session["receivedBytes"]!!.jsonPrimitive.content.toLong()
        if (offset > 0L && offset < manifest.fileSize) metrics?.record("MEDIA_RESUME", message,
            transport = "ONLINE", bytes = offset)
        require(offset in 0..manifest.fileSize && (offset == manifest.fileSize || offset % MediaPolicy.CHUNK_BYTES == 0L))
        RandomAccessFile(media.filePath(manifest.mediaId), "r").use { file ->
            require(file.length() == manifest.fileSize)
            while (offset < manifest.fileSize) {
                if (recalls?.revoked(message.messageId) == true) return
                val count = minOf(MediaPolicy.CHUNK_BYTES.toLong(), manifest.fileSize - offset).toInt()
                val bytes = ByteArray(count)
                file.seek(offset); file.readFully(bytes)
                val response = api.jsonBytes("PUT", "/api/v1/media/${manifest.mediaId}/chunks?offset=$offset",
                    bytes, manifest.mimeType)
                val received = response["receivedBytes"]!!.jsonPrimitive.content.toLong()
                require(received >= offset + count && received <= manifest.fileSize)
                offset = received
                mutableMediaProgress.value = mutableMediaProgress.value +
                    (message.messageId to (offset * 100 / manifest.fileSize).toInt())
            }
        }
        logger.log("ONLINE", "ONLINE ${message.contentType} UPLOAD COMPLETE", message.messageId)
        metrics?.record("MEDIA_TRANSFER_COMPLETED", message, transport = "ONLINE",
            bytes = manifest.fileSize, durationMs = System.currentTimeMillis() - uploadStarted)
    }

    private suspend fun synchronize() {
        repeat(10) {
            logger.log("ONLINE", "ONLINE SYNC START")
            val response = api.json("GET", "/api/v1/sync?cursor=${api.cursor}")
            for (element in response["revokes"]?.jsonArray.orEmpty()) receiveRevoke(element.jsonObject)
            for (element in response["messages"]!!.jsonArray) receiveMessage(element.jsonObject)
            for (element in response["deliveryReceipts"]!!.jsonArray) receiveReceipt(element.jsonObject)
            for (element in response["revokeResults"]?.jsonArray.orEmpty()) receiveRevokeResult(element.jsonObject)
            api.cursor = response["nextCursor"]!!.jsonPrimitive.content.toLong()
            if (response["hasMore"]?.jsonPrimitive?.content != "true") return
        }
    }

    private suspend fun receiveMessage(row: JsonObject) {
        if (security != null && row["signatureVersion"]?.jsonPrimitive?.content !in setOf("1", "2")) {
            logger.log("SECURITY", "LEGACY_UNSIGNED online message skipped")
            return
        }
        val id = row.string("messageId")
        if (recalls?.revoked(id) == true) return
        val source = row.string("sourceId")
        val destination = row.string("destinationId")
        val created = row.long("createdAt")
        val expires = row.long("expiresAt")
        if (destination != localId || expires <= System.currentTimeMillis() || expires <= created ||
            (expires - created) % 1000L != 0L) return
        val type = MessageContentType.valueOf(row.string("contentType"))
        trustServerIdentity(source)
        val message = Message(id, source, destination, row.string("payload"), created,
            (expires - created) / 1000L, MessageStatus.RECEIVED, expiresAt = expires,
            hopLimit = row["hopLimit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 8,
            copiesRemaining = 1, contentType = type, mediaId = row["mediaId"]?.jsonPrimitive?.content,
            signatureVersion = row["signatureVersion"]?.jsonPrimitive?.content?.toIntOrNull(),
            originPublicKey = row["originPublicKey"]?.jsonPrimitive?.content,
            originPublicKeyFingerprint = row["originPublicKeyFingerprint"]?.jsonPrimitive?.content,
            contentDigest = row["contentDigest"]?.jsonPrimitive?.content,
            originSignature = row["originSignature"]?.jsonPrimitive?.content,
            signedMediaMimeType = row["signedMediaMimeType"]?.jsonPrimitive?.content,
            signedMediaFileSize = row["signedMediaFileSize"]?.jsonPrimitive?.content?.toLongOrNull(),
            signedMediaSha256 = row["signedMediaSha256"]?.jsonPrimitive?.content)
            .copy(relayPolicy = row["relayPolicy"]?.jsonPrimitive?.content?.let(RelayPolicy::valueOf)
                ?: RelayPolicy.ALLOW_RELAY)
        WireProtocol.validate(WirePacket(PacketType.MESSAGE, message = message.copy(status = MessageStatus.PENDING)))
        if (security != null) require(security.verifyMessage(message) != "UNKNOWN") { "Source identity not verified" }
        val old = messages.get(id)
        if (old != null && !com.group16.dinoblog.dtn.DeliveryEngine.sameBundle(old, message)) error("MESSAGE_ID_CONFLICT")
        if (old != null) metrics?.record("DUPLICATE_AVOIDED", message, transport = "ONLINE", reason = "MESSAGE_ID")
        else metrics?.record("MESSAGE_RECEIVE_STARTED", message, transport = "ONLINE")
        if (type != MessageContentType.TEXT) {
            val metadata = row["media"]!!.jsonObject
            val size = metadata.long("fileSize")
            require(size in 1..MediaPolicy.onlineMax(type))
            val manifest = MediaManifest(id, message.mediaId ?: error("Missing media ID"), metadata.string("mimeType"),
                when (type) { MessageContentType.IMAGE -> "image.jpg"; MessageContentType.AUDIO -> "audio";
                    MessageContentType.VIDEO -> "video"; else -> error("Invalid media type") },
                size, metadata.string("sha256"), MediaPolicy.CHUNK_BYTES,
                ((size + MediaPolicy.CHUNK_BYTES - 1) / MediaPolicy.CHUNK_BYTES).toInt(), type,
                metadata["durationMs"]?.takeIf { it.toString() != "null" }?.jsonPrimitive?.content?.toLong(),
                metadata["width"]?.takeIf { it.toString() != "null" }?.jsonPrimitive?.content?.toInt(),
                metadata["height"]?.takeIf { it.toString() != "null" }?.jsonPrimitive?.content?.toInt())
            val downloadStarted = System.currentTimeMillis()
            metrics?.record("MEDIA_TRANSFER_STARTED", message, transport = "ONLINE", bytes = size)
            if (!media.receiveOnline(message, manifest, null, localId, System.currentTimeMillis())) {
                val missing = media.prepareOnline(message, manifest, localId, System.currentTimeMillis())
                if (missing.size < manifest.totalChunks) metrics?.record("MEDIA_RESUME", message,
                    transport = "ONLINE")
                for (index in missing) {
                    val start = index.toLong() * manifest.chunkSize
                    val end = minOf(start + manifest.chunkSize, size) - 1
                    val bytes = api.request("GET", "/api/v1/media/${manifest.mediaId}",
                        headers = mapOf("Range" to "bytes=$start-$end"))
                    check(bytes.size.toLong() == end - start + 1)
                    val result = media.receiveChunk(id, manifest.mediaId, index, bytes, localId,
                        System.currentTimeMillis(), DtnConfig.MAX_RELAY_BUFFER_MESSAGES)
                    if (result is com.group16.dinoblog.repository.MediaChunkResult.Reject) {
                        if (media.receiveOnline(message, manifest, null, localId, System.currentTimeMillis())) break
                        error("Media download could not be completed")
                    }
                }
            }
            metrics?.record("MEDIA_TRANSFER_COMPLETED", message, transport = "ONLINE", bytes = size,
                durationMs = System.currentTimeMillis() - downloadStarted)
        } else {
            val receipt = DeliveryReceipt.create(message, localId, System.currentTimeMillis())
            if (recalls?.revoked(id) == true) return
            try { messages.commitReceived(message, receipt, localId, System.currentTimeMillis(), DtnConfig.MAX_RELAY_BUFFER_MESSAGES) }
            catch (_: com.group16.dinoblog.repository.MessageRevokedException) { return }
        }
        logger.log("ONLINE", "ONLINE MESSAGE RECEIVE", id)
        row["serverReceivedAt"]?.jsonPrimitive?.content?.toLongOrNull()?.let { receivedAt ->
            metrics?.record("ONLINE_MAILBOX_WAIT", message, transport = "ONLINE",
                durationMs = (System.currentTimeMillis() - receivedAt).coerceAtLeast(0))
        }
        if (old == null) metrics?.delivered(message, "ONLINE")
    }

    private suspend fun receiveReceipt(row: JsonObject) {
        if (security != null && row["signatureVersion"]?.jsonPrimitive?.content != "1") {
            logger.log("SECURITY", "LEGACY_UNSIGNED online receipt skipped")
            return
        }
        trustServerIdentity(row.string("destinationId"))
        val receipt = DeliveryReceipt(row.string("originalMessageId"), row.string("sourceId"),
            row.string("destinationId"), row.long("deliveredAt"), row.long("originalExpiresAt"),
            row.string("createdByDeviceId"),
            row["signatureVersion"]?.jsonPrimitive?.content?.toIntOrNull(),
            row["destinationPublicKey"]?.jsonPrimitive?.content,
            row["destinationPublicKeyFingerprint"]?.jsonPrimitive?.content,
            row["destinationSignature"]?.jsonPrimitive?.content)
        messages.saveReceipt(receipt)
        metrics?.record("RECEIPT_SERVER_SYNC", messages.get(receipt.originalMessageId), transport = "ONLINE")
        logger.log("ONLINE", "ONLINE RECEIPT RECEIVE", receipt.originalMessageId)
    }

    private suspend fun uploadRevokes() {
        val source = recalls ?: return
        for (id in source.revokeIds()) {
            val revoke = source.revoke(id) ?: continue
            if (revoke.originalSourceId != localId) continue
            try {
                api.json("POST", "/api/v1/messages/$id/revoke", json.encodeToString(revoke))
                logger.log("REVOKE", "REVOKE SERVER_ACCEPTED", id)
                metrics?.record("REVOKE_PROPAGATED", messageId = id, transport = "ONLINE")
            } catch (error: OnlineHttpException) { if (error.status >= 500) throw error }
        }
    }

    private suspend fun receiveRevoke(row: JsonObject) {
        val source = recalls ?: return
        val revoke = json.decodeFromString<Revoke>(row.toString())
        trustServerIdentity(revoke.originalSourceId)
        source.receiveRevoke(revoke)
    }

    private suspend fun uploadRevokeResults() {
        val source = recalls ?: return
        for (id in source.resultIds()) {
            val result = source.result(id) ?: continue
            if (result.originalDestinationId != localId) continue
            try { api.json("POST", "/api/v1/revoke-results", json.encodeToString(result)) }
            catch (error: OnlineHttpException) { if (error.status >= 500) throw error }
        }
    }

    private suspend fun receiveRevokeResult(row: JsonObject) {
        val source = recalls ?: return
        val result = json.decodeFromString<RevokeResult>(row.toString())
        trustServerIdentity(result.originalDestinationId)
        source.receiveResult(result)
    }

    private suspend fun lookupHeldRevokes() {
        val source = recalls ?: return
        val held = messages.messages.first().filter { it.sourceId != localId && it.destinationId != localId }
            .map { it.messageId }.take(200)
        if (held.isEmpty()) return
        val response = api.json("POST", "/api/v1/revokes/lookup", buildJsonObject {
            put("messageIds", kotlinx.serialization.json.JsonArray(held.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }.toString())
        for (element in response["revokes"]?.jsonArray.orEmpty()) receiveRevoke(element.jsonObject)
    }

    private suspend fun uploadReceipts() {
        for (receipt in messages.receipts.first().filter { it.destinationId == localId }) {
            try { api.json("POST", "/api/v1/receipts", buildJsonObject {
                put("originalMessageId", receipt.originalMessageId); put("sourceId", receipt.sourceId)
                put("destinationId", receipt.destinationId); put("deliveredAt", receipt.deliveredAt)
                put("originalExpiresAt", receipt.originalExpiresAt); put("createdByDeviceId", receipt.createdByDeviceId)
                receipt.signatureVersion?.let { put("signatureVersion", it) }
                receipt.destinationPublicKey?.let { put("destinationPublicKey", it) }
                receipt.destinationPublicKeyFingerprint?.let { put("destinationPublicKeyFingerprint", it) }
                receipt.destinationSignature?.let { put("destinationSignature", it) }
            }.toString()) }
            catch (error: OnlineHttpException) { if (error.status >= 500) throw error }
        }
    }

    private suspend fun lookupHeldReceipts() {
        val held = messages.messages.first().filter { it.sourceId != localId && it.destinationId != localId }
            .map { it.messageId }.take(200)
        if (held.isEmpty()) return
        val body = "{\"messageIds\":" + held.joinToString(prefix = "[", postfix = "]") { "\"$it\"" } + "}"
        val response = api.json("POST", "/api/v1/receipts/lookup", body)
        for (element in response["deliveryReceipts"]!!.jsonArray) receiveReceipt(element.jsonObject)
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content ?: error("Missing $key")
    private fun JsonObject.long(key: String) = string(key).toLong()
}
