package com.group16.dinoblog

import com.group16.dinoblog.dtn.*
import com.group16.dinoblog.model.*
import com.group16.dinoblog.network.*
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.io.*

@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryReceiptTest {
    private fun id() = UUID.randomUUID().toString()
    private val a = id(); private val b = id(); private val c = id(); private val d = id(); private val e = id()
    private val time = 1700000000000L
    private fun message(destination: String = d) = Message(id(), a, destination, "Hello destination", time, 60,
        MessageStatus.PENDING, hopCount = 2, hopLimit = 4)
    private fun receipt(m: Message = message()) = DeliveryReceipt.create(m, time + 1).copy(hopCount = 1)
    private class Wire : MessageTransport {
        override val state = MutableStateFlow(SocketState())
        override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
        val sent = mutableListOf<WirePacket>()
        var succeeds = true
        var onSend: suspend (WirePacket) -> Unit = {}
        fun connect(peer: String, session: String = "one") { state.value = SocketState("Connected", peer, sessionId = session) }
        override suspend fun send(peerId: String, packet: WirePacket): Boolean {
            if (!succeeds || state.value.peerId != peerId) return false
            sent += packet; onSend(packet); return true
        }
    }
    private fun snapshot(peer: String, receipts: List<DeliveryReceipt> = emptyList(), messages: List<Message> = emptyList(), session: String = "one") =
        InventoryState(peerId = peer, sessionId = session, localReady = true, remoteReceived = true, sent = true,
            missingOnPeer = messages.map { it.messageId }, localReceiptIds = receipts.map { it.receiptId },
            receiptMissingOnPeer = receipts.map { it.receiptId }, peerSupportsReceipts = true)
    private fun receiptAck(r: DeliveryReceipt) = WirePacket(PacketType.ACK, receiptId = r.receiptId,
        ackFor = PacketType.DELIVERY_RECEIPT, ackStatus = MessageStatus.CARRIED)

    @Test fun `final commit creates one stable receipt before ACK and preserves duplicate final chat`() = runTest {
        val store = ReceiptTestStore(); val wire = Wire().apply { connect(c) }
        val engine = DeliveryEngine(d, store, wire, backgroundScope, NetworkLogger()) { time + 1 }
        val m = message()
        wire.onSend = {
            assertEquals(MessageStatus.RECEIVED, store.get(m.messageId)!!.status)
            assertEquals(DeliveryReceipt.idFor(m.messageId), store.receipts.value.single().receiptId)
            assertEquals(MessageStatus.DELIVERED, it.ackStatus)
        }
        repeat(2) { engine.receive(c, WirePacket(PacketType.MESSAGE, message = m), "one") }
        assertEquals(1, store.base.rows.size)
        assertEquals(1, store.receipts.value.size)
        assertEquals(0, store.receipts.value.single().hopCount)
        assertTrue(store.forwardableMessageIds(time + 2).isEmpty())
        assertTrue(store.receivedMessageIds(time + 2).isEmpty())
        assertEquals(2, wire.sent.size)
        assertTrue(store.base.peers.value.any { it.deviceId == a })
    }

    @Test fun `relay never creates receipt and failed final transaction sends no ACK or knowledge`() = runTest {
        val m = message(); val wire = Wire().apply { connect(b) }; val store = ReceiptTestStore()
        val relay = DeliveryEngine(c, store, wire, backgroundScope, NetworkLogger()) { time + 1 }
        relay.receive(b, WirePacket(PacketType.MESSAGE, message = m), "one")
        assertEquals(MessageStatus.CARRIED, store.get(m.messageId)!!.status)
        assertTrue(store.receipts.value.isEmpty())
        val failed = ReceiptTestStore().apply { failCommit = true }
        val finalWire = Wire().apply { connect(c) }
        val final = DeliveryEngine(d, failed, finalWire, backgroundScope, NetworkLogger()) { time + 1 }
        try { final.receive(c, WirePacket(PacketType.MESSAGE, message = m), "one"); fail() } catch (_: IllegalStateException) { }
        assertTrue(failed.base.rows.isEmpty()); assertTrue(failed.receipts.value.isEmpty()); assertTrue(finalWire.sent.isEmpty())
    }

    @Test fun `receipt updates source or carrier once suppresses forwarding and keeps original copy`() = runTest {
        val m = message(); val r = receipt(m); val store = ReceiptTestStore().apply { insert(m.copy(status = MessageStatus.FORWARDED)) }
        val wire = Wire().apply { connect(b) }
        val inventory = MutableStateFlow(snapshot(b, messages = listOf(m)))
        val engine = DeliveryEngine(a, store, wire, backgroundScope, NetworkLogger(), inventory) { time + 2 }
        engine.retryPending()
        assertEquals(1, wire.sent.size)
        repeat(2) { engine.receive(b, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = r), "one") }
        assertEquals(MessageStatus.DELIVERED, store.get(m.messageId)!!.status)
        assertEquals(1, store.receipts.value.size); assertEquals(1, store.base.rows.size)
        assertTrue(engine.forwarding.value.awaitingAckIds.isEmpty())
        wire.connect(e, "two"); inventory.value = snapshot(e, receipts = listOf(r), messages = listOf(m), session = "two")
        engine.retryPending()
        assertEquals(1, wire.sent.count { it.type == PacketType.MESSAGE })
        assertEquals(r.copy(hopCount = 2), wire.sent.last().receipt)
    }

    @Test fun `receipt before message and receipt arriving during message admission cannot restart routing`() = runTest {
        for (racing in listOf(false, true)) {
            val m = message(); val r = receipt(m); val store = ReceiptTestStore(); val wire = Wire().apply { connect(b) }
            val engine = DeliveryEngine(c, store, wire, backgroundScope, NetworkLogger()) { time + 2 }
            if (racing) store.beforeMessageCommit = { store.saveReceipt(r) }
            else engine.receive(b, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = r), "one")
            engine.receive(b, WirePacket(PacketType.MESSAGE, message = m), "one")
            assertTrue(store.base.rows.isEmpty()); assertEquals(1, store.receipts.value.size)
            assertFalse(wire.sent.any { it.type == PacketType.ACK && it.ackFor == PacketType.MESSAGE })
        }
    }

    @Test fun `expired receipt stops propagation but retained knowledge still suppresses message`() = runTest {
        val m = message(); val r = receipt(m).copy(expiresAt = time + 3)
        val store = ReceiptTestStore().apply { saveReceipt(r) }; val wire = Wire().apply { connect(b) }
        val inventory = MutableStateFlow(snapshot(b, receipts = listOf(r)))
        val engine = DeliveryEngine(c, store, wire, backgroundScope, NetworkLogger(), inventory) { time + 3 }
        engine.retryPending(); engine.receive(b, WirePacket(PacketType.MESSAGE, message = m), "one")
        assertTrue(store.base.rows.isEmpty()); assertTrue(wire.sent.isEmpty())
        assertTrue(store.activeReceiptIds(time + 3).isEmpty())
        assertNotNull(store.receiptForMessage(m.messageId))
    }

    @Test fun `receipt routing guards expiry hops malformed IDs and distinguishes source priority`() {
        val r = receipt()
        assertEquals(ForwardingKind.DIRECT, DeliveryEngine.classifyReceipt(r, a, time + 2))
        assertEquals(ForwardingKind.RELAY, DeliveryEngine.classifyReceipt(r, b, time + 2))
        assertNull(DeliveryEngine.classifyReceipt(r, b, r.expiresAt))
        assertNull(DeliveryEngine.classifyReceipt(r.copy(hopCount = r.hopLimit), b, time + 2))
        assertNull(DeliveryEngine.classifyReceipt(r.copy(receiptId = id()), b, time + 2))
        assertEquals(ForwardingKind.RELAY, DeliveryEngine.classifyReceipt(r.copy(hopCount = r.hopLimit - 1), b, time + 2))
    }

    @Test fun `priority is direct receipt direct message relay receipt relay message`() = runTest {
        val directR = receipt(); val relayR = receipt(message().copy(sourceId = e))
        val directM = message(a).copy(sourceId = b); val relayM = message().copy(sourceId = b)
        val store = ReceiptTestStore().apply { saveReceipt(relayR); saveReceipt(directR); insert(relayM); insert(directM) }
        val wire = Wire().apply { connect(a) }
        val inventory = MutableStateFlow(snapshot(a, listOf(relayR, directR), listOf(relayM, directM)))
        val engine = DeliveryEngine(c, store, wire, backgroundScope, NetworkLogger(), inventory) { time + 2 }
        engine.retryPending()
        assertEquals(listOf(directR.receiptId, directM.messageId, relayR.receiptId, relayM.messageId),
            wire.sent.map { it.receipt?.receiptId ?: it.message!!.messageId })
    }

    @Test fun `receipt in flight retry typed ACK and branches retain depth`() = runTest {
        var clock = time + 2
        val r = receipt(); val store = ReceiptTestStore().apply { saveReceipt(r) }; val wire = Wire().apply { connect(b) }
        val inventory = MutableStateFlow(snapshot(b, listOf(r)))
        val engine = DeliveryEngine(c, store, wire, backgroundScope, NetworkLogger(), inventory) { clock }
        repeat(3) { engine.retryPending() }; assertEquals(1, wire.sent.size)
        engine.receive(b, WirePacket(PacketType.ACK, messageId = r.receiptId), "one") // Wrong namespace.
        assertTrue(engine.forwarding.value.receiptPeerKnownIds.isEmpty())
        clock += 5000; engine.retryPending(); assertEquals(wire.sent[0], wire.sent[1])
        engine.receive(b, receiptAck(r), "one"); clock += 5000; engine.retryPending(); assertEquals(2, wire.sent.size)
        wire.connect(e, "two"); inventory.value = snapshot(e, listOf(r), session = "two")
        engine.retryPending()
        assertEquals(listOf(2, 2, 2), wire.sent.map { it.receipt!!.hopCount })
        assertEquals(r, store.receipt(r.receiptId))
        engine.receive(b, receiptAck(r), "one")
        assertTrue(engine.forwarding.value.receiptPeerKnownIds.isEmpty())
    }

    @Test fun `failed receipt transfer needs fresh inventory then retries without loss`() = runTest {
        val r = receipt(); val store = ReceiptTestStore().apply { saveReceipt(r) }
        val wire = Wire().apply { connect(a); succeeds = false }; val inventory = MutableStateFlow(snapshot(a, listOf(r)))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { time + 2 }
        engine.retryPending(); assertEquals(r, store.receipt(r.receiptId))
        assertTrue(engine.forwarding.value.receiptPeerKnownIds.isEmpty())
        wire.connect(a, "two"); wire.succeeds = true
        engine.retryPending(); assertTrue(wire.sent.isEmpty())
        inventory.value = snapshot(a, listOf(r), session = "two"); engine.retryPending()
        assertEquals(r.receiptId, wire.sent.single().receipt!!.receiptId)
    }

    @Test fun `receipt read crossing reconnect cannot leak through the new session`() = runTest {
        val r = receipt(); val store = ReceiptTestStore().apply { saveReceipt(r) }
        val wire = Wire().apply { connect(a) }; val inventory = MutableStateFlow(snapshot(a, listOf(r)))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { time + 2 }
        val gate = CompletableDeferred<Unit>(); store.beforeReceiptRead = { gate.await() }
        val task = launch { engine.retryPending() }; runCurrent()
        wire.connect(e, "two"); gate.complete(Unit); task.join()
        assertTrue(wire.sent.isEmpty())
        inventory.value = snapshot(e, listOf(r), session = "two"); engine.retryPending()
        assertEquals(1, wire.sent.size)
    }

    @Test fun `same peer reconnect rejects old receipt query and old ACK even after fresh inventory`() = runTest {
        val r = receipt(); val store = ReceiptTestStore().apply { saveReceipt(r) }
        val wire = Wire().apply { connect(a) }; val inventory = MutableStateFlow(snapshot(a, listOf(r)))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { time + 2 }
        val gate = CompletableDeferred<Unit>()
        store.beforeReceiptRead = { gate.await() }
        val oldPass = launch { engine.retryPending() }
        runCurrent()

        // Same UUID, different socket: even a complete new inventory cannot authorize old work.
        wire.connect(a, "two")
        inventory.value = snapshot(a, listOf(r), session = "two")
        gate.complete(Unit)
        oldPass.join()
        assertTrue(wire.sent.isEmpty())

        engine.retryPending()
        assertEquals(r.copy(hopCount = 2), wire.sent.single().receipt)
        engine.receive(a, receiptAck(r), "one")
        assertEquals(listOf(r.receiptId), engine.forwarding.value.receiptAwaitingAckIds)
        assertTrue(engine.forwarding.value.receiptPeerKnownIds.isEmpty())
        engine.receive(a, receiptAck(r), "two")
        assertTrue(engine.forwarding.value.receiptAwaitingAckIds.isEmpty())
        assertEquals(listOf(r.receiptId), engine.forwarding.value.receiptPeerKnownIds)
        assertEquals(r, store.receipt(r.receiptId))
    }

    @Test fun `receipt expiring during suspended lookup is retained but not sent`() = runTest {
        var clock = time + 2
        val r = receipt().copy(expiresAt = time + 100)
        val store = ReceiptTestStore().apply { saveReceipt(r) }
        val wire = Wire().apply { connect(a) }; val inventory = MutableStateFlow(snapshot(a, listOf(r)))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { clock }
        val gate = CompletableDeferred<Unit>()
        store.beforeReceiptRead = { gate.await() }
        val pass = launch { engine.retryPending() }
        runCurrent()
        clock = r.expiresAt
        gate.complete(Unit)
        pass.join()

        assertTrue(wire.sent.isEmpty())
        assertTrue(engine.forwarding.value.receiptAwaitingAckIds.isEmpty())
        assertTrue(store.activeReceiptIds(clock).isEmpty())
        assertEquals(r, store.receiptForMessage(r.originalMessageId))
    }

    @Test fun `remote receipt knowledge arriving during lookup cancels the selected transfer`() = runTest {
        val r = receipt(); val store = ReceiptTestStore().apply { saveReceipt(r) }
        val wire = Wire().apply { connect(a) }; val inventory = MutableStateFlow(snapshot(a, listOf(r)))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { time + 2 }
        val gate = CompletableDeferred<Unit>()
        store.beforeReceiptRead = { gate.await() }
        val pass = launch { engine.retryPending() }
        runCurrent()
        inventory.value = inventory.value.copy(remoteReceiptIds = listOf(r.receiptId), receiptMissingOnPeer = emptyList())
        gate.complete(Unit)
        pass.join()

        assertTrue(wire.sent.isEmpty())
        assertTrue(engine.forwarding.value.receiptAwaitingAckIds.isEmpty())
        assertEquals(r, store.receipt(r.receiptId))
    }

    @Test fun `duplicate final message after engine recreation preserves receipt timestamps and lifetime`() = runTest {
        val m = message(); val store = ReceiptTestStore(); val wire = Wire().apply { connect(c) }
        val original = DeliveryEngine(d, store, wire, backgroundScope, NetworkLogger()) { time + 1 }
        original.receive(c, WirePacket(PacketType.MESSAGE, message = m), "one")
        val savedReceipt = store.receipts.value.single()
        val savedMessage = store.get(m.messageId)

        // Reuse durable-state stand-in, not engine memory. Actual disk reopen is in androidTest.
        wire.connect(e, "two")
        val reopened = DeliveryEngine(d, store, wire, backgroundScope, NetworkLogger()) { m.expiresAt + 1 }
        reopened.receive(e, WirePacket(PacketType.MESSAGE, message = m.copy(hopCount = 3)), "two")

        assertEquals(listOf(savedReceipt), store.receipts.value)
        assertEquals(savedMessage, store.get(m.messageId))
        assertEquals(1, store.base.rows.size)
        assertEquals(2, wire.sent.size)
        assertTrue(wire.sent.all { it.type == PacketType.ACK && it.ackFor == PacketType.MESSAGE &&
            it.messageId == m.messageId && it.ackStatus == MessageStatus.DELIVERED })
    }

    @Test fun `live inventory refresh never echoes a received receipt and disconnect clears receipt state`() = runTest {
        val r = receipt(); val store = ReceiptTestStore(); val wire = Wire().apply { connect(d) }
        val manager = InventoryManager(c, store, wire, backgroundScope, NetworkLogger()) { time + 2 }
        val engine = DeliveryEngine(c, store, wire, backgroundScope, NetworkLogger(), manager.state) { time + 2 }
        manager.start(); engine.start(); runCurrent()
        manager.receive(d, "one", WirePacket(PacketType.INVENTORY, deviceId = d, messageIds = emptyList(), deliveryReceiptIds = emptyList()))
        runCurrent(); engine.receive(d, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = r), "one"); runCurrent()
        assertTrue(r.receiptId in manager.state.value.receiptMissingOnPeer)
        assertFalse(wire.sent.any { it.type == PacketType.DELIVERY_RECEIPT })
        assertTrue(r.receiptId in engine.forwarding.value.receiptPeerKnownIds)
        wire.state.value = SocketState(); runCurrent()
        assertTrue(engine.forwarding.value.receiptPeerKnownIds.isEmpty())
        assertTrue(engine.forwarding.value.receiptAwaitingAckIds.isEmpty())
        assertTrue(manager.state.value.remoteReceiptIds.isEmpty())
        assertTrue(manager.state.value.receiptMissingOnPeer.isEmpty())
        assertEquals(1, store.receipts.value.size)
    }

    @Test fun `legacy inventory does not trigger unsupported receipt packets`() = runTest {
        val r = receipt(); val store = ReceiptTestStore().apply { saveReceipt(r) }; val wire = Wire().apply { connect(a) }
        val inventory = MutableStateFlow(snapshot(a, listOf(r)).copy(peerSupportsReceipts = false))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { time + 2 }
        engine.retryPending(); assertTrue(wire.sent.isEmpty())
    }

    @Test fun `receipt protocol roundtrip typed ACK and combined inventory bounds`() {
        val r = receipt()
        for (packet in listOf(WirePacket(PacketType.DELIVERY_RECEIPT, receipt = r), receiptAck(r),
            WirePacket(PacketType.INVENTORY, deviceId = b, messageIds = listOf(id()), deliveryReceiptIds = listOf(r.receiptId)))) {
            val bytes = ByteArrayOutputStream(); WireProtocol.write(DataOutputStream(bytes), packet)
            assertEquals(packet, WireProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))))
        }
        val invalid = listOf(receiptAck(r).copy(messageId = id()), receiptAck(r).copy(ackFor = PacketType.MESSAGE),
            WirePacket(PacketType.DELIVERY_RECEIPT, receipt = r.copy(hopCount = 11)),
            WirePacket(PacketType.DELIVERY_RECEIPT, receipt = r.copy(expiresAt = r.createdAt)),
            WirePacket(PacketType.INVENTORY, deviceId = b, messageIds = List(600) { id() }, deliveryReceiptIds = List(401) { id() }))
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { WireProtocol.encode(it) } }
        assertTrue(WireProtocol.encode(WirePacket(PacketType.INVENTORY, deviceId = b,
            messageIds = List(500) { id() }, deliveryReceiptIds = List(500) { id() })).size < WireProtocol.MAX_FRAME_BYTES)
    }

    @Test fun `conflicting receipt or failed receipt storage never ACKs or confirms delivery`() = runTest {
        val m = message(); val r = receipt(m); val store = ReceiptTestStore().apply { insert(m) }
        val wire = Wire().apply { connect(d) }; val engine = DeliveryEngine(c, store, wire, backgroundScope, NetworkLogger()) { time + 2 }
        try { engine.receive(d, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = r.copy(originalDestinationId = e)), "one"); fail() }
        catch (_: IllegalArgumentException) { }
        store.failCommit = true
        try { engine.receive(d, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = r), "one"); fail() }
        catch (_: IllegalStateException) { }
        assertTrue(wire.sent.isEmpty()); assertTrue(store.receipts.value.isEmpty()); assertEquals(m, store.get(m.messageId))
    }

    @Test fun `receipt at last hop is stored and known but never retransmitted to another peer`() = runTest {
        val r = receipt().copy(hopCount = 2, hopLimit = 2)
        val store = ReceiptTestStore(); val wire = Wire().apply { connect(d) }
        val inventory = MutableStateFlow(snapshot(d))
        val engine = DeliveryEngine(c, store, wire, backgroundScope, NetworkLogger(), inventory) { time + 2 }
        engine.receive(d, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = r), "one")
        assertEquals(r, store.receipt(r.receiptId))
        assertEquals(listOf(r.receiptId), store.activeReceiptIds(time + 2))
        wire.connect(e, "two"); inventory.value = snapshot(e, listOf(r), session = "two")
        engine.retryPending()
        assertEquals(listOf(receiptAck(r)), wire.sent)
        assertEquals(2, store.receipt(r.receiptId)!!.hopCount)
    }

    @Test fun `expired unseen receipt rejected but previously committed duplicate reACKed without renewal`() = runTest {
        var clock = time + 2
        val r = receipt().copy(expiresAt = time + 3); val store = ReceiptTestStore(); val wire = Wire().apply { connect(d) }
        val engine = DeliveryEngine(c, store, wire, backgroundScope, NetworkLogger()) { clock }
        engine.receive(d, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = r), "one")
        clock = r.expiresAt
        engine.receive(d, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = r.copy(hopCount = 2)), "one")
        assertEquals(r, store.receipts.value.single()); assertEquals(2, wire.sent.size)
        val other = receipt().copy(expiresAt = time + 3)
        engine.receive(d, WirePacket(PacketType.DELIVERY_RECEIPT, receipt = other), "one")
        assertEquals(1, store.receipts.value.size); assertEquals(2, wire.sent.size)
    }
}
