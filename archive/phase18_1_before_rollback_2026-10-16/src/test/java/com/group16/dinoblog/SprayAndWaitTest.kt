package com.group16.dinoblog

import com.group16.dinoblog.dtn.*
import com.group16.dinoblog.model.*
import com.group16.dinoblog.network.*
import com.group16.dinoblog.repository.*
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class SprayAndWaitTest {
    private fun id() = UUID.randomUUID().toString()
    private val at = 2000L
    private fun row(copies: Int = 4, destination: String = id()) = Message(id(), id(), destination, "data", 1000, 60,
        MessageStatus.CARRIED, hopCount = 1, copiesRemaining = copies)
    private class Wire(peer: String) : MessageTransport {
        override val state = MutableStateFlow(SocketState("Connected", peer, sessionId = "s"))
        override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
        val sent = mutableListOf<WirePacket>()
        var onSend: suspend (WirePacket) -> Unit = {}
        var beforeWrite: suspend () -> Unit = {}
        var succeeds = true
        override suspend fun send(peerId: String, packet: WirePacket): Boolean {
            sent += packet; onSend(packet); return succeeds
        }
        override suspend fun sendIfCurrent(peerId: String, sessionId: String, packet: WirePacket, eligible: suspend () -> Boolean): Boolean {
            beforeWrite()
            if (!eligible() || state.value.peerId != peerId || state.value.sessionId != sessionId) return false
            return send(peerId, packet)
        }
    }
    private fun inventory(peer: String, vararg ids: String) = MutableStateFlow(InventoryState(peerId = peer,
        sessionId = "s", sent = true, localReady = true, remoteReceived = true, missingOnPeer = ids.toList()))

    @Test fun binarySplitsPersistBeforeWriteForEvenAndOddBudgets(): Unit = runTest {
        for ((total, keep, give) in listOf(Triple(4,2,2), Triple(3,2,1), Triple(2,1,1))) {
            val m = row(total); val local = id(); val peer = id(); val store = ReceiptTestStore(); store.insert(m)
            val wire = Wire(peer); val inv = inventory(peer, m.messageId)
            val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), inv, now = { at }, smartRelaySelection = false)
            wire.onSend = { p ->
                assertEquals(keep, store.get(m.messageId)!!.copiesRemaining)
                assertEquals(give, p.message!!.copiesRemaining)
            }
            engine.retryPending()
            val outgoing = wire.sent.single().message!!
            assertEquals(m.copy(status = MessageStatus.PENDING, hopCount = 2, copiesRemaining = give), outgoing)
            assertEquals(m.copy(copiesRemaining = keep), store.get(m.messageId))
            assertEquals(total, store.get(m.messageId)!!.copiesRemaining + outgoing.copiesRemaining)
        }
    }

    @Test fun sourceInitializesOnlyNewRowsAndWaitCanOnlyDeliverDirect(): Unit = runTest {
        val local = id(); val peer = id(); val store = ReceiptTestStore(); val wire = Wire(peer)
        val inv = inventory(peer)
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), inv, now = { at }, smartRelaySelection = false)
        val new = engine.queue(id(), "source")
        assertEquals(DtnConfig.INITIAL_SPRAY_COPIES, new.copiesRemaining)
        val wait = row(1); store.insert(wait)
        inv.value = inv.value.copy(missingOnPeer = listOf(wait.messageId))
        engine.retryPending(); assertTrue(wire.sent.isEmpty())
        assertEquals(ForwardingKind.WAIT, DeliveryEngine.classify(wait, local, peer, at))
        wire.state.value = SocketState("Connected", wait.destinationId, sessionId = "dest")
        inv.value = inv.value.copy(peerId = wait.destinationId, sessionId = "dest")
        engine.retryPending()
        assertEquals(ForwardingKind.DIRECT, engine.forwarding.value.lastSentKind)
        assertEquals(1, wire.sent.single().message!!.copiesRemaining)
        assertEquals(1, store.get(wait.messageId)!!.copiesRemaining)
    }

    @Test fun knownPeerDeliveredExpiredAndHopBlockedNeverConsumeBudget(): Unit = runTest {
        val peer = id(); val local = id(); val store = ReceiptTestStore(); val wire = Wire(peer)
        val known = row(); val delivered = row(); val expired = row().copy(expiresAt = 2000, ttl = 1)
        val blocked = row().copy(hopLimit = 1); val invalid = row().copy(copiesRemaining = 0)
        listOf(known, delivered, expired, blocked, invalid).forEach { store.insert(it) }
        store.saveReceipt(DeliveryReceipt.create(delivered, delivered.destinationId, 1500))
        val inv = inventory(peer, known.messageId, delivered.messageId, expired.messageId, blocked.messageId, invalid.messageId)
        inv.value = inv.value.copy(remoteMessageIds = listOf(known.messageId))
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), inv, now = { at }, smartRelaySelection = false)
        engine.retryPending(); assertTrue(wire.sent.isEmpty())
        listOf(known, delivered, expired, blocked).forEach { assertEquals(4, store.get(it.messageId)!!.copiesRemaining) }
    }

    @Test fun lostAckRetriesSameBranchWithoutReservingAgainEvenWhenRetainedCopyIsWait(): Unit = runTest {
        val m = row(2); val peer = id(); val local = id(); var clock = at
        val store = ReceiptTestStore(); store.insert(m); val wire = Wire(peer)
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), inventory(peer, m.messageId), now = { clock }, smartRelaySelection = false)
        engine.retryPending(); repeat(4) { engine.retryPending() }
        assertEquals(1, wire.sent.size); assertEquals(1, store.get(m.messageId)!!.copiesRemaining)
        clock += 5000; engine.retryPending()
        assertEquals(2, wire.sent.size); assertEquals(wire.sent[0], wire.sent[1])
        assertEquals(1, store.get(m.messageId)!!.copiesRemaining)
        engine.receive(peer, WirePacket(PacketType.ACK, messageId = m.messageId, ackStatus = MessageStatus.CARRIED))
        clock += 10000; engine.retryPending(); assertEquals(2, wire.sent.size)
    }

    @Test fun duplicateIncomingBudgetIsIgnoredAndStillAcked(): Unit = runTest {
        val m = row(1); val local = id(); val peer = id(); val store = ReceiptTestStore(); store.insert(m)
        val wire = Wire(peer); val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), now = { at }, smartRelaySelection = false)
        engine.receive(peer, WirePacket(PacketType.MESSAGE, message = m.copy(status = MessageStatus.PENDING, copiesRemaining = 2)))
        assertEquals(m, store.get(m.messageId)); assertEquals(1, store.rows.size)
        assertEquals(PacketType.ACK, wire.sent.single().type)
    }

    @Test fun ambiguousDisconnectAndEngineRestartNeverRestoreUncertainCopies(): Unit = runTest {
        val m = row(2); val peer = id(); val local = id(); val store = ReceiptTestStore(); store.insert(m)
        val wire = Wire(peer); wire.succeeds = false
        val inv = inventory(peer, m.messageId)
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), inv, now = { at }, smartRelaySelection = false)
        engine.retryPending(); assertEquals(1, store.get(m.messageId)!!.copiesRemaining)
        wire.state.value = SocketState(); engine.retryPending()
        assertTrue(engine.forwarding.value.awaitingAckIds.isEmpty())
        wire.state.value = SocketState("Connected", peer, sessionId = "new")
        inv.value = inv.value.copy(sessionId = "new")
        wire.sent.clear(); wire.succeeds = true
        val restarted = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), inv, now = { at }, smartRelaySelection = false)
        restarted.retryPending(); assertTrue(wire.sent.isEmpty())
        assertEquals(1, store.get(m.messageId)!!.copiesRemaining)
    }

    @Test fun definitiveBufferRejectRestoresOnceAndBlocksFurtherSessionRetries(): Unit = runTest {
        val m = row(2); val peer = id(); val local = id(); val store = ReceiptTestStore(); store.insert(m)
        val wire = Wire(peer); val inv = inventory(peer, m.messageId); var clock = at
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), inv, now = { clock }, smartRelaySelection = false)
        val reject = WirePacket(PacketType.MESSAGE_REJECT, messageId = m.messageId, reason = MessageRejectReason.BUFFER_FULL)
        wire.onSend = { engine.receive(peer, reject) }
        engine.retryPending(); assertEquals(2, store.get(m.messageId)!!.copiesRemaining)
        repeat(3) { engine.receive(peer, reject); clock += 10000; engine.retryPending() }
        assertEquals(1, wire.sent.size); assertEquals(2, store.get(m.messageId)!!.copiesRemaining)
        assertEquals(listOf(m.messageId), engine.forwarding.value.rejectedIds)
        wire.state.value = SocketState("Connected", peer, sessionId = "new"); inv.value = inv.value.copy(sessionId = "new")
        wire.onSend = {}; engine.retryPending()
        assertEquals(1, store.get(m.messageId)!!.copiesRemaining)
        assertTrue(engine.forwarding.value.rejectedIds.isEmpty())
    }

    @Test fun fullRelayQuotaRejectsNewPayloadButNotDuplicatesSourcesDestinationsOrReceipts(): Unit = runTest {
        val local = id(); val peer = id(); val store = ReceiptTestStore(); val existing = List(2) { row(1) }
        existing.forEach { store.insert(it) }
        val wire = Wire(peer); val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), relayBufferLimit = 2, now = { at }, smartRelaySelection = false)
        val incoming = row(1).copy(status = MessageStatus.PENDING)
        engine.receive(peer, WirePacket(PacketType.MESSAGE, message = incoming))
        assertEquals(PacketType.MESSAGE_REJECT, wire.sent.last().type); assertNull(store.get(incoming.messageId))
        assertEquals(2, store.relayBufferUsage(local, at))
        // Sticky rejection remains definitive even after capacity becomes available.
        store.rows.remove(existing[0].messageId)
        engine.receive(peer, WirePacket(PacketType.MESSAGE, message = incoming))
        assertEquals(PacketType.MESSAGE_REJECT, wire.sent.last().type); assertNull(store.get(incoming.messageId))
        store.insert(existing[0])
        engine.receive(peer, WirePacket(PacketType.MESSAGE, message = existing[1].copy(status = MessageStatus.PENDING, copiesRemaining = 2)))
        assertEquals(PacketType.ACK, wire.sent.last().type); assertEquals(1, store.get(existing[1].messageId)!!.copiesRemaining)
        val final = row(1, local).copy(status = MessageStatus.PENDING)
        engine.receive(peer, WirePacket(PacketType.MESSAGE, message = final))
        assertEquals(MessageStatus.RECEIVED, store.get(final.messageId)!!.status)
        assertNotNull(store.receipt(final.messageId)); assertEquals(PacketType.ACK, wire.sent.last().type)
        val tombstoneOnly = row(); engine.receive(peer, WirePacket(PacketType.DELIVERY_RECEIPT,
            receipt = DeliveryReceipt.create(tombstoneOnly, tombstoneOnly.destinationId, at)))
        assertEquals(2, store.relayBufferUsage(local, at))
    }

    @Test fun housekeepingReclaimsOnlyForeignExpiredDeliveredPayloadsAndKeepsTombstones(): Unit = runTest {
        val local = id(); val store = ReceiptTestStore()
        val expired = row().copy(ttl = 1, expiresAt = 2000)
        val delivered = row(); val active = row(); val own = row().copy(sourceId = local)
        val final = row(destination = local).copy(status = MessageStatus.RECEIVED)
        listOf(expired, delivered, active, own, final).forEach { store.insert(it) }
        val r = DeliveryReceipt.create(delivered, delivered.destinationId, 1500); store.saveReceipt(r)
        assertEquals(1, store.relayBufferUsage(local, at))
        val incoming = row(1)
        assertTrue(store.commitReceived(incoming, null, local, at, 2))
        assertNull(store.get(expired.messageId)); assertNull(store.get(delivered.messageId))
        assertEquals(r, store.receipt(delivered.messageId)); assertNotNull(store.get(own.messageId)); assertNotNull(store.get(final.messageId))
        assertEquals(2, store.relayBufferUsage(local, at))
        assertFalse(store.commitReceived(delivered, null, local, at, 2))
    }

    @Test fun multipleMessagesSendDirectThenSprayAndNeverSendWait(): Unit = runTest {
        val peer = id(); val local = id(); val store = ReceiptTestStore()
        val four = row(4); val two = row(2); val wait = row(1); val direct = row(1, peer)
        val ms = listOf(four,two,wait,direct); ms.forEach { store.insert(it) }
        val wire = Wire(peer); val inv = inventory(peer, *ms.map { it.messageId }.toTypedArray())
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), inv, now = { at }, smartRelaySelection = false)
        engine.retryPending()
        assertEquals(direct.messageId, wire.sent.first().message!!.messageId)
        assertEquals(setOf(direct.messageId,four.messageId,two.messageId), wire.sent.map { it.message!!.messageId }.toSet())
        assertEquals(2,store.get(four.messageId)!!.copiesRemaining); assertEquals(1,store.get(two.messageId)!!.copiesRemaining)
        assertEquals(listOf(wait.messageId),engine.forwarding.value.waitIds)
    }

    @Test fun concurrentReservationCannotSplitSameTwoCopiesTwice(): Unit = runTest {
        val m = row(2); val store = ReceiptTestStore(); store.insert(m); val local = id()
        val results = listOf(async { store.reserveSpray(m.messageId,local,id(),2,at) }, async { store.reserveSpray(m.messageId,local,id(),2,at) }).awaitAll()
        assertEquals(1,results.count { it != null }); assertEquals(1,store.get(m.messageId)!!.copiesRemaining)
    }

    @Test fun finalWriterChecksReceiptAndSessionBeforeReservingBudget(): Unit = runTest {
        for (receiptArrives in listOf(true,false)) {
            val m = row(); val peer = id(); val local = id(); val store = ReceiptTestStore(); store.insert(m)
            val wire = Wire(peer); val inv = inventory(peer,m.messageId)
            val engine = DeliveryEngine(local,store,wire,backgroundScope,NetworkLogger(),inv,now = { at }, smartRelaySelection = false)
            wire.beforeWrite = {
                if (receiptArrives) store.saveReceipt(DeliveryReceipt.create(m,m.destinationId,at))
                else wire.state.value = SocketState("Connected",id(),sessionId="new")
            }
            engine.retryPending(); assertTrue(wire.sent.isEmpty()); assertEquals(4,store.get(m.messageId)!!.copiesRemaining)
        }
    }

    @Test fun wireBudgetAndBufferRejectRoundTripWithConservativeMissingField() {
        val m = row(2).copy(status=MessageStatus.PENDING)
        val packets = listOf(WirePacket(PacketType.MESSAGE,message=m),
            WirePacket(PacketType.MESSAGE_REJECT,messageId=m.messageId,reason=MessageRejectReason.BUFFER_FULL))
        packets.forEach { p -> val bytes=ByteArrayOutputStream();WireProtocol.write(DataOutputStream(bytes),p)
            assertEquals(p,WireProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))) }
        val legacy = String(WireProtocol.encode(packets[0]),Charsets.UTF_8).replace(Regex(",?\"copiesRemaining\":2"),"")
        val bytes=ByteArrayOutputStream();DataOutputStream(bytes).apply { val raw=legacy.toByteArray();writeInt(raw.size);write(raw) }
        assertEquals(1,WireProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))).message!!.copiesRemaining)
        for (copies in listOf(0,-1,256)) assertTrue(runCatching { WireProtocol.validate(packets[0].copy(message=m.copy(copiesRemaining=copies))) }.isFailure)
    }
    @Test fun lostRejectRemainsDefinitiveAfterReceiverCapacityFrees(): Unit = runTest {
        val a = id(); val b = id(); val m = row(2).copy(sourceId = a)
        val senderStore = ReceiptTestStore(); senderStore.insert(m)
        val receiverStore = ReceiptTestStore(); val occupied = row(1); receiverStore.insert(occupied)
        val senderWire = Wire(b); val receiverWire = Wire(a); var clock = at; var dropReject = true
        val sender = DeliveryEngine(a,senderStore,senderWire,backgroundScope,NetworkLogger(),inventory(b,m.messageId),now={clock}, smartRelaySelection = false)
        val receiver = DeliveryEngine(b,receiverStore,receiverWire,backgroundScope,NetworkLogger(),relayBufferLimit=1,now={clock}, smartRelaySelection = false)
        senderWire.onSend = { receiver.receive(a,it) }
        receiverWire.onSend = { if (dropReject) dropReject = false else sender.receive(b,it) }
        sender.retryPending()
        assertEquals(1,senderStore.get(m.messageId)!!.copiesRemaining)
        receiverStore.rows.remove(occupied.messageId)
        clock += 5000; sender.retryPending()
        assertNull(receiverStore.get(m.messageId))
        assertEquals(2,senderStore.get(m.messageId)!!.copiesRemaining)
        assertEquals(2,receiverWire.sent.count { it.type == PacketType.MESSAGE_REJECT })
        // A later tombstone overrides even the sticky buffer rejection.
        val receipt = DeliveryReceipt.create(m,m.destinationId,at)
        receiverStore.saveReceipt(receipt)
        receiver.receive(a,WirePacket(PacketType.MESSAGE,message=m.copy(status=MessageStatus.PENDING,hopCount=2,copiesRemaining=1)))
        assertEquals(PacketType.ACK,receiverWire.sent.last().type)
        assertNull(receiverStore.get(m.messageId))
    }

}
