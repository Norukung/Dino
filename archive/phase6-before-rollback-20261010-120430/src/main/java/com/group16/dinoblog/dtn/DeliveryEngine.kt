package com.group16.dinoblog.dtn

import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.model.InventoryState
import com.group16.dinoblog.model.ForwardingState
import com.group16.dinoblog.model.ForwardingKind
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.network.MessageTransport
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.repository.MessageStore
import com.group16.dinoblog.repository.ReceiptStore
import com.group16.dinoblog.repository.MessageAdmission
import com.group16.dinoblog.util.NetworkLogger
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
    private val receiptStore: ReceiptStore? = store as? ReceiptStore,
    private val receiptLifetimeMillis: Long = DeliveryReceipt.DEFAULT_LIFETIME_MILLIS,
    private val receiptHopLimit: Int = 10,
    private val now: () -> Long = System::currentTimeMillis,
) {
    init {
        require(receiptLifetimeMillis in 1..DeliveryReceipt.MAX_LIFETIME_MILLIS)
        require(receiptHopLimit in 1..255)
    }
    private val retryLock = Mutex()
    private var started = false
    private val forwardingLock = Any()
    private val mutableForwarding = MutableStateFlow(ForwardingState())
    val forwarding = mutableForwarding.asStateFlow()
    private data class Attempt(val peer: String, val session: String, val previousHop: Int, val destination: String, val at: Long)
    private val attempts = mutableMapOf<String, Attempt>()
    private val peerKnown = mutableSetOf<String>()
    private val receiptAttempts = mutableMapOf<String, Attempt>()
    private val receiptPeerKnown = mutableSetOf<String>()

    fun start() {
        if (started) return
        started = true
        transport.onPacket = { peer, packet -> receive(peer, packet) }
        scope.launch {
            transport.state.collectLatest { connection ->
                syncForwardingSession()
                connection.peerId?.let { peerId ->
                    safely {
                        store.savePeer(Peer(peerId, connection.peerName ?: "Android device", now()))
                        retryPending()
                    }
                }
            }
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

    suspend fun queue(receiverId: String, payload: String, ttl: Long = 86_400,
        carrierId: String? = null, hopLimit: Int = 8): Message {
        require(receiverId != localId && WireProtocol.isUuid(receiverId)) { "Choose a valid recipient Device ID" }
        val message = Message(UUID.randomUUID().toString(), localId, receiverId, payload, now(), ttl,
            MessageStatus.PENDING, carrierId = carrierId, hopLimit = hopLimit)
        WireProtocol.validate(WirePacket(PacketType.MESSAGE, message = message))
        check(store.insert(message)) { "Could not store message" }
        logger.log("DB", "Queued durably", message.messageId, ttl, "PENDING")
        // Saving to Room completes before networking; process death cannot lose an unsent message.
        scope.launch { safely { retryPending() } }
        return message
    }

    suspend fun retryPending() = retryLock.withLock {
        syncForwardingSession()
        store.expire(now())
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
            // A successful TCP write is not proof of storage on B. Keep PENDING until ACK.
            // Increment the outgoing copy, not the queued row. Lost ACK retries therefore
            // keep the same UUID AND hop count; duplicate TCP attempts are not new hops.
            val outgoing = message.copy(hopCount = message.hopCount + 1)
            if (outgoing.hopCount > outgoing.hopLimit) {
                logTransfer("DROP", localId, peerId, outgoing, "DTN REJECT: receiving hop exceeds hopLimit")
                continue
            }
            logTransfer("TX", localId, peerId, outgoing, "First-hop transfer")
            if (!transport.send(peerId, WirePacket(PacketType.MESSAGE, message = outgoing))) break
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
            receiptPeerKnown.addAll(snapshot.remoteReceiptIds)
            mutableForwarding.value = mutableForwarding.value.copy(peerKnownIds = peerKnown.sorted(), receiptPeerKnownIds = receiptPeerKnown.sorted())
        }
        forwardReceipts(peer, session, ForwardingKind.DIRECT)
        logger.log("DTN", "DTN EPIDEMIC EVALUATE\npeer=$peer\nmissing=${snapshot.missingOnPeer.size}")
        val candidates = mutableListOf<Pair<Message, ForwardingKind>>()
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
            val kind = classify(message, localId, peer, now())
            if (kind == null) {
                logger.log("DTN", "DTN FORWARD SKIP ${routingRejection(message, localId, peer, now())}", id)
                continue
            }
            candidates += message to kind
        }
        val ordered = candidates.sortedWith(compareBy<Pair<Message, ForwardingKind>> { it.second.ordinal }
            .thenBy { it.first.createdAt }.thenBy { it.first.messageId })
        synchronized(forwardingLock) {
            if (matches(peer, session)) mutableForwarding.value = mutableForwarding.value.copy(
                candidateIds = ordered.map { it.first.messageId },
                directIds = ordered.filter { it.second == ForwardingKind.DIRECT }.map { it.first.messageId },
                relayIds = ordered.filter { it.second == ForwardingKind.RELAY }.map { it.first.messageId })
        }
        var relayedReceipts = false
        for ((selected, selectedKind) in ordered) {
            if (selectedKind == ForwardingKind.RELAY && !relayedReceipts) {
                forwardReceipts(peer, session, ForwardingKind.RELAY)
                relayedReceipts = true
            }
            val id = selected.messageId
            // Earlier sends/ACKs and Room reads may have changed eligibility or the session.
            val message = store.get(id) ?: continue
            if (deliveryKnown(id)) continue
            if (!isCurrentCandidate(id, peer, session)) continue
            val kind = classify(message, localId, peer, now()) ?: continue
            val outgoing = message.copy(status = MessageStatus.PENDING, hopCount = message.hopCount + 1)
            logger.log("DTN", "DTN FORWARD $kind CANDIDATE\npeer=$peer\ndestination=${message.destinationId}", id)
            val attempt = Attempt(peer, session, message.hopCount, message.destinationId, now())
            val shouldSend = synchronized(forwardingLock) {
                syncForwardingSession()
                val previous = attempts[id]
                if (!matches(peer, session) || id in peerKnown ||
                    (previous != null && previous.session == session && now() - previous.at < 5000)) false
                else {
                    attempts[id] = attempt // Register before writing: a fast ACK may arrive immediately.
                    mutableForwarding.value = mutableForwarding.value.copy(awaitingAckIds = attempts.keys.sorted())
                    true
                }
            }
            if (!shouldSend) {
                logger.log("DTN", "DTN FORWARD SKIP in flight or peer known", id)
                continue
            }
            logTransfer("TX", localId, peer, outgoing, "DTN FORWARD $kind SEND")
            val sent = transport.sendToSession(peer, session, WirePacket(PacketType.MESSAGE, message = outgoing))
            synchronized(forwardingLock) {
                if (matches(peer, session) && sent) mutableForwarding.value = mutableForwarding.value.copy(lastSentId = id, lastSentKind = kind)
            }
            if (!sent) {
                logger.log("DTN", "DTN FORWARD CONNECTION LOST; persistent copy retained", id)
                return
            }
        }
        if (!relayedReceipts) forwardReceipts(peer, session, ForwardingKind.RELAY)
    }

    private suspend fun deliveryKnown(id: String): Boolean {
        if (receiptStore?.receiptForMessage(id) == null) return false
        logger.log("DTN", "DTN MESSAGE SUPPRESSED reason=FINAL_DELIVERY_KNOWN", id)
        synchronized(forwardingLock) {
            attempts.remove(id)
            mutableForwarding.value = mutableForwarding.value.copy(awaitingAckIds = attempts.keys.sorted(),
                candidateIds = mutableForwarding.value.candidateIds - id,
                directIds = mutableForwarding.value.directIds - id, relayIds = mutableForwarding.value.relayIds - id)
        }
        return true
    }

    /** Same retryLock, cadence, session guards and TCP writer as MESSAGE forwarding. */
    private suspend fun forwardReceipts(peer: String, session: String, kind: ForwardingKind) {
        val receipts = receiptStore ?: return
        val snapshot = inventory?.value ?: return
        if (!ready(snapshot, peer, session) || !snapshot.peerSupportsReceipts) return
        for (id in snapshot.receiptMissingOnPeer) {
            val receipt = receipts.receipt(id) ?: continue
            if (!receiptCandidate(id, peer, session) || classifyReceipt(receipt, peer, now()) != kind) continue
            val shouldSend = synchronized(forwardingLock) {
                syncForwardingSession()
                val previous = receiptAttempts[id]
                if (!matches(peer, session) || id in receiptPeerKnown ||
                    (previous != null && previous.session == session && now() - previous.at < 5000)) false
                else {
                    receiptAttempts[id] = Attempt(peer, session, receipt.hopCount, receipt.originalSourceId, now())
                    mutableForwarding.value = mutableForwarding.value.copy(receiptAwaitingAckIds = receiptAttempts.keys.sorted())
                    true
                }
            }
            if (!shouldSend) continue
            logger.log("DTN", "DTN RECEIPT $kind CANDIDATE\nreceiptId=$id\npeer=$peer", receipt.originalMessageId)
            val outgoing = receipt.copy(hopCount = receipt.hopCount + 1)
            if (!transport.sendToSession(peer, session, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = outgoing))) return
            synchronized(forwardingLock) {
                if (matches(peer, session)) mutableForwarding.value = mutableForwarding.value.copy(
                    lastReceiptSentId = id, lastReceiptSentKind = kind)
            }
            logger.log("TX", "DTN RECEIPT SEND\nreceiptId=$id\nhopCount=${outgoing.hopCount}", receipt.originalMessageId)
        }
    }

    private fun receiptCandidate(id: String, peer: String, session: String) = inventory?.value?.let {
        ready(it, peer, session) && it.peerSupportsReceipts && id in it.receiptMissingOnPeer &&
            id !in it.remoteReceiptIds && synchronized(forwardingLock) { id !in receiptPeerKnown }
    } == true

    private fun ready(snapshot: InventoryState, peer: String, session: String) =
        snapshot.peerId == peer && snapshot.sessionId == session && snapshot.remoteReceived &&
            snapshot.localReady && snapshot.sent && snapshot.error == null && matches(peer, session)

    private fun isCurrentCandidate(id: String, peer: String, session: String): Boolean = inventory?.value?.let {
        ready(it, peer, session) && id in it.missingOnPeer && id !in it.remoteMessageIds &&
            synchronized(forwardingLock) { id !in peerKnown }
    } == true

    private fun matches(peer: String, session: String) = transport.state.value.let {
        it.connectedPeerDeviceId == peer && it.sessionId == session
    }

    private fun syncForwardingSession() = synchronized(forwardingLock) {
        val connection = transport.state.value
        val old = mutableForwarding.value
        if (old.peerId != connection.peerId || old.sessionId != connection.sessionId) {
            if (attempts.isNotEmpty()) logger.log("DTN", "DTN FORWARD SESSION CLOSED; unconfirmed Room copies retained")
            attempts.clear()
            peerKnown.clear()
            receiptAttempts.clear()
            receiptPeerKnown.clear()
            mutableForwarding.value = ForwardingState(peerId = connection.peerId, sessionId = connection.sessionId)
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
            else store.replicated(id, peer, attempt.previousHop)
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
        WireProtocol.validate(packet)
        when (packet.type) {
            PacketType.MESSAGE -> {
                val message = requireNotNull(packet.message)
                val isDestination = message.destinationId == localId
                val finalForward = isDestination && message.hopCount >= 2
                require(message.hopCount >= 1) { "A received copy must have traversed a hop" }
                // The original carrier hint is immutable, not an authorization list for later relays.
                // Record knowledge before Room emits its insert so a refresh cannot echo it back.
                rememberPeerHas(message.messageId, peerId, sessionId)
                val storedStatus = if (isDestination) MessageStatus.RECEIVED else MessageStatus.CARRIED
                val ackStatus = if (isDestination) MessageStatus.DELIVERED else MessageStatus.CARRIED
                val existing = store.get(message.messageId)
                val knownReceipt = receiptStore?.receiptForMessage(message.messageId)
                require(knownReceipt == null || knownReceipt.matches(message)) { "Receipt/message identity conflict" }
                if (knownReceipt != null && existing == null) {
                    deliveryKnown(message.messageId)
                    // Do not claim custody of a copy we did not store. Inventory-driven receipt
                    // transfer informs the sender; its normal bounded retry remains safe meanwhile.
                    return
                }
                if (existing != null) {
                    require(sameBundle(existing, message)) { "UUID collision with different content" }
                    // A lost ACK causes a valid duplicate. ACK it again, even after TTL,
                    // because this exact message was already committed before expiration.
                    logger.log("DB", "Duplicate suppressed; re-ACK", message.messageId, message.ttl, existing.status.name)
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
                }
                if (receiptStore != null) {
                    val finalReceipt = if (isDestination && knownReceipt == null)
                        DeliveryReceipt.create(message, now(), receiptLifetimeMillis, receiptHopLimit) else null
                    finalReceipt?.let { WireProtocol.validate(WirePacket(PacketType.DELIVERY_RECEIPT, receipt = it)) }
                    val admission = receiptStore.commitReceived(message.copy(status = storedStatus), finalReceipt)
                    if (admission == MessageAdmission.SUPPRESSED) {
                        deliveryKnown(message.messageId)
                        return
                    }
                    if (finalReceipt != null) logger.log("DB", "DTN DELIVERY RECEIPT CREATED\nreceiptId=${finalReceipt.receiptId}", message.messageId)
                } else if (existing == null) {
                    if (!store.insert(message.copy(status = storedStatus))) {
                        val saved = store.get(message.messageId)
                        require(saved != null && sameBundle(saved, message)) { "Conflicting duplicate" }
                    }
                }
                if (existing == null) logTransfer("DB", peerId, localId, message.copy(status = storedStatus),
                    if (isDestination) "DTN FINAL DESTINATION ACCEPT; committed to Room" else "DTN RECEIVE RELAY; stored CARRIED")
                if (finalForward) {
                    logger.log("RX", "DTN FORWARD RECEIVE\npeer=$peerId\nsource=${message.sourceId}\ndestination=$localId", message.messageId)
                    // The chat belongs to original A, not carrier B. Make A selectable
                    // in the existing Conversations/Choose peer UI without changing the UI model.
                    if (store.peers.first().none { it.deviceId == message.sourceId }) {
                        store.savePeer(Peer(message.sourceId, "Via carrier (${message.sourceId.take(8)})", now()))
                    }
                }
                // Never acknowledge until the Room insert has returned successfully.
                // If storage fails, no ACK is sent and A can safely retry later.
                // A custody ACK proves only storage on B, never delivery to C.
                val ack = WirePacket(PacketType.ACK, messageId = message.messageId, ackStatus = ackStatus)
                if (sessionId != null) transport.sendToSession(peerId, sessionId, ack) else transport.send(peerId, ack)
            }
            PacketType.ACK -> {
                if (packet.ackFor == PacketType.DELIVERY_RECEIPT) {
                    val id = requireNotNull(packet.receiptId)
                    synchronized(forwardingLock) {
                        val attempt = receiptAttempts[id]
                        if (sessionId != null && matches(peerId, sessionId) && attempt?.peer == peerId && attempt.session == sessionId) {
                            receiptAttempts.remove(id)
                            receiptPeerKnown += id
                            mutableForwarding.value = mutableForwarding.value.copy(receiptAwaitingAckIds = receiptAttempts.keys.sorted(),
                                receiptPeerKnownIds = receiptPeerKnown.sorted())
                            logger.log("RX", "DTN RECEIPT STORAGE ACK\nreceiptId=$id")
                        }
                    }
                    return
                }
                val id = requireNotNull(packet.messageId)
                val changed = if (inventory != null) confirmForwarded(id, peerId, sessionId, packet.ackStatus)
                    else if (packet.ackStatus == MessageStatus.CARRIED) store.forwarded(id, localId, peerId)
                    else store.delivered(id, localId, peerId)
                if (changed) {
                    store.get(id)?.let { logTransfer("DB", localId, peerId, it,
                        if (packet.ackStatus == MessageStatus.CARRIED) "DTN RELAY ACK; copy stored, NOT final delivery" else "DTN DIRECT ACK; confirmed durable delivery") }
                } else logger.log("DROP", "Unknown, duplicate, or wrong-peer ACK", id)
            }
            PacketType.DELIVERY_RECEIPT -> receiveReceipt(peerId, sessionId, requireNotNull(packet.receipt))
            else -> Unit
        }
    }

    private suspend fun receiveReceipt(peer: String, session: String?, receipt: DeliveryReceipt) {
        val receipts = receiptStore ?: return
        require(receipt.hopCount >= 1) { "A received receipt must have traversed a hop" }
        val existing = receipts.receiptForMessage(receipt.originalMessageId)
        require(existing == null || existing.sameReceipt(receipt)) { "Conflicting delivery receipt" }
        if (existing == null && (now() >= receipt.expiresAt || receipt.createdAt > now() + 300_000)) {
            logger.log("DROP", "DTN RECEIPT expired or future timestamp\nreceiptId=${receipt.receiptId}")
            return
        }
        synchronized(forwardingLock) {
            if (session != null && matches(peer, session)) {
                syncForwardingSession()
                receiptPeerKnown += receipt.receiptId // Before Room emissions: no echo.
                mutableForwarding.value = mutableForwarding.value.copy(receiptPeerKnownIds = receiptPeerKnown.sorted())
            }
        }
        val inserted = receipts.saveReceipt(receipt) // Transaction also marks local MESSAGE delivered.
        logger.log("DB", if (inserted) "DTN RECEIPT RECEIVE; DTN DELIVERY CONFIRMED\nreceiptId=${receipt.receiptId}"
            else "DTN RECEIPT DUPLICATE\nreceiptId=${receipt.receiptId}", receipt.originalMessageId)
        deliveryKnown(receipt.originalMessageId)
        synchronized(forwardingLock) {
            if (session != null && matches(peer, session)) mutableForwarding.value = mutableForwarding.value.copy(lastReceiptReceivedId = receipt.receiptId)
        }
        val ack = WirePacket(PacketType.ACK, ackFor = PacketType.DELIVERY_RECEIPT,
            receiptId = receipt.receiptId, ackStatus = MessageStatus.CARRIED)
        if (session != null) transport.sendToSession(peer, session, ack) else transport.send(peer, ack)
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
        fun classifyReceipt(receipt: DeliveryReceipt, peer: String, at: Long): ForwardingKind? {
            if (at >= receipt.expiresAt || receipt.createdAt > at + 300_000 || receipt.hopCount >= receipt.hopLimit) return null
            if (runCatching { WireProtocol.validate(WirePacket(PacketType.DELIVERY_RECEIPT, receipt = receipt)) }.isFailure) return null
            return if (receipt.originalSourceId == peer) ForwardingKind.DIRECT else ForwardingKind.RELAY
        }
        /** Pure routing policy. Session/known/in-flight checks stay in the existing coordinator. */
        fun classify(message: Message, localId: String, peerId: String, at: Long): ForwardingKind? {
            if (routingRejection(message, localId, peerId, at) != null) return null
            return if (message.destinationId == peerId) ForwardingKind.DIRECT else ForwardingKind.RELAY
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
            stored.copy(status = incoming.status, hopCount = incoming.hopCount) == incoming
        fun isExpired(message: Message, at: Long): Boolean = at >= message.expiresAt
    }
}
