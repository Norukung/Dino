package com.group16.dinoblog.experiment

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.room.withTransaction
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.model.ExperimentEvent
import com.group16.dinoblog.model.ExperimentMessageResult
import com.group16.dinoblog.model.ExperimentSession
import com.group16.dinoblog.model.MediaEntity
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.RoutingExperimentMode
import com.group16.dinoblog.repository.MediaRepository
import com.group16.dinoblog.repository.MessageRepository
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ExperimentDashboard(val session: ExperimentSession? = null,
    val summary: ExperimentSummary? = null, val lastEvent: String? = null)
data class CleanupMeasurement(val message: Message, val reason: String, val bytes: Long)

/** Observational, bounded metric queue. Failure here never aborts a network operation. */
class ExperimentManager(private val context: Context, private val localId: String,
    private val db: DinoblogDatabase, private val messages: MessageRepository,
    private val media: MediaRepository, private val scope: CoroutineScope,
    private val logger: NetworkLogger, private val networkIdle: () -> Boolean) {
    private val dao = db.experiments()
    private val control = Mutex()
    private val queue = Channel<Command>(512)
    private val mutableCurrent = MutableStateFlow<ExperimentSession?>(null)
    val current = mutableCurrent.asStateFlow()
    private val mutableSessions = MutableStateFlow<List<ExperimentSession>>(emptyList())
    val sessions = mutableSessions.asStateFlow()
    private val mutableDashboard = MutableStateFlow(ExperimentDashboard())
    val dashboard = mutableDashboard.asStateFlow()
    private val sent = AtomicLong()
    private val received = AtomicLong()
    private val mediaSent = AtomicLong()
    private val mediaReceived = AtomicLong()
    private val dtnControlSent = AtomicLong()
    private val dtnPayloadSent = AtomicLong()
    private val onlineControlSent = AtomicLong()
    private val onlinePayloadSent = AtomicLong()
    private val buffer = AtomicLong()
    private val peak = AtomicLong()
    private val partial = AtomicLong()
    private var lastRefreshAt = 0L
    private val seenCreated = ConcurrentHashMap.newKeySet<String>()
    private val seenReceipts = ConcurrentHashMap.newKeySet<String>()
    private val seenExpired = ConcurrentHashMap.newKeySet<String>()
    private val seenDestination = ConcurrentHashMap.newKeySet<String>()

    private sealed interface Command {
        val experimentId: String
        data class Event(override val experimentId: String, val row: ExperimentEvent) : Command
        data class Created(override val experimentId: String, val message: Message, val fileSize: Long) : Command
        data class Delivered(override val experimentId: String, val message: Message, val transport: String) : Command
        data class Receipt(override val experimentId: String, val receipt: DeliveryReceipt, val message: Message,
            val learnedAt: Long) : Command
        data class Expired(override val experimentId: String, val message: Message) : Command
        data class Barrier(override val experimentId: String, val done: CompletableDeferred<Unit>) : Command
    }

    init {
        scope.launch {
            for (command in queue) {
                try { process(command) }
                catch (error: Exception) { logger.log("METRIC", "Metric write failed: ${error.javaClass.simpleName}") }
                finally { if (command is Command.Barrier) command.done.complete(Unit) }
            }
        }
        scope.launch {
            control.withLock {
                mutableSessions.value = dao.sessions()
                dao.active()?.let { active ->
                    mutableCurrent.value = active
                    loadCounters(active)
                    restoreSeen(active.experimentId)
                    refreshDashboard(active.experimentId, force = true)
                }
            }
            current.collectLatest { active ->
                if (active != null) combine(messages.messages, messages.receipts, media.media) { rows, receipts, mediaRows ->
                    Triple(rows, receipts, mediaRows)
                }.collect { (rows, receipts, mediaRows) -> observeCore(rows, receipts, mediaRows) }
            }
        }
        scope.launch {
            while (isActive) {
                delay(5_000)
                runCatching { control.withLock { flushCounters() } }
                    .onFailure { logger.log("METRIC", "Counter flush failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    fun routingMode(): RoutingExperimentMode = current.value?.routingMode?.let {
        runCatching { RoutingExperimentMode.valueOf(it) }.getOrNull()
    } ?: RoutingExperimentMode.SMART_SPRAY

    fun activeId(): String? = current.value?.experimentId

    fun created(message: Message, fileSize: Long = 0) {
        val active = current.value ?: return
        if (message.sourceId == localId && message.createdAt >= active.startedAt && seenCreated.add(message.messageId))
            enqueueReliable(Command.Created(active.experimentId, message, fileSize))
    }

    suspend fun cleanupCandidates(at: Long): List<CleanupMeasurement> {
        if (current.value == null) return emptyList()
        return db.messages().relayCleanupCandidates(localId, at).mapNotNull { candidate ->
            val message = db.messages().get(candidate.messageId) ?: return@mapNotNull null
            val bytes = message.mediaId?.let { db.media().get(it)?.fileSize }
                ?: message.payload.toByteArray(Charsets.UTF_8).size.toLong()
            CleanupMeasurement(message, candidate.reason, bytes)
        }
    }

    suspend fun recordCleanup(candidates: List<CleanupMeasurement>) {
        if (current.value == null) return
        for (candidate in candidates) if (db.messages().get(candidate.message.messageId) == null)
            record("BUFFER_RECLAIM", candidate.message, bytes = candidate.bytes, transport = "DTN",
                reason = candidate.reason)
    }

    suspend fun start(label: String, mode: RoutingExperimentMode, nodeCount: Int?, target: Int?, notes: String): ExperimentSession =
        control.withLock {
            require(current.value == null) { "Stop the current experiment first" }
            require(networkIdle()) { "Disconnect the current peer before changing routing mode" }
            require(label.trim().length in 1..64 && notes.length <= 256) { "Use a label of 1–64 characters and short notes" }
            require(nodeCount == null || nodeCount in 2..100)
            require(target == null || target in 1..100_000)
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            val row = ExperimentSession(UUID.randomUUID().toString(), label.trim(), System.currentTimeMillis(),
                routingMode = mode.name, deviceHash = hash(localId), appVersion = version,
                buildType = if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) "DEBUG" else "RELEASE",
                nodeCount = nodeCount, messageCountTarget = target, notes = notes.trim())
            dao.insertSession(row)
            resetCounters()
            seenCreated.clear(); seenReceipts.clear(); seenExpired.clear(); seenDestination.clear()
            mutableCurrent.value = row
            mutableSessions.value = dao.sessions()
            mutableDashboard.value = ExperimentDashboard(row)
            record("EXPERIMENT_STARTED")
            row
        }

    suspend fun stop(): ExperimentSession = control.withLock {
        val active = requireNotNull(current.value) { "No experiment is running" }
        require(networkIdle()) { "Disconnect the current peer before stopping this routing test" }
        record("EXPERIMENT_STOPPED")
        barrier(active.experimentId)
        flushCounters()
        val ended = requireNotNull(dao.session(active.experimentId)).copy(endedAt = System.currentTimeMillis())
        dao.updateSession(ended)
        mutableCurrent.value = null // Production routing is SMART_SPRAY again.
        mutableSessions.value = dao.sessions()
        refreshDashboard(ended.experimentId, force = true)
        ended
    }

    suspend fun reset(): Unit = control.withLock {
        val active = requireNotNull(current.value) { "No experiment is running" }
        require(networkIdle()) { "Disconnect before resetting experiment metrics" }
        barrier(active.experimentId)
        db.withTransaction {
            dao.deleteEvents(active.experimentId)
            dao.deleteMessages(active.experimentId)
            dao.updateSession(active.copy(startedAt = System.currentTimeMillis(), bytesSent = 0,
                bytesReceived = 0, mediaBytesSent = 0, mediaBytesReceived = 0, dtnControlBytesSent = 0,
                dtnPayloadBytesSent = 0, onlineControlBytesSent = 0, onlinePayloadBytesSent = 0,
                currentBufferBytes = 0, peakBufferBytes = 0, partialMediaBytes = 0))
        }
        resetCounters()
        seenCreated.clear(); seenReceipts.clear(); seenExpired.clear(); seenDestination.clear()
        mutableCurrent.value = dao.session(active.experimentId)
        refreshDashboard(active.experimentId, force = true)
    }

    suspend fun delete(id: String) = control.withLock {
        require(current.value?.experimentId != id) { "Stop the experiment before deleting its results" }
        db.withTransaction { dao.deleteEvents(id); dao.deleteMessages(id); dao.deleteSession(id) }
        mutableSessions.value = dao.sessions()
        if (mutableDashboard.value.session?.experimentId == id) mutableDashboard.value = ExperimentDashboard()
    }

    suspend fun export(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        val row = requireNotNull(dao.session(id)) { "Experiment not found" }
        require(row.endedAt != null) { "Stop the experiment before exporting" }
        val output = requireNotNull(context.contentResolver.openOutputStream(uri)) { "Cannot open export document" }
        ExperimentCsv.writeZip(output, row, dao.messages(id), dao.events(id))
    }

    fun record(type: String, message: Message? = null, peerId: String? = null, mediaId: String? = null,
        decision: String? = null, bytes: Long? = null, transport: String? = null,
        result: String? = null, reason: String? = null, durationMs: Long? = null,
        messageId: String? = null) {
        val active = current.value ?: return
        val event = ExperimentEvent(experimentId = active.experimentId, eventTime = System.currentTimeMillis(),
            elapsedRealtimeMs = SystemClock.elapsedRealtime(), eventType = type,
            messageId = message?.messageId ?: messageId, mediaId = mediaId ?: message?.mediaId,
            localDeviceHash = active.deviceHash, peerDeviceHash = peerId?.let(::hash),
            routingMode = active.routingMode, decision = decision, bytes = bytes,
            hopCount = message?.hopCount, copiesRemaining = message?.copiesRemaining,
            bufferBytes = buffer.get(), contentType = message?.contentType?.name,
            transport = transport, durationMs = durationMs, result = result, reason = reason)
        enqueue(Command.Event(active.experimentId, event))
    }

    fun delivered(message: Message, transport: String) {
        val active = current.value ?: return
        enqueueReliable(Command.Delivered(active.experimentId, message, transport))
    }

    fun bytes(direction: String, transport: String, mediaPayload: Boolean, amount: Long,
        payload: Boolean = mediaPayload, mediaBytes: Long = if (mediaPayload) amount else 0) {
        if (current.value == null || amount <= 0) return
        if (direction == "SENT") {
            sent.addAndGet(amount)
            if (mediaPayload) mediaSent.addAndGet(mediaBytes)
            when (transport) {
                "DTN", "WIFI_DIRECT", "BLUETOOTH" ->
                    (if (payload) dtnPayloadSent else dtnControlSent).addAndGet(amount)
                "ONLINE" -> (if (payload) onlinePayloadSent else onlineControlSent).addAndGet(amount)
            }
        } else {
            received.addAndGet(amount)
            if (mediaPayload) mediaReceived.addAndGet(mediaBytes)
        }
        if (transport == "WIFI_DIRECT" || transport == "BLUETOOTH")
            record("PEER_BYTES_$direction", bytes = amount, transport = transport,
                result = if (payload) "PAYLOAD" else "CONTROL")
    }

    private fun observeCore(rows: List<Message>, receipts: List<DeliveryReceipt>, mediaRows: List<MediaEntity>) {
        val active = current.value ?: return
        val byId = rows.associateBy { it.messageId }
        val fileSizes = mediaRows.associate { it.messageId to it.fileSize }
        for (message in rows) {
            created(message, fileSizes[message.messageId] ?: 0)
            if (message.sourceId == localId && message.createdAt >= active.startedAt &&
                message.status == MessageStatus.EXPIRED && seenExpired.add(message.messageId))
                enqueueReliable(Command.Expired(active.experimentId, message))
        }
        for (receipt in receipts) {
            val message = byId[receipt.originalMessageId] ?: continue
            if (receipt.sourceId == localId && message.createdAt >= active.startedAt && seenReceipts.add(message.messageId))
                enqueueReliable(Command.Receipt(active.experimentId, receipt, message, System.currentTimeMillis()))
            if (receipt.createdByDeviceId == localId && receipt.deliveredAt >= active.startedAt &&
                seenDestination.add(message.messageId)) delivered(message, "UNKNOWN")
        }
        val receiptIds = receipts.mapTo(hashSetOf()) { it.originalMessageId }
        val relayMessages = rows.filter { it.sourceId != localId && it.destinationId != localId &&
            it.status == MessageStatus.CARRIED && it.expiresAt > System.currentTimeMillis() && it.messageId !in receiptIds }
        val mediaIds = mediaRows.associateBy { it.messageId }
        val relayBytes = relayMessages.sumOf { mediaIds[it.messageId]?.fileSize ?: it.payload.toByteArray(Charsets.UTF_8).size.toLong() }
        val partialBytes = mediaRows.filter { !it.isComplete && it.sourceId != localId && it.destinationId != localId }
            .sumOf { it.fileSize }
        buffer.set(relayBytes + partialBytes)
        partial.set(partialBytes)
        peak.accumulateAndGet(buffer.get()) { a, b -> maxOf(a, b) }
    }

    private suspend fun process(command: Command) {
        val id = command.experimentId
        when (command) {
            is Command.Event -> {
                val eventId = dao.insertEvent(command.row)
                if (eventId % 100L == 0L) dao.pruneEvents(id, 10_000)
                if (command.row.eventType != "ROUTING_DECISION") refreshDashboard(id)
            }
            is Command.Created -> {
                val m = command.message
                if (dao.message(id, m.messageId) == null) {
                    val size = m.mediaId?.let { db.media().get(it)?.fileSize } ?: command.fileSize
                    dao.saveMessage(ExperimentMessageResult(id, m.messageId, hash(m.sourceId), hash(m.destinationId),
                        m.contentType.name, size, m.createdAt, routingMode = requireNotNull(dao.session(id)).routingMode,
                        createdLocally = true))
                    recordFor(id, "MESSAGE_CREATED", m)
                }
                refreshDashboard(id)
            }
            is Command.Delivered -> {
                val m = command.message
                val old = dao.message(id, m.messageId)
                val transport = if (command.transport == "UNKNOWN") old?.deliveryTransport ?: "UNKNOWN" else command.transport
                val raced = command.transport in setOf("DTN", "ONLINE") && dao.events(id).any { event ->
                    event.messageId == m.messageId && event.transport in setOf("DTN", "ONLINE") &&
                        event.transport != command.transport && event.eventType in setOf("MEDIA_TRANSFER_STARTED", "MESSAGE_RECEIVE_STARTED")
                }
                val finalTransport = when {
                    raced -> "HYBRID_RACE"
                    transport == "ONLINE" -> "ONLINE"
                    transport == "DTN" && m.hopCount <= 1 -> "DIRECT_DTN"
                    transport == "DTN" -> "MULTIHOP_DTN"
                    else -> transport
                }
                val deliveredAt = old?.deliveredAt ?: System.currentTimeMillis()
                val row = (old ?: ExperimentMessageResult(id, m.messageId, hash(m.sourceId), hash(m.destinationId),
                    m.contentType.name, 0, m.createdAt, routingMode = requireNotNull(dao.session(id)).routingMode))
                    .copy(deliveredAt = deliveredAt, finalHopCount = if (command.transport == "UNKNOWN") old?.finalHopCount else m.hopCount,
                        deliveryTransport = finalTransport, finalStatus = "DELIVERED")
                dao.saveMessage(row)
                if (old?.deliveredAt == null) recordFor(id, "MESSAGE_DELIVERED", m, result = finalTransport, transport = command.transport)
                refreshDashboard(id)
            }
            is Command.Receipt -> {
                val r = command.receipt
                val old = dao.message(id, r.originalMessageId)
                val firstReceipt = if (r.sourceId == localId) old?.receiptReceivedAt == null else old?.deliveredAt == null
                val row = (old ?: ExperimentMessageResult(id, r.originalMessageId, hash(r.sourceId), hash(r.destinationId),
                    command.message.contentType.name, 0, command.message.createdAt,
                    routingMode = requireNotNull(dao.session(id)).routingMode,
                    createdLocally = r.sourceId == localId)).copy(deliveredAt = r.deliveredAt,
                        receiptReceivedAt = if (r.sourceId == localId) old?.receiptReceivedAt ?: command.learnedAt else old?.receiptReceivedAt,
                        finalStatus = "DELIVERED")
                dao.saveMessage(row)
                if (firstReceipt) recordFor(id, if (r.sourceId == localId) "RECEIPT_RECEIVED" else "RECEIPT_CREATED", command.message)
                refreshDashboard(id)
            }
            is Command.Expired -> {
                dao.message(id, command.message.messageId)?.let { dao.saveMessage(it.copy(finalStatus = "EXPIRED")) }
                recordFor(id, "MESSAGE_EXPIRED", command.message)
                refreshDashboard(id)
            }
            is Command.Barrier -> Unit
        }
    }

    private suspend fun recordFor(id: String, type: String, message: Message,
        result: String? = null, transport: String? = null) {
        val session = dao.session(id) ?: return
        val eventId = dao.insertEvent(ExperimentEvent(experimentId = id, eventTime = System.currentTimeMillis(),
            elapsedRealtimeMs = SystemClock.elapsedRealtime(), eventType = type, messageId = message.messageId,
            mediaId = message.mediaId, localDeviceHash = session.deviceHash, routingMode = session.routingMode,
            hopCount = message.hopCount, copiesRemaining = message.copiesRemaining,
            contentType = message.contentType.name, result = result, transport = transport))
        if (eventId % 100L == 0L) dao.pruneEvents(id, 10_000)
    }

    private suspend fun restoreSeen(id: String) {
        for (event in dao.events(id)) when (event.eventType) {
            "MESSAGE_CREATED" -> event.messageId?.let(seenCreated::add)
            "RECEIPT_RECEIVED" -> event.messageId?.let(seenReceipts::add)
            "MESSAGE_EXPIRED" -> event.messageId?.let(seenExpired::add)
            "MESSAGE_DELIVERED" -> event.messageId?.let(seenDestination::add)
        }
    }

    private suspend fun barrier(id: String) {
        val done = CompletableDeferred<Unit>()
        queue.send(Command.Barrier(id, done))
        done.await()
    }

    private fun enqueue(command: Command): Boolean {
        if (queue.trySend(command).isSuccess) return true
        logger.log("METRIC", "Metric queue full; detailed event omitted")
        return false
    }

    private fun enqueueReliable(command: Command) {
        if (!enqueue(command)) scope.launch(start = CoroutineStart.UNDISPATCHED) { queue.send(command) }
    }

    private suspend fun refreshDashboard(id: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRefreshAt < 1_000) return
        lastRefreshAt = now
        val session = dao.session(id) ?: return
        val currentSession = if (current.value?.experimentId == id) session.copy(
            bytesSent = sent.get(), bytesReceived = received.get(), mediaBytesSent = mediaSent.get(),
            mediaBytesReceived = mediaReceived.get(), currentBufferBytes = buffer.get(),
            peakBufferBytes = peak.get(), partialMediaBytes = partial.get()) else session
        val events = dao.events(id)
        mutableDashboard.value = ExperimentDashboard(currentSession,
            ExperimentAnalysis.summarize(currentSession, dao.messages(id), events), events.lastOrNull()?.eventType)
    }

    private suspend fun flushCounters() {
        val active = current.value ?: return
        val old = dao.session(active.experimentId) ?: return
        dao.updateSession(old.copy(bytesSent = sent.get(), bytesReceived = received.get(),
            mediaBytesSent = mediaSent.get(), mediaBytesReceived = mediaReceived.get(),
            dtnControlBytesSent = dtnControlSent.get(), dtnPayloadBytesSent = dtnPayloadSent.get(),
            onlineControlBytesSent = onlineControlSent.get(), onlinePayloadBytesSent = onlinePayloadSent.get(),
            currentBufferBytes = buffer.get(), peakBufferBytes = peak.get(), partialMediaBytes = partial.get()))
        refreshDashboard(active.experimentId)
    }

    private fun resetCounters() {
        listOf(sent, received, mediaSent, mediaReceived, dtnControlSent, dtnPayloadSent,
            onlineControlSent, onlinePayloadSent, buffer, peak, partial).forEach { it.set(0) }
    }

    private fun loadCounters(session: ExperimentSession) {
        sent.set(session.bytesSent); received.set(session.bytesReceived)
        mediaSent.set(session.mediaBytesSent); mediaReceived.set(session.mediaBytesReceived)
        dtnControlSent.set(session.dtnControlBytesSent); dtnPayloadSent.set(session.dtnPayloadBytesSent)
        onlineControlSent.set(session.onlineControlBytesSent); onlinePayloadSent.set(session.onlinePayloadBytesSent)
        buffer.set(session.currentBufferBytes); peak.set(session.peakBufferBytes); partial.set(session.partialMediaBytes)
    }

    private fun hash(id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(id.lowercase().toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
}
