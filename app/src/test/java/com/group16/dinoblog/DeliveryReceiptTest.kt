package com.group16.dinoblog

import com.group16.dinoblog.dtn.*
import com.group16.dinoblog.model.*
import com.group16.dinoblog.network.*
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryReceiptTest {
    private fun id() = UUID.randomUUID().toString()
    private val at = 2000L
    private fun message(a: String = id(), d: String = id()) = Message(id(), a, d, "body", 1000, 60,
        MessageStatus.PENDING, hopCount = 1, copiesRemaining = 4)
    private class Wire(peer: String) : MessageTransport {
        override val state = MutableStateFlow(SocketState("Connected", peer, sessionId = "s"))
        override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
        val sent = mutableListOf<WirePacket>()
        var beforeWrite: suspend () -> Unit = {}
        override suspend fun send(peerId: String, packet: WirePacket): Boolean { sent += packet; return true }
        override suspend fun sendIfCurrent(peerId: String, sessionId: String, packet: WirePacket, eligible: suspend () -> Boolean): Boolean {
            beforeWrite()
            return if (eligible()) sendToSession(peerId, sessionId, packet) else false
        }
    }
    private fun snapshot(peer: String, ids: List<String>) = MutableStateFlow(InventoryState(peerId = peer,
        sessionId = "s", localReady = true, sent = true, remoteReceived = true, missingOnPeer = ids))

    @Test fun destinationCreatesReceiptAfterPersistenceAndRelayDoesNot(): Unit = runTest {
        val m = message(); val peer = id()
        for (local in listOf(id(), m.destinationId)) {
            val store = ReceiptTestStore(); val wire = Wire(peer)
            val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), now = { at }, smartRelaySelection = false)
            engine.receive(peer, WirePacket(PacketType.MESSAGE, message = m))
            assertEquals(1, store.rows.size)
            assertEquals(local == m.destinationId, store.receipt(m.messageId) != null)
            assertEquals(if (local == m.destinationId) MessageStatus.DELIVERED else MessageStatus.CARRIED, wire.sent.single().ackStatus)
            engine.receive(peer, WirePacket(PacketType.MESSAGE, message = m))
            assertEquals(1, store.rows.size)
            assertEquals(if (local == m.destinationId) 1 else 0, store.receipts.value.size)
        }
        assertTrue(runCatching { DeliveryReceipt.create(m, peer, at) }.isFailure)
    }

    @Test fun storageFailureProducesNeitherReceiptNorAck(): Unit = runTest {
        val m = message(); val peer = id(); val store = ReceiptTestStore(); store.failReceipt = true
        val wire = Wire(peer); val engine = DeliveryEngine(m.destinationId, store, wire, backgroundScope, NetworkLogger(), now = { at }, smartRelaySelection = false)
        assertTrue(runCatching { engine.receive(peer, WirePacket(PacketType.MESSAGE, message = m)) }.isFailure)
        assertTrue(store.rows.isEmpty()); assertTrue(store.receipts.value.isEmpty()); assertTrue(wire.sent.isEmpty())
    }

    @Test fun receiptBeforeMessageIsTombstoneAndDuplicatePreservesEvent(): Unit = runTest {
        val m = message(); val r = DeliveryReceipt.create(m, m.destinationId, at); val peer = id(); val local = id()
        val store = ReceiptTestStore(); val wire = Wire(peer)
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), now = { at }, smartRelaySelection = false)
        engine.receive(peer, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = r))
        engine.receive(peer, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = r.copy(deliveredAt = at + 1)))
        engine.receive(peer, WirePacket(PacketType.MESSAGE, message = m))
        assertEquals(listOf(r), store.receipts.value); assertTrue(store.rows.isEmpty())
        assertEquals(2, wire.sent.count { it.type == PacketType.DELIVERY_RECEIPT_ACK })
        assertEquals(PacketType.ACK, wire.sent.last().type)
    }

    @Test fun receiptsUpdateSourceAndRelayAndSuppressBothRoutingKinds(): Unit = runTest {
        val m = message(); val r = DeliveryReceipt.create(m, m.destinationId, at)
        for (local in listOf(m.sourceId, id())) for (peer in listOf(m.destinationId, id())) {
            val store = ReceiptTestStore(); store.insert(m.copy(status = MessageStatus.CARRIED))
            val wire = Wire(peer); val inv = snapshot(peer, listOf(m.messageId))
            val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), inv, now = { at }, smartRelaySelection = false)
            engine.receive(peer, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = r))
            engine.retryPending()
            assertEquals(MessageStatus.DELIVERED, store.get(m.messageId)!!.status)
            assertEquals(1, store.get(m.messageId)!!.hopCount)
            assertFalse(wire.sent.any { it.type == PacketType.MESSAGE })
        }
    }

    @Test fun remoteSummaryBlocksMessageUntilMetadataAndQueuedReceiptCancelsWrite(): Unit = runTest {
        val m = message(); val peer = id(); val store = ReceiptTestStore(); store.insert(m)
        val wire = Wire(peer); val inv = snapshot(peer, listOf(m.messageId))
        val engine = DeliveryEngine(id(), store, wire, backgroundScope, NetworkLogger(), inv, now = { at }, smartRelaySelection = false)
        inv.value = inv.value.copy(remoteDeliveredMessageIds = listOf(m.messageId))
        engine.retryPending(); assertTrue(wire.sent.isEmpty())
        inv.value = inv.value.copy(remoteDeliveredMessageIds = emptyList())
        wire.beforeWrite = { store.saveReceipt(DeliveryReceipt.create(m, m.destinationId, at)) }
        engine.retryPending(); assertFalse(wire.sent.any { it.type == PacketType.MESSAGE })
    }

    @Test fun onlyMissingReceiptsSentWithRetryAckAndNoEcho(): Unit = runTest {
        val peer = id(); val store = ReceiptTestStore()
        val rs = List(3) { val m = message(); DeliveryReceipt.create(m, m.destinationId, at) }
        rs.forEach { store.saveReceipt(it) }
        val wire = Wire(peer); val inv = snapshot(peer, emptyList())
        inv.value = inv.value.copy(remoteDeliveredMessageIds = listOf(rs[1].originalMessageId),
            missingReceiptsOnPeer = listOf(rs[0].originalMessageId, rs[2].originalMessageId))
        var clock = at
        val engine = DeliveryEngine(id(), store, wire, backgroundScope, NetworkLogger(), inv, now = { clock }, smartRelaySelection = false)
        engine.retryPending(); engine.retryPending()
        assertEquals(2, wire.sent.count { it.type == PacketType.DELIVERY_RECEIPT })
        clock += 5001; engine.retryPending()
        assertEquals(4, wire.sent.count { it.type == PacketType.DELIVERY_RECEIPT })
        for (r in listOf(rs[0], rs[2])) engine.receive(peer, WirePacket(PacketType.DELIVERY_RECEIPT_ACK, messageId = r.originalMessageId))
        clock += 5001; engine.retryPending()
        assertEquals(4, wire.sent.count { it.type == PacketType.DELIVERY_RECEIPT })
        assertTrue(engine.forwarding.value.inFlightReceiptIds.isEmpty())
        // Receipt from peer proves knowledge even if its inventory was old.
        val m = message(); val extra = DeliveryReceipt.create(m, m.destinationId, at)
        engine.receive(peer, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = extra))
        inv.value = inv.value.copy(missingReceiptsOnPeer = inv.value.missingReceiptsOnPeer + m.messageId)
        engine.retryPending()
        assertFalse(wire.sent.any { it.receipt?.originalMessageId == m.messageId })
    }

    @Test fun receiptInventoryWireRoundTripAndMetadataValidation() {
        val m = message(); val r = DeliveryReceipt.create(m, m.destinationId, at)
        for (packet in listOf(WirePacket(PacketType.DELIVERY_RECEIPT, receipt = r),
            WirePacket(PacketType.INVENTORY, deviceId = id(), messageIds = listOf(m.messageId), deliveredMessageIds = listOf(m.messageId)),
            WirePacket(PacketType.DELIVERY_RECEIPT_ACK, messageId = m.messageId))) {
            val bytes = ByteArrayOutputStream(); WireProtocol.write(DataOutputStream(bytes), packet)
            assertEquals(packet, WireProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))))
        }
        assertTrue(runCatching { WireProtocol.validateReceipt(r.copy(createdByDeviceId = m.sourceId)) }.isFailure)
        assertEquals(listOf("a", "c") to emptyList<String>(), InventoryManager.compare(listOf("a", "b", "c"), listOf("b")))
    }
    @Test fun mixedEncounterPrioritizesReceiptThenDirectThenRelay(): Unit = runTest {
        val local = id(); val peer = id(); val store = ReceiptTestStore()
        val delivered = message(local, id())
        val direct = message(local, peer)
        val relay = message(local, id())
        for (m in listOf(delivered, direct, relay)) store.insert(m)
        val r = DeliveryReceipt.create(delivered, delivered.destinationId, at)
        store.saveReceipt(r)
        val wire = Wire(peer)
        val inv = snapshot(peer, listOf(delivered.messageId, relay.messageId, direct.messageId))
        inv.value = inv.value.copy(missingReceiptsOnPeer = listOf(delivered.messageId))
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), inv, now = { at }, smartRelaySelection = false)
        engine.retryPending()
        assertEquals(listOf(PacketType.DELIVERY_RECEIPT), wire.sent.map { it.type })
        engine.receive(peer, WirePacket(PacketType.DELIVERY_RECEIPT_ACK, messageId = delivered.messageId))
        engine.retryPending()
        assertEquals(listOf(direct.messageId, relay.messageId), wire.sent.mapNotNull { it.message?.messageId })
        assertFalse(engine.forwarding.value.candidateIds.contains(delivered.messageId))
    }

    @Test fun receiptQueryFromOldSessionCannotWriteToReconnectedPeer(): Unit = runTest {
        val oldPeer = id(); val newPeer = id(); val store = ReceiptTestStore()
        val m = message(); store.saveReceipt(DeliveryReceipt.create(m, m.destinationId, at))
        val wire = Wire(oldPeer); val inv = snapshot(oldPeer, emptyList())
        inv.value = inv.value.copy(missingReceiptsOnPeer = listOf(m.messageId))
        val engine = DeliveryEngine(id(), store, wire, backgroundScope, NetworkLogger(), inv, now = { at }, smartRelaySelection = false)
        wire.beforeWrite = {
            wire.state.value = SocketState("Connected", newPeer, sessionId = "new")
            inv.value = InventoryState(peerId = newPeer, sessionId = "new")
        }
        engine.retryPending()
        assertTrue(wire.sent.isEmpty())
        engine.retryPending()
        assertTrue(engine.forwarding.value.inFlightReceiptIds.isEmpty())
    }

    @Test fun inventoryComparesReceiptSetsAndRefreshesAfterDurableReceipt(): Unit = runTest {
        val peer = id(); val store = ReceiptTestStore(); val wire = Wire(peer)
        val rs = List(3) { val m = message(); DeliveryReceipt.create(m, m.destinationId, at) }
        rs.forEach { store.saveReceipt(it) }
        val manager = InventoryManager(id(), store, wire, backgroundScope, NetworkLogger(), now = { at })
        manager.start(); runCurrent()
        manager.receive(peer, "s", WirePacket(PacketType.INVENTORY, deviceId = peer, messageIds = emptyList(),
            deliveredMessageIds = listOf(rs[1].originalMessageId)))
        assertEquals(listOf(rs[0].originalMessageId, rs[2].originalMessageId).sorted(), manager.state.value.missingReceiptsOnPeer)
        assertTrue(manager.state.value.missingReceiptsLocally.isEmpty())
        assertEquals(rs.map { it.originalMessageId }.sorted(), wire.sent.first { it.type == PacketType.INVENTORY }.deliveredMessageIds)
        wire.state.value = SocketState(); runCurrent()
        assertTrue(manager.state.value.remoteDeliveredMessageIds.isEmpty())
        assertTrue(manager.state.value.missingReceiptsOnPeer.isEmpty())
        assertEquals(3, store.receipts.value.size)
    }

}
