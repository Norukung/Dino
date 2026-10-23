package com.group16.dinoblog.dtn

import com.group16.dinoblog.repository.PredictabilityStore
import com.group16.dinoblog.model.PredictabilityState
import com.group16.dinoblog.model.SmartRoutingDecision
import com.group16.dinoblog.model.RoutingMetrics
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.repository.RelayBufferFullException
import com.group16.dinoblog.repository.RelayDisabledException
import com.group16.dinoblog.network.MessageRejectReason
import com.group16.dinoblog.repository.ReceiptStore
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.model.InventoryState
import com.group16.dinoblog.model.ForwardingState
import com.group16.dinoblog.model.ForwardingKind
import com.group16.dinoblog.network.MessageTransport
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.repository.MessageStore
import com.group16.dinoblog.repository.MediaStore
import com.group16.dinoblog.repository.PreparedImage
import com.group16.dinoblog.repository.MediaOffer
import com.group16.dinoblog.repository.MediaChunkResult
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class DeliveryEngine(
    private val localId: String,
    private val store: MessageStore,
    private val transport: MessageTransport,
    private val scope: CoroutineScope,
    private val logger: NetworkLogger,
    private val inventory: StateFlow<InventoryState>? = null,
    private val relayBufferLimit: Int = DtnConfig.MAX_RELAY_BUFFER_MESSAGES,
    /** Internal baseline comparison for regression/evaluation; the app always enables smart selection. */
    private val smartRelaySelection: Boolean = true,
    private val relayEnabled: () -> Boolean = { true },
    private val mediaStore: MediaStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val receiptStore = store as? ReceiptStore
    private val predictabilityStore = store as? PredictabilityStore
    private val routingLock = Mutex()
    private val mutablePredictability = MutableStateFlow(PredictabilityState())
    val predictability = mutablePredictability.asStateFlow()
    @Volatile private var summarySending = false
    private val decisionKeys = mutableSetOf<String>()
    private val retryLock = Mutex()
    private var started = false
    private val forwardingLock = Any()
    private val mutableForwarding = MutableStateFlow(ForwardingState())
    val forwarding = mutableForwarding.asStateFlow()
    private data class Attempt(val peer: String, val session: String, val previousHop: Int, val destination: String, val at: Long,
        val outgoing: Message? = null, val copiesGiven: Int = 0, val copiesKept: Int = 0)
    private val attempts = mutableMapOf<String, Attempt>()
    private val peerKnown = mutableSetOf<String>()
    private val peerKnownReceipts = mutableSetOf<String>()
    private val receiptAttempts = mutableMapOf<String, Attempt>()
    private val peerRejected = mutableSetOf<String>()
    // A definitive rejection stays stable for retries of the same bundle/session.
    private val incomingRejected = mutableMapOf<String, Pair<Message, MessageRejectReason>>()
    private val mediaSending = mutableSetOf<String>()
    private val mutableMediaProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val mediaProgress = mutableMediaProgress.asStateFlow()

    fun start() {
        if (started) return
        started = true
        mediaStore?.let { media -> scope.launch { safely { media.recover(localId, now()) } } }
        transport.onPacket = { peer, packet -> receive(peer, packet) }
        scope.launch {
            transport.state.collectLatest { connection ->
                syncForwardingSession()
                connection.peerId?.let { peerId ->
                    safely {
                        store.savePeer(Peer(peerId, connection.peerName ?: "DinoBlog User", now()))
                        retryPending()
                    }
                }
            }
        }
        receiptStore?.let { receipts ->
            scope.launch { receipts.receipts.collect { rows ->
                rows.forEach { suppress(it.originalMessageId) }
                safely { retryPending() }
            } }
        }
        inventory?.let { snapshots ->
            scope.launch { snapshots.collect { safely { retryPending() } } }
        }
        scope.launch {
            while (isActive) {
                safely { retryPending() }
                delay(5_000)
            }
        }
    }

    suspend fun queue(receiverId: String, payload: String, ttl: Long = DtnConfig.DEFAULT_MESSAGE_LIFETIME_SECONDS,
        carrierId: String? = null, hopLimit: Int = DtnConfig.DEFAULT_HOP_LIMIT,
        allowRelay: Boolean = true): Message {
        require(receiverId != localId && WireProtocol.isUuid(receiverId)) { "Choose a valid recipient Device ID" }
        val message = Message(UUID.randomUUID().toString(), localId, receiverId, payload, now(), ttl,
            MessageStatus.PENDING, carrierId = carrierId,
            hopLimit = if (allowRelay) hopLimit else 1,
            copiesRemaining = if (allowRelay) DtnConfig.INITIAL_SPRAY_COPIES else 1)
        WireProtocol.validate(WirePacket(PacketType.MESSAGE, message = message))
        check(store.insert(message)) { "Could not store message" }
        logger.log("DB", "Queued durably", message.messageId, ttl, "PENDING")
        // Saving to Room completes before networking; process death cannot lose an unsent message.
        scope.launch { safely { retryPending() } }
        return message
    }

    suspend fun queueImage(receiverId: String, prepared: PreparedImage, allowRelay: Boolean = true): Message {
        require(receiverId != localId && WireProtocol.isUuid(receiverId)) { "Choose a valid recipient" }
        val media = requireNotNull(mediaStore)
        val message = media.queuePrepared(prepared, localId, receiverId, now(),
            DtnConfig.DEFAULT_MESSAGE_LIFETIME_SECONDS,
            if (allowRelay) DtnConfig.DEFAULT_HOP_LIMIT else 1,
            if (allowRelay) DtnConfig.INITIAL_SPRAY_COPIES else 1)
        scope.launch { safely { retryPending() } }
        return message
    }

    suspend fun retryPending() = retryLock.withLock {
        syncForwardingSession()
        if (smartRelaySelection) transport.state.value.let { c ->
            if (c.peerId != null && c.sessionId != null) ensureEncounter(c.peerId, c.sessionId)
        }
        store.cleanup(localId, now()).also { reclaimed ->
            if (reclaimed > 0) logger.log("DTN", "DTN RELAY PAYLOAD RECLAIMED count=$reclaimed")
        }
        mediaStore?.cleanup(localId, now())
        // The app supplies inventory: all automatic sends, including new PENDING
        // messages, use that comparison. Keep the standalone legacy direct path.
        if (inventory != null) { forwardMissing(); return@withLock }
        val peerId = transport.state.value.peerId ?: return@withLock
        for (message in store.pending(localId, peerId)) {
            if (deliveryKnown(message.messageId)) continue
            if (isExpired(message, now())) { store.expire(now()); continue }
            if (message.hopCount >= message.hopLimit) {
                logTransfer("DROP", localId, peerId, message, "Hop limit reached")
                continue
            }
            // Preserve the original first-hop path. Relay sends are inventory-gated below.
            if (message.sourceId != localId || message.hopCount != 0 || message.status != MessageStatus.PENDING) continue
            val kind = routingDecision(message, peerId, transport.state.value.sessionId) ?: continue
            if (kind == ForwardingKind.WAIT && !hasReservedSpray(message.messageId, peerId, transport.state.value.sessionId) &&
                !(message.contentType == MessageContentType.IMAGE && message.reservedCopies > 0 && message.reservedPeerId == peerId)) continue
            if (!sendCandidate(message, if (kind == ForwardingKind.WAIT) { if (smartRelaySelection) ForwardingKind.SMART_SPRAY else ForwardingKind.SPRAY } else kind,
                peerId, transport.state.value.sessionId, inventoryGated = false)) break
        }
        forwardMissing()
    }

    /** Called under retryLock: reuse the existing retry cadence and serialized TCP writes. */
    private suspend fun forwardMissing() {
        val snapshot = inventory?.value ?: return
        val peer = snapshot.peerId ?: return
        val session = snapshot.sessionId ?: return
        if (!ready(snapshot, peer, session)) return
        syncForwardingSession()
        synchronized(forwardingLock) {
            if (!matches(peer, session)) return
            peerKnown.addAll(snapshot.remoteMessageIds)
            mutableForwarding.value = mutableForwarding.value.copy(peerKnownIds = peerKnown.sorted())
        }
        synchronized(forwardingLock) {
            if (!matches(peer, session)) return
            peerKnownReceipts.addAll(snapshot.remoteDeliveredMessageIds)
            mutableForwarding.value = mutableForwarding.value.copy(peerKnownReceiptIds = peerKnownReceipts.sorted())
        }
        snapshot.remoteDeliveredMessageIds.forEach { suppress(it) }
        forwardReceipts(peer, session)
        // Wait for full remote tombstones to be persisted, and our receipts to be
        // storage-ACKed, before sending normal messages in this encounter.
        if (receiptStore != null && (snapshot.remoteDeliveredMessageIds.any { receiptStore.receipt(it) == null } ||
            snapshot.missingReceiptsOnPeer.any { synchronized(forwardingLock) { it !in peerKnownReceipts } })) return
        if (smartRelaySelection) sendPredictability(peer, session)
        logger.log("DTN", "DTN SMART SPRAY EVALUATE\npeer=$peer\nmissing=${snapshot.missingOnPeer.size}")
        val candidates = mutableListOf<Pair<Message, ForwardingKind>>()
        val waitIds = mutableListOf<String>()
        for (id in snapshot.missingOnPeer) {
            val message = store.get(id) ?: continue
            if (deliveryKnown(id)) continue
            if (!matches(peer, session)) {
                logger.log("DTN", "DTN FORWARD CANCEL stale session", id)
                return
            }
            if (!isCurrentCandidate(id, peer, session)) {
                logger.log("DTN", "DTN FORWARD SKIP peer known or no longer missing", id)
                continue
            }
            val kind = routingDecision(message, peer, session)
            if (kind == null) {
                logger.log("DTN", "DTN FORWARD SKIP ${routingRejection(message, localId, peer, now())}", id)
                continue
            }
            if (kind == ForwardingKind.WAIT && !hasReservedSpray(id, peer, session) &&
                !(message.contentType == MessageContentType.IMAGE && message.reservedCopies > 0 && message.reservedPeerId == peer)) {
                waitIds += id
                logger.log("DTN", "Candidate WAIT; copy budget or predictability gate", id)
                continue
            }
            candidates += message to if (kind == ForwardingKind.WAIT) { if (smartRelaySelection) ForwardingKind.SMART_SPRAY else ForwardingKind.SPRAY } else kind
        }
        val ordered = candidates.sortedWith(compareBy<Pair<Message, ForwardingKind>> { it.second.ordinal }
            .thenBy { it.first.createdAt }.thenBy { it.first.messageId })
        synchronized(forwardingLock) {
            if (matches(peer, session)) mutableForwarding.value = mutableForwarding.value.copy(
                waitIds = waitIds.sorted(), candidateIds = ordered.map { it.first.messageId },
                directIds = ordered.filter { it.second == ForwardingKind.DIRECT }.map { it.first.messageId },
                relayIds = ordered.filter { it.second in listOf(ForwardingKind.SPRAY, ForwardingKind.SMART_SPRAY) }.map { it.first.messageId })
        }
        for ((selected, _) in ordered) {
            val id = selected.messageId
            // Earlier sends/ACKs and Room reads may have changed eligibility or the session.
            val message = store.get(id) ?: continue
            if (deliveryKnown(id)) continue
            if (!isCurrentCandidate(id, peer, session)) continue
            val kind = routingDecision(message, peer, session) ?: continue
            if (kind == ForwardingKind.WAIT && !hasReservedSpray(id, peer, session) &&
                !(message.contentType == MessageContentType.IMAGE && message.reservedCopies > 0 && message.reservedPeerId == peer)) continue
            val sent = sendCandidate(message, if (kind == ForwardingKind.WAIT) { if (smartRelaySelection) ForwardingKind.SMART_SPRAY else ForwardingKind.SPRAY } else kind, peer, session)
            if (!sent && !matches(peer, session)) return
        }
    }

    private fun hasReservedSpray(id: String, peer: String, session: String?) = synchronized(forwardingLock) {
        attempts[id]?.let { it.peer == peer && it.session == (session ?: "legacy") && it.copiesGiven > 0 } == true
    }

    /** One retry coordinator and one TCP writer. Reservation happens at the writer boundary. */
    private suspend fun sendCandidate(selected: Message, kind: ForwardingKind, peer: String,
        session: String?, inventoryGated: Boolean = true): Boolean {
        val id = selected.messageId
        val token = session ?: "legacy"
        val prior = synchronized(forwardingLock) { attempts[id]?.takeIf { it.peer == peer && it.session == token } }
        if (inventoryGated && prior != null && now() - prior.at < 5000) return false
        val resuming = selected.contentType == MessageContentType.IMAGE && selected.reservedCopies > 0 && selected.reservedPeerId == peer
        val outgoing = prior?.outgoing ?: selected.copy(status = MessageStatus.PENDING,
            hopCount = selected.hopCount + 1,
            copiesRemaining = if (resuming) selected.reservedCopies else if (kind in listOf(ForwardingKind.SPRAY, ForwardingKind.SMART_SPRAY)) selected.copiesRemaining / 2 else selected.copiesRemaining)
        val manifest = if (selected.contentType == MessageContentType.IMAGE) mediaStore?.manifest(id) else null
        if (selected.contentType == MessageContentType.IMAGE && manifest == null) return false
        val packet = if (manifest == null) WirePacket(PacketType.MESSAGE, message = outgoing)
            else WirePacket(PacketType.MEDIA_MANIFEST, message = outgoing, manifest = manifest)
        suspend fun eligible(): Boolean {
            fun current() = if (inventoryGated && session != null) isCurrentCandidate(id, peer, session)
                else transport.state.value.peerId == peer && transport.state.value.sessionId == session &&
                    synchronized(forwardingLock) { id !in peerRejected && id !in peerKnown }
            if (!current()) return false
            val latest = store.get(id) ?: return false
            if (routingRejection(latest, localId, peer, now()) != null || !sameBundle(latest, outgoing) || deliveryKnown(id)) return false
            if (resuming && (latest.reservedCopies != selected.reservedCopies || latest.reservedPeerId != peer ||
                latest.copiesRemaining - latest.reservedCopies < 1)) return false
            if (!relayAllowed(latest, peer, session)) return false
            var given = prior?.copiesGiven ?: if (resuming) latest.reservedCopies else 0
            var kept = prior?.copiesKept ?: if (resuming) latest.copiesRemaining - latest.reservedCopies else latest.copiesRemaining
            val spraying = kind in listOf(ForwardingKind.SPRAY, ForwardingKind.SMART_SPRAY)
            if (spraying && smartRelaySelection && !remoteReady(peer, session)) return false
            if (spraying && prior == null && !resuming) {
                if (classify(latest, localId, peer, now()) != ForwardingKind.SPRAY || latest.copiesRemaining != selected.copiesRemaining) return false
                val reservation = if (smartRelaySelection) predictabilityStore?.reserveSmartSpray(id, localId, peer,
                    selected.copiesRemaining, remoteScore(latest.destinationId, peer, session), now())
                    else store.reserveSpray(id, localId, peer, selected.copiesRemaining, now())
                if (reservation == null) {
                    logger.log("ROUTE", "Queued smart spray rejected; score/budget changed before reservation", id)
                    return false
                }
                given = reservation.copiesGiven; kept = reservation.retained.copiesRemaining
                check(given == outgoing.copiesRemaining && kept >= 1)
                logger.log("DTN", "Spray escrow L=${selected.copiesRemaining} keep=$kept send=$given; commit after ACK", id)
                synchronized(forwardingLock) {
                    if (current()) mutableForwarding.value = mutableForwarding.value.copy(lastCopiesGiven = given, lastCopiesKept = kept)
                }
            } else if (kind == ForwardingKind.DIRECT && latest.destinationId != peer) return false
            if (spraying && smartRelaySelection && !resuming) {
                val score = predictabilityStore?.predictability(latest.destinationId, now())
                if (score == null || !PredictabilityPolicy.better(score, remoteScore(latest.destinationId, peer, session))) {
                    if (prior == null && given > 0) store.restoreSpray(id, kept, given)
                    return false
                }
            }
            // A reserved retry is the SAME branch, even if the retained row is now WAIT.
            // Never reserve again for its lost ACK. A changed session abandons it conservatively.
            if (!current() || deliveryKnown(id) || isExpired(latest, now()) || !relayAllowed(latest, peer, session)) {
                if (spraying && prior == null && given > 0) store.restoreSpray(id, kept, given)
                return false
            }
            val attempt = Attempt(peer, token, latest.hopCount, latest.destinationId, now(), outgoing, given, kept)
            synchronized(forwardingLock) {
                if (!current()) return false
                attempts[id] = attempt // Before write: immediate ACK/reject can resolve it.
                mutableForwarding.value = mutableForwarding.value.copy(awaitingAckIds = attempts.keys.sorted())
            }
            logger.log("DTN", "Candidate $kind; copies=${outgoing.copiesRemaining} hop=${outgoing.hopCount}", id)
            return true
        }
        val sent = if (session != null) transport.sendIfCurrent(peer, session, packet) { eligible() }
            else if (eligible()) transport.send(peer, packet) else false
        synchronized(forwardingLock) {
            if (transport.state.value.peerId == peer && transport.state.value.sessionId == session && sent)
                mutableForwarding.value = mutableForwarding.value.copy(lastSentId = id, lastSentKind = kind)
        }
        if (!sent && kind in listOf(ForwardingKind.SPRAY, ForwardingKind.SMART_SPRAY)) logger.log("DTN", "Skipped/ambiguous spray failure: unconfirmed escrow retained if reserved", id)
        if (sent) metric(peer, session) { it.copy(messageTransmissions = it.messageTransmissions + 1,
            directAttempts = it.directAttempts + if (kind == ForwardingKind.DIRECT) 1 else 0,
            smartAttempts = it.smartAttempts + if (kind == ForwardingKind.SMART_SPRAY) 1 else 0) }
        return sent
    }

    private suspend fun ensureEncounter(peer: String, session: String) = routingLock.withLock {
        val scores = predictabilityStore ?: return@withLock
        syncForwardingSession()
        if (!matches(peer, session) || mutablePredictability.value.encounterRecorded) return@withLock
        // Finish this bounded transaction/flag update even if a StateFlow collector is cancelled.
        withContext(NonCancellable) {
            val updated = scores.recordEncounter(peer, now())
            synchronized(forwardingLock) {
                if (matches(peer, session)) mutablePredictability.value = mutablePredictability.value.copy(
                    encounterRecorded = true, lastEncounterScore = updated.predictability)
            }
        }
    }

    private suspend fun sendPredictability(peer: String, session: String) {
        val scores = predictabilityStore ?: return
        ensureEncounter(peer, session)
        val entries = routingLock.withLock {
            if (!matches(peer, session) || mutablePredictability.value.summarySent || summarySending ||
                !mutablePredictability.value.encounterRecorded) return
            summarySending = true
            try { scores.predictabilitySummary(localId, emptySet(), now()) }
            catch (e: Exception) { summarySending = false; throw e }
        }
        // No routing mutex during socket write: a fast peer summary may arrive concurrently.
        var sent = false
        try {
            sent = transport.sendToSession(peer, session,
                WirePacket(PacketType.PREDICTABILITY_SUMMARY, deviceId = localId, entries = entries))
        } finally {
            // Cancellation while waiting for the socket writer must not latch this
            // session in "sending" forever. The peer ignores duplicate summaries.
            synchronized(forwardingLock) {
                if (matches(peer, session)) {
                    summarySending = false
                    mutablePredictability.value = mutablePredictability.value.copy(summarySent = sent)
                }
            }
        }
        if (sent) logger.log("ROUTE", "Predictability summary sent peer=$peer count=${entries.size}")
    }

    private fun remoteReady(peer: String, session: String?): Boolean = synchronized(forwardingLock) {
        val r = mutablePredictability.value
        session != null && matches(peer, session) && r.peerId == peer && r.sessionId == session &&
            r.encounterRecorded && r.summarySent && r.summaryReceived
    }
    private fun remoteScore(destination: String, peer: String, session: String?): Double {
        if (!remoteReady(peer, session)) return 0.0
        val r = mutablePredictability.value
        val score = r.remoteEntries.find { it.deviceId == destination }?.predictability ?: 0.0
        return PredictabilityPolicy.age(score, r.receivedAt ?: now(), now())
    }
    private suspend fun routingDecision(message: Message, peer: String, session: String?): ForwardingKind? {
        val base = classify(message, localId, peer, now())
        if (base != null && base != ForwardingKind.DIRECT && !relayAllowed(message, peer, session)) return null
        if (!smartRelaySelection) return base
        val localScore = if (base == ForwardingKind.SPRAY) predictabilityStore?.predictability(message.destinationId, now()) ?: 0.0 else 0.0
        val peerScore = remoteScore(message.destinationId, peer, session)
        val decision = PredictabilityPolicy.decide(base, localScore, peerScore, remoteReady(peer, session))
        val reason = when {
            base == null -> "invalid/expired/hop/status"
            base == ForwardingKind.DIRECT -> "final destination"
            base == ForwardingKind.WAIT -> "one-copy WAIT"
            !remoteReady(peer, session) -> "awaiting current routing summary"
            peerScore <= localScore -> "poor score"
            decision == ForwardingKind.WAIT -> "insufficient margin"
            else -> "peer meaningfully better"
        }
        synchronized(forwardingLock) {
            if (session != null && matches(peer, session)) {
                val entry = SmartRoutingDecision(message.messageId, message.destinationId, localScore, peerScore, decision?.name ?: "SKIP", reason)
                val old = mutablePredictability.value
                val key = message.messageId + ":" + reason
                val first = decisionKeys.add(key)
                val m = old.metrics
                mutablePredictability.value = old.copy(decisions = (old.decisions.filterNot { it.messageId == message.messageId } + entry).takeLast(100),
                    metrics = if (!first) m else m.copy(poorScore = m.poorScore + if (reason == "poor score") 1 else 0,
                        insufficientMargin = m.insufficientMargin + if (reason == "insufficient margin") 1 else 0,
                        waits = m.waits + if (decision == ForwardingKind.WAIT) 1 else 0))
                if (first) logger.log("ROUTE", "${entry.decision} $reason local=$localScore peer=$peerScore margin=${DtnConfig.FORWARDING_MARGIN}", message.messageId)
            }
        }
        return decision
    }
    private fun relayAllowed(message: Message, peer: String, session: String?): Boolean {
        if (message.destinationId == peer) {
            if (!relayEnabled()) logger.log("DTN", "DTN DIRECT DELIVERY ALLOWED relayEnabled=false", message.messageId)
            return true
        }
        if (message.sourceId != localId && !relayEnabled()) {
            logger.log("DTN", "DTN RELAY SKIP reason=LOCAL_RELAY_DISABLED", message.messageId)
            return false
        }
        val connection = transport.state.value
        if (connection.peerId == peer && connection.sessionId == session && connection.peerRelayEnabled == false) {
            logger.log("DTN", "DTN RELAY SKIP reason=PEER_RELAY_DISABLED", message.messageId)
            return false
        }
        return true
    }
    private fun metric(peer: String, session: String?, update: (RoutingMetrics) -> RoutingMetrics) = synchronized(forwardingLock) {
        if (session != null && matches(peer, session)) mutablePredictability.value = mutablePredictability.value.copy(metrics = update(mutablePredictability.value.metrics))
    }

    private fun suppress(id: String) = synchronized(forwardingLock) {
        attempts.remove(id)
        mutableForwarding.value = mutableForwarding.value.copy(
            awaitingAckIds = attempts.keys.sorted(), candidateIds = mutableForwarding.value.candidateIds - id,
            directIds = mutableForwarding.value.directIds - id, relayIds = mutableForwarding.value.relayIds - id,
            waitIds = mutableForwarding.value.waitIds - id)
    }

    private suspend fun deliveryKnown(id: String): Boolean {
        val known = receiptStore?.receipt(id) != null || inventory?.value?.remoteDeliveredMessageIds?.contains(id) == true
        if (known) { suppress(id); logger.log("DTN", "Message suppressed because delivered", id) }
        return known
    }

    private suspend fun forwardReceipts(peer: String, session: String) {
        val receipts = receiptStore ?: return
        val snapshot = inventory?.value ?: return
        for (id in snapshot.missingReceiptsOnPeer) {
            val receipt = receipts.receipt(id) ?: continue
            val send = synchronized(forwardingLock) {
                if (!matches(peer, session) || id in peerKnownReceipts ||
                    receiptAttempts[id]?.let { now() - it.at < 5000 } == true) false
                else {
                    receiptAttempts[id] = Attempt(peer, session, 0, receipt.destinationId, now())
                    mutableForwarding.value = mutableForwarding.value.copy(inFlightReceiptIds = receiptAttempts.keys.sorted())
                    true
                }
            }
            if (!send) continue
            logger.log("DTN", "Sending delivery receipt peer=$peer", id)
            val sent = transport.sendIfCurrent(peer, session, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = receipt)) {
                inventory?.value?.let { ready(it, peer, session) } == true &&
                    synchronized(forwardingLock) { id !in peerKnownReceipts }
            }
            if (sent && matches(peer, session)) synchronized(forwardingLock) {
                mutableForwarding.value = mutableForwarding.value.copy(lastSentReceiptId = id)
            }
            if (sent) metric(peer, session) { it.copy(receiptTransmissions = it.receiptTransmissions + 1) }
            if (!sent) return
        }
    }

    private fun ready(snapshot: InventoryState, peer: String, session: String) =
        snapshot.peerId == peer && snapshot.sessionId == session && snapshot.remoteReceived &&
            snapshot.localReady && snapshot.sent && snapshot.error == null && matches(peer, session)

    private fun isCurrentCandidate(id: String, peer: String, session: String): Boolean = inventory?.value?.let {
        ready(it, peer, session) && id in it.missingOnPeer && id !in it.remoteMessageIds && id !in it.remoteDeliveredMessageIds &&
            synchronized(forwardingLock) { id !in peerKnown && id !in peerRejected }
    } == true

    private fun matches(peer: String, session: String) = transport.state.value.let {
        it.connectedPeerDeviceId == peer && it.sessionId == session
    }

    private fun syncForwardingSession() = synchronized(forwardingLock) {
        val connection = transport.state.value
        val old = mutableForwarding.value
        if (old.peerId != connection.peerId || old.sessionId != connection.sessionId) {
            if (attempts.isNotEmpty()) logger.log("DTN", "DTN FORWARD SESSION CLOSED; unconfirmed spray escrow retained")
            attempts.clear()
            peerKnown.clear()
            peerKnownReceipts.clear()
            receiptAttempts.clear()
            peerRejected.clear()
            incomingRejected.clear()
            mediaSending.clear()
            mutableMediaProgress.value = emptyMap()
            logger.log("DTN", "Receipt session state cleared")
            mutableForwarding.value = ForwardingState(peerId = connection.peerId, sessionId = connection.sessionId)
            mutablePredictability.value = PredictabilityState(peerId = connection.peerId, sessionId = connection.sessionId)
            summarySending = false
            decisionKeys.clear()
            logger.log("ROUTE", "Remote predictability summary cleared on session change")
        }
    }

    private fun rememberPeerHas(id: String, peer: String, session: String?) = synchronized(forwardingLock) {
        if (session == null || !matches(peer, session)) return@synchronized
        syncForwardingSession()
        peerKnown += id
        mutableForwarding.value = mutableForwarding.value.copy(peerKnownIds = peerKnown.sorted())
    }

    private suspend fun confirmForwarded(id: String, peer: String, session: String?, status: MessageStatus): Boolean {
        if (session == null || !matches(peer, session)) return false
        val attempt = synchronized(forwardingLock) { attempts[id] } ?: return false
        if (attempt.peer != peer || attempt.session != session) return false
        val final = attempt.destination == peer
        if (status != if (final) MessageStatus.DELIVERED else MessageStatus.CARRIED) return false
        val changed = if (final) store.relayDelivered(id, localId, peer, attempt.previousHop)
            else if (attempt.copiesGiven > 0) store.confirmSpray(id, peer, attempt.previousHop, attempt.copiesKept, attempt.copiesGiven)
            else store.replicated(id, peer, attempt.previousHop)
        if (changed && attempt.copiesGiven > 0 && smartRelaySelection) metric(peer, session) { it.copy(smartAccepted = it.smartAccepted + 1) }
        if (changed) synchronized(forwardingLock) {
            if (matches(peer, session)) {
                attempts.remove(id)
                peerKnown += id
                mutableForwarding.value = mutableForwarding.value.copy(awaitingAckIds = attempts.keys.sorted(),
                    peerKnownIds = peerKnown.sorted(), lastAckId = id)
            }
        }
        return changed
    }

    suspend fun receive(peerId: String, packet: WirePacket, sessionId: String? = transport.state.value.sessionId) {
        if (sessionId != null && !matches(peerId, sessionId)) return
        if (packet.type == PacketType.PREDICTABILITY_SUMMARY) {
            if (!smartRelaySelection || sessionId == null || predictabilityStore == null) return
            if (runCatching { WireProtocol.validate(packet); require(packet.deviceId == peerId) }.isFailure) {
                logger.log("DROP", "Invalid predictability summary; ignored peer=$peerId")
                return
            }
            ensureEncounter(peerId, sessionId)
            routingLock.withLock {
                if (!matches(peerId, sessionId) || mutablePredictability.value.summaryReceived) return@withLock
                predictabilityStore.learnTransitive(localId, peerId, packet.entries, now())
                synchronized(forwardingLock) {
                    if (matches(peerId, sessionId)) mutablePredictability.value = mutablePredictability.value.copy(
                        summaryReceived = true, remoteEntries = packet.entries.toList(), receivedAt = now(), lastTransitiveAt = now())
                }
            }
            logger.log("ROUTE", "Remote predictability summary received peer=$peerId count=${packet.entries.size}")
            scope.launch { safely { retryPending() } }
            return
        }
        if (packet.type != PacketType.MEDIA_MANIFEST) WireProtocol.validate(packet)
        when (packet.type) {
            PacketType.MESSAGE -> {
                val message = requireNotNull(packet.message)
                if (message.contentType == MessageContentType.IMAGE) {
                    sendMediaReject(peerId, sessionId, message.messageId, MessageRejectReason.INVALID_MEDIA)
                    return
                }
                val isDestination = message.destinationId == localId
                val finalForward = isDestination && message.hopCount >= 2
                require(message.hopCount >= 1) { "A received copy must have traversed a hop" }
                // The original carrier hint is immutable, not an authorization list for later relays.
                // Record knowledge before Room emits its insert so a refresh cannot echo it back.
                rememberPeerHas(message.messageId, peerId, sessionId)
                val storedStatus = if (isDestination) MessageStatus.RECEIVED else MessageStatus.CARRIED
                val ackStatus = if (isDestination) MessageStatus.DELIVERED else MessageStatus.CARRIED
                synchronized(forwardingLock) {
                    if (sessionId != null && matches(peerId, sessionId)) syncForwardingSession()
                }
                val existing = store.get(message.messageId)
                val tombstone = receiptStore?.receipt(message.messageId)
                require(tombstone == null || tombstone.matches(message)) { "Receipt/message identity conflict" }
                if (tombstone != null) {
                    require(existing == null || sameBundle(existing, message)) { "UUID collision with different content" }
                    logger.log("DTN", "Stale replica ignored because receipt exists", message.messageId)
                    suppress(message.messageId)
                    val ack = WirePacket(PacketType.ACK, messageId = message.messageId, ackStatus = ackStatus)
                    if (sessionId != null) transport.sendToSession(peerId, sessionId, ack) else transport.send(peerId, ack)
                    scope.launch { safely { retryPending() } }
                    return
                }
                val rejected = synchronized(forwardingLock) { incomingRejected[message.messageId] }
                if (rejected != null) {
                    require(sameBundle(rejected.first, message)) { "Conflicting rejected retry" }
                    rejectRelay(peerId, sessionId, message, rejected.second)
                    return
                }
                if (existing != null) {
                    require(sameBundle(existing, message)) { "UUID collision with different content" }
                    // A lost ACK causes a valid duplicate. ACK it again, even after TTL,
                    // because this exact message was already committed before expiration.
                    logger.log("DB", "Duplicate suppressed; copy budget not increased; re-ACK", message.messageId, message.ttl, existing.status.name)
                } else {
                    // Check admission before any new Room insert. Already committed
                    // duplicates above only re-ACK existing storage; they are never
                    // inserted again or given a fresh lifetime (preserves lost-ACK recovery).
                    if (message.hopCount > message.hopLimit) {
                        logTransfer("DROP", peerId, localId, message, "DTN REJECT: hopCount > hopLimit")
                        return
                    }
                    if (isExpired(message, now()) || message.timestamp > now() + 300_000) {
                        logTransfer("DROP", peerId, localId, message.copy(status = MessageStatus.EXPIRED), "DTN REJECT: expired or clock skew exceeds 5 minutes")
                        return
                    }
                    if (!isDestination && message.sourceId != localId && !relayEnabled()) {
                        rejectRelay(peerId, sessionId, message, MessageRejectReason.RELAY_DISABLED)
                        return
                    }
                }
                if (receiptStore != null) {
                    val receipt = if (isDestination && !isExpired(message, now())) DeliveryReceipt.create(message, localId, now()) else null
                    val accepted = try {
                        receiptStore.commitReceived(message.copy(status = storedStatus), receipt, localId, now(), relayBufferLimit)
                    } catch (_: RelayBufferFullException) {
                        rejectRelay(peerId, sessionId, message)
                        return // Definitive failure: no success ACK and no payload admitted.
                    } catch (_: RelayDisabledException) {
                        rejectRelay(peerId, sessionId, message, MessageRejectReason.RELAY_DISABLED)
                        return
                    }
                    if (receipt != null && accepted) logger.log("DB", "Delivery receipt created at final destination", message.messageId)
                    if (!accepted) logger.log("DTN", "Stale replica suppressed during atomic admission", message.messageId)
                } else if (existing == null) {
                    if (!store.insert(message.copy(status = storedStatus))) {
                        val saved = store.get(message.messageId)
                        require(saved != null && sameBundle(saved, message)) { "Conflicting duplicate" }
                    }
                }
                if (existing == null && isDestination) metric(peerId, sessionId) { it.copy(delivered = it.delivered + 1) }
                if (existing == null) logTransfer("DB", peerId, localId, message.copy(status = storedStatus),
                    if (isDestination) "DTN FINAL DESTINATION ACCEPT; committed to Room" else "DTN RECEIVE RELAY; stored CARRIED")
                if (finalForward) {
                    logger.log("RX", "DTN FORWARD RECEIVE\npeer=$peerId\nsource=${message.sourceId}\ndestination=$localId", message.messageId)
                    // The chat belongs to original A, not carrier B. Make A selectable
                    // in the existing Conversations/Choose peer UI without changing the UI model.
                    if (store.peers.first().none { it.deviceId == message.sourceId }) {
                        store.savePeer(Peer(message.sourceId, "DinoBlog User", now()))
                    }
                }
                // Never acknowledge until the Room insert has returned successfully.
                // If storage fails, no ACK is sent and A can safely retry later.
                // A custody ACK proves only storage on B, never delivery to C.
                val ack = WirePacket(PacketType.ACK, messageId = message.messageId, ackStatus = ackStatus)
                if (sessionId != null) transport.sendToSession(peerId, sessionId, ack) else transport.send(peerId, ack)
            }
            PacketType.DELIVERY_RECEIPT -> {
                val receipts = receiptStore ?: return
                val receipt = requireNotNull(packet.receipt)
                require(receipt.deliveredAt <= now() + 300_000) { "Receipt clock skew exceeds 5 minutes" }
                // Peer knowledge is recorded before Room emits, preventing send-back.
                synchronized(forwardingLock) {
                    if (sessionId != null && matches(peerId, sessionId)) {
                        syncForwardingSession()
                        peerKnownReceipts += receipt.originalMessageId
                    }
                }
                val inserted = receipts.saveReceipt(receipt)
                store.cleanup(localId, now()).also { reclaimed ->
                    if (reclaimed > 0) logger.log("DTN", "DTN RELAY PAYLOAD RECLAIMED messageId=${receipt.originalMessageId} reason=DELIVERED count=$reclaimed")
                }
                mediaStore?.cleanup(localId, now())
                suppress(receipt.originalMessageId)
                synchronized(forwardingLock) {
                    if (sessionId != null && matches(peerId, sessionId)) mutableForwarding.value = mutableForwarding.value.copy(
                        lastReceivedReceiptId = receipt.originalMessageId, peerKnownReceiptIds = peerKnownReceipts.sorted())
                }
                logger.log("DB", if (inserted) "Delivery receipt persisted; source/relay delivery confirmed" else "Delivery receipt already exists", receipt.originalMessageId)
                val ack = WirePacket(PacketType.DELIVERY_RECEIPT_ACK, messageId = receipt.originalMessageId)
                if (sessionId != null) transport.sendToSession(peerId, sessionId, ack) else transport.send(peerId, ack)
                scope.launch { safely { retryPending() } }
            }
            PacketType.DELIVERY_RECEIPT_ACK -> {
                val id = requireNotNull(packet.messageId)
                synchronized(forwardingLock) {
                    val attempt = receiptAttempts[id]
                    if (sessionId != null && matches(peerId, sessionId) && attempt?.peer == peerId && attempt.session == sessionId) {
                        receiptAttempts.remove(id)
                        peerKnownReceipts += id
                        mutableForwarding.value = mutableForwarding.value.copy(inFlightReceiptIds = receiptAttempts.keys.sorted(),
                            peerKnownReceiptIds = peerKnownReceipts.sorted())
                        logger.log("RX", "Receipt storage ACK received", id)
                    }
                }
                scope.launch { safely { retryPending() } }
            }
            PacketType.MESSAGE_REJECT -> {
                val id = requireNotNull(packet.messageId)
                val attempt = synchronized(forwardingLock) {
                    val pending = attempts[id]
                    if (sessionId == null || !matches(peerId, sessionId) || pending?.peer != peerId || pending.session != sessionId) null
                    else {
                        attempts.remove(id)
                        peerRejected += id
                        mutableForwarding.value = mutableForwarding.value.copy(awaitingAckIds = attempts.keys.sorted(),
                            rejectedIds = peerRejected.sorted(), lastRejectReason = packet.reason?.name)
                        pending
                    }
                }
                if (attempt != null) {
                    val restored = if (attempt.copiesGiven > 0) store.restoreSpray(id, attempt.copiesKept, attempt.copiesGiven) else false
                    val reason = packet.reason?.name ?: "UNKNOWN"
                    logger.log("DTN", if (restored) "Spray budget restored after definitive $reason" else "$reason; restoration skipped because row changed", id)
                } else logger.log("DROP", "Unknown, duplicate or stale MESSAGE_REJECT", id)
            }
            PacketType.MEDIA_MANIFEST -> {
                val message = packet.message
                val manifest = packet.manifest
                if (message == null || manifest == null || mediaStore == null) {
                    message?.messageId?.takeIf(WireProtocol::isUuid)?.let { sendMediaReject(peerId, sessionId, it, MessageRejectReason.INVALID_MEDIA) }
                    return
                }
                val result = try { mediaStore.offer(message, manifest, localId, now(), relayBufferLimit) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    logger.log("DTN", "DTN MEDIA MANIFEST FAILED: ${e.javaClass.simpleName}", message.messageId)
                    MediaOffer.Reject(MessageRejectReason.INVALID_MEDIA)
                }
                when (result) {
                    is MediaOffer.Accept -> {
                        rememberPeerHas(message.messageId, peerId, sessionId)
                        val accept = WirePacket(PacketType.MEDIA_ACCEPT, messageId = message.messageId,
                            mediaId = manifest.mediaId, missingChunks = result.missingChunks)
                        if (sessionId != null) transport.sendToSession(peerId, sessionId, accept) else transport.send(peerId, accept)
                    }
                    MediaOffer.Have -> {
                        rememberPeerHas(message.messageId, peerId, sessionId)
                        val ack = WirePacket(PacketType.ACK, messageId = message.messageId,
                            ackStatus = if (message.destinationId == localId) MessageStatus.DELIVERED else MessageStatus.CARRIED)
                        if (sessionId != null) transport.sendToSession(peerId, sessionId, ack) else transport.send(peerId, ack)
                    }
                    is MediaOffer.Reject -> sendMediaReject(peerId, sessionId, message.messageId, result.reason)
                }
            }
            PacketType.MEDIA_ACCEPT -> {
                val id = requireNotNull(packet.messageId)
                val mediaId = requireNotNull(packet.mediaId)
                val attempt = synchronized(forwardingLock) {
                    attempts[id]?.takeIf { it.peer == peerId && it.session == sessionId &&
                        it.outgoing?.contentType == MessageContentType.IMAGE && it.outgoing.mediaId == mediaId }
                } ?: return
                val manifest = mediaStore?.manifest(id) ?: return
                val missing = requireNotNull(packet.missingChunks)
                if (missing.any { it !in 0 until manifest.totalChunks }) return
                val launch = synchronized(forwardingLock) { mediaSending.add(id) }
                if (!launch) return
                scope.launch {
                    try {
                        logger.log("DTN", "DTN MEDIA RESUME missingChunks=${missing.size}", id)
                        for ((position, index) in missing.withIndex()) {
                            if (sessionId == null || !matches(peerId, sessionId) || deliveryKnown(id) ||
                                isExpired(attempt.outgoing!!, now()) || !relayAllowed(attempt.outgoing, peerId, sessionId) ||
                                synchronized(forwardingLock) { id in peerRejected || attempts[id]?.peer != peerId || attempts[id]?.session != sessionId }) break
                            val bytes = mediaStore.readChunk(mediaId, index) ?: break
                            val chunk = WirePacket(PacketType.MEDIA_CHUNK, messageId = id, mediaId = mediaId,
                                chunkIndex = index, dataLength = bytes.size, binaryData = bytes)
                            if (!transport.sendToSession(peerId, sessionId, chunk)) break
                            mutableMediaProgress.value = mutableMediaProgress.value + (id to ((position + 1) * 100 / missing.size))
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { logger.log("DTN", "DTN MEDIA TRANSFER INTERRUPTED: ${e.message}", id) }
                    finally { synchronized(forwardingLock) { mediaSending.remove(id) } }
                }
            }
            PacketType.MEDIA_CHUNK -> {
                val id = requireNotNull(packet.messageId)
                val result = mediaStore?.receiveChunk(id, requireNotNull(packet.mediaId), requireNotNull(packet.chunkIndex),
                    requireNotNull(packet.binaryData), localId, now(), relayBufferLimit) ?: return
                when (result) {
                    is MediaChunkResult.Progress -> Unit
                    is MediaChunkResult.Complete -> {
                        val message = result.message
                        if (message.destinationId == localId && message.hopCount >= 2 &&
                            store.peers.first().none { it.deviceId == message.sourceId })
                            store.savePeer(Peer(message.sourceId, "DinoBlog User", now()))
                        logger.log("DTN", "DTN MEDIA COMPLETE mediaId=${message.mediaId}", id)
                        mutableMediaProgress.value = mutableMediaProgress.value - id
                        val ack = WirePacket(PacketType.ACK, messageId = id,
                            ackStatus = if (message.destinationId == localId) MessageStatus.DELIVERED else MessageStatus.CARRIED)
                        if (sessionId != null) transport.sendToSession(peerId, sessionId, ack) else transport.send(peerId, ack)
                    }
                    is MediaChunkResult.Reject -> sendMediaReject(peerId, sessionId, id, result.reason)
                }
            }
            PacketType.ACK -> {
                val id = requireNotNull(packet.messageId)
                val changed = if (inventory != null) confirmForwarded(id, peerId, sessionId, packet.ackStatus)
                    else if (packet.ackStatus == MessageStatus.CARRIED) store.forwarded(id, localId, peerId)
                    else store.delivered(id, localId, peerId)
                if (changed) {
                    mutableMediaProgress.value = mutableMediaProgress.value - id
                    if (inventory == null) synchronized(forwardingLock) {
                        attempts.remove(id)
                        mutableForwarding.value = mutableForwarding.value.copy(awaitingAckIds = attempts.keys.sorted())
                    }
                    store.get(id)?.let { logTransfer("DB", localId, peerId, it,
                        if (packet.ackStatus == MessageStatus.CARRIED) "DTN RELAY ACK; copy stored, NOT final delivery" else "DTN DIRECT ACK; confirmed durable delivery") }
                } else logger.log("DROP", "Unknown, duplicate, or wrong-peer ACK", id)
            }
            else -> Unit
        }
    }

    private suspend fun rejectRelay(peer: String, session: String?, message: Message,
        reason: MessageRejectReason = MessageRejectReason.BUFFER_FULL) {
        require(message.destinationId != localId)
        synchronized(forwardingLock) {
            if (session != null && matches(peer, session)) {
                syncForwardingSession()
                incomingRejected[message.messageId] = message to reason
                mutableForwarding.value = mutableForwarding.value.copy(lastRejectReason = reason.name)
            }
        }
        logger.log("DTN", "DTN RELAY REJECT reason=${reason.name}", message.messageId)
        val packet = WirePacket(PacketType.MESSAGE_REJECT, messageId = message.messageId, reason = reason)
        if (session != null) transport.sendToSession(peer, session, packet) else transport.send(peer, packet)
    }

    private suspend fun sendMediaReject(peer: String, session: String?, id: String, reason: MessageRejectReason) {
        if (!WireProtocol.isUuid(id)) return
        logger.log("DTN", "DTN MEDIA REJECT reason=$reason", id)
        val packet = WirePacket(PacketType.MESSAGE_REJECT, messageId = id, reason = reason)
        if (session != null) transport.sendToSession(peer, session, packet) else transport.send(peer, packet)
    }

    private fun logTransfer(direction: String, from: String, to: String, message: Message, detail: String) {
        logger.log(direction, "$detail\n$from -> $to\nmessageId=${message.messageId}\nsource=${message.sourceId}\ndestination=${message.destinationId}\ncurrentNode=$localId\nhopCount=${message.hopCount}\nhopLimit=${message.hopLimit}\nstatus=${message.status.name}",
            message.messageId, message.ttl, message.status.name)
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { logger.log("ERROR", "Delivery: ${e.message}") }
    }

    companion object {
        /** Pure routing policy. Session/known/in-flight checks stay in the existing coordinator. */
        fun classify(message: Message, localId: String, peerId: String, at: Long): ForwardingKind? {
            if (routingRejection(message, localId, peerId, at) != null) return null
            return if (message.destinationId == peerId) ForwardingKind.DIRECT
                else if (message.copiesRemaining > 1) ForwardingKind.SPRAY else ForwardingKind.WAIT
        }
        private fun routingRejection(message: Message, localId: String, peerId: String, at: Long): String? {
            if (localId == peerId) return "self peer"
            if (message.destinationId == localId) return "locally final content"
            if (isExpired(message, at)) return "expired"
            if (message.hopCount < 0 || message.hopCount >= message.hopLimit) return "hop limit"
            if (message.status !in listOf(MessageStatus.PENDING, MessageStatus.CARRIED, MessageStatus.FORWARDED)) return "status ${message.status}"
            val outgoing = message.copy(status = MessageStatus.PENDING, hopCount = message.hopCount + 1)
            if (runCatching { WireProtocol.validate(WirePacket(PacketType.MESSAGE, message = outgoing)) }.isFailure) return "invalid metadata"
            return null
        }
        // Hop count is path metadata; a duplicate arriving by a different allowed path
        // must not create a second row. All immutable bundle fields must still match.
        fun sameBundle(stored: Message, incoming: Message): Boolean =
            stored.copy(status = incoming.status, hopCount = incoming.hopCount,
                copiesRemaining = incoming.copiesRemaining, reservedCopies = incoming.reservedCopies,
                reservedPeerId = incoming.reservedPeerId) == incoming
        fun isExpired(message: Message, at: Long): Boolean = at >= message.expiresAt
    }
}
