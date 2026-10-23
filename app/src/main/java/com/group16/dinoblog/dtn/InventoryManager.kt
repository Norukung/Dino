package com.group16.dinoblog.dtn

import com.group16.dinoblog.model.InventoryState
import com.group16.dinoblog.network.MessageTransport
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.repository.ReceiptStore
import com.group16.dinoblog.repository.InventoryStore
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Advertise IDs and compare sets. DeliveryEngine consumes the result; no MESSAGE is sent here. */
class InventoryManager(
    private val localId: String,
    private val store: InventoryStore,
    private val transport: MessageTransport,
    private val scope: CoroutineScope,
    private val logger: NetworkLogger,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(InventoryState())
    val state = mutableState.asStateFlow()
    private val stateLock = Any()
    private var started = false
    private val refreshLock = Mutex()
    private data class LocalInventory(val messages: List<String>, val delivered: List<String>)

    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Session token, not just peer UUID: same-peer reconnect must exchange again.
            transport.state.map { it.peerId to it.sessionId }.distinctUntilChanged().collectLatest { (peer, session) ->
                if (peer == null || session == null) {
                    synchronized(stateLock) {
                        val connection = transport.state.value
                        if (connection.peerId == null || connection.sessionId == null) {
                            val old = mutableState.value
                            mutableState.value = InventoryState(localMessageIds = old.localMessageIds,
                                localDeliveredMessageIds = old.localDeliveredMessageIds, localReady = old.localReady, builtAt = old.builtAt)
                        }
                    }
                } else {
                    if (update(peer, session) { it }) safely(peer, session) {
                        val builtAt = now()
                        val ids = build(builtAt)
                        if (!updateLocal(peer, session, ids, builtAt)) return@safely
                        // Session-scoped send prevents a slow Room read from leaking
                        // an old snapshot into a different connection to the same peer.
                        val sent = transport.sendToSession(peer, session,
                            WirePacket(PacketType.INVENTORY, deviceId = localId, messageIds = ids.messages, deliveredMessageIds = ids.delivered))
                        if (update(peer, session) { it.copy(sent = sent, error = if (sent) null else "Inventory send failed; reconnect to retry") } && sent) {
                            logger.log("TX", "DTN INVENTORY SEND\npeer=$peer\ncount=${ids.messages.size}")
                            refreshLocal()
                        }
                    }
                }
            }
        }
        scope.launch { store.inventoryChanges.collect { refreshLocal() } }
    }

    // Recompare after Room changes without pretending the remote snapshot changed.
    // DeliveryEngine's session-known IDs prevent echo against that older snapshot.
    private suspend fun refreshLocal() = refreshLock.withLock {
        val current = mutableState.value
        val peer = current.peerId ?: return@withLock
        val session = current.sessionId ?: return@withLock
        if (!current.sent || !current.remoteReceived || !matches(peer, session)) return@withLock
        safely(peer, session) {
            val at = now()
            updateLocal(peer, session, build(at), at)
        }
    }

    suspend fun receive(peerId: String, sessionId: String, packet: WirePacket) {
        if (!matches(peerId, sessionId)) return
        safely(peerId, sessionId) {
            WireProtocol.validate(packet)
            require(packet.type == PacketType.INVENTORY && packet.deviceId == peerId) { "Inventory identity does not match HELLO" }
            val remote = requireNotNull(packet.messageIds).distinct().sorted()
            logger.log("RX", "DTN INVENTORY RECEIVE\npeer=$peerId\nmessages=$remote")
            val remoteDelivered = packet.deliveredMessageIds.distinct().sorted()
            // Publish delivery suppression before any suspending Room read. A
            // previously queued send must see this new summary immediately.
            if (!update(peerId, sessionId) { it.copy(remoteDeliveredMessageIds = remoteDelivered, remoteReceived = false) }) return@safely
            // Read Room again for comparison. Do not assume the snapshot sent earlier
            // is still eligible after direct ACKs, expiry, or local message deletion.
            val builtAt = now()
            val local = build(builtAt)
            val comparison = compare(local.messages, remote)
            val receipts = compare(local.delivered, remoteDelivered)
            logger.log("DTN", "Receipt inventory peer=$peerId delivered=$remoteDelivered missingOnPeer=${receipts.first}")
            if (update(peerId, sessionId) {
                it.copy(localMessageIds = local.messages, localReady = true, builtAt = builtAt,
                    remoteMessageIds = remote, remoteReceived = true,
                    localDeliveredMessageIds = local.delivered, remoteDeliveredMessageIds = remoteDelivered,
                    missingReceiptsOnPeer = receipts.first, missingReceiptsLocally = receipts.second,
                    missingOnPeer = comparison.first, missingLocally = comparison.second, error = null)
            }) {
                logger.log("DTN", "DTN INVENTORY COMPARE\npeer=$peerId\nlocal=$local\nremote=$remote\nmissingOnPeer=${comparison.first}\nmissingLocally=${comparison.second}")
            }
            // DeliveryEngine observes this session's comparison independently.
        }
    }

    private suspend fun build(at: Long): LocalInventory {
        val ids = (store.forwardableMessageIds(at) + store.receivedMessageIds(at))
            .filter(WireProtocol::isUuid).distinct().sorted()
        // Never silently truncate: that would falsely classify IDs as absent.
        val delivered = (store as? ReceiptStore)?.receiptIds().orEmpty().distinct().sorted()
        require(ids.size + delivered.size <= WireProtocol.MAX_INVENTORY_IDS) {
            "Inventory exceeds ${WireProtocol.MAX_INVENTORY_IDS} IDs; no partial inventory sent"
        }
        logger.log("DTN", "DTN INVENTORY BUILD\nlocalDevice=$localId\nmessages=$ids")
        return LocalInventory(ids, delivered)
    }

    private fun matches(peer: String, session: String): Boolean = transport.state.value.let {
        it.connectedPeerDeviceId == peer && it.sessionId == session
    }

    private fun update(peer: String, session: String, transform: (InventoryState) -> InventoryState): Boolean =
        synchronized(stateLock) {
            if (!matches(peer, session)) return@synchronized false
            val current = mutableState.value
            // A fast peer may send INVENTORY before the StateFlow collector runs.
            // Initialize once, and do not erase its result when that collector catches up.
            val base = if (current.peerId == peer && current.sessionId == session) current
                else InventoryState(peerId = peer, sessionId = session)
            mutableState.value = transform(base)
            true
        }

    private fun updateLocal(peer: String, session: String, local: LocalInventory, at: Long): Boolean = update(peer, session) {
        val comparison = if (it.remoteReceived) compare(local.messages, it.remoteMessageIds) else emptyList<String>() to emptyList()
        val receipts = if (it.remoteReceived) compare(local.delivered, it.remoteDeliveredMessageIds) else emptyList<String>() to emptyList()
        it.copy(localMessageIds = local.messages, localDeliveredMessageIds = local.delivered,
            missingReceiptsOnPeer = receipts.first, missingReceiptsLocally = receipts.second,
            localReady = true, builtAt = at,
            missingOnPeer = comparison.first, missingLocally = comparison.second, error = null)
    }

    private suspend fun safely(peer: String, session: String, block: suspend () -> Unit) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (update(peer, session) { it.copy(error = e.message ?: "Inventory failed") }) {
                logger.log("ERROR", "DTN INVENTORY ERROR\npeer=$peer\n${e.message}")
            }
        }
    }

    companion object {
        /** Both directions are set differences of eligible snapshots, not delivery proof. */
        fun compare(local: List<String>, remote: List<String>): Pair<List<String>, List<String>> {
            val localSet = local.toSet()
            val remoteSet = remote.toSet()
            return (localSet - remoteSet).sorted() to (remoteSet - localSet).sorted()
        }
    }
}
