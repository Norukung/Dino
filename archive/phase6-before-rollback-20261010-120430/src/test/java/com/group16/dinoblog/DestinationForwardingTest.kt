package com.group16.dinoblog

import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.dtn.InventoryManager
import com.group16.dinoblog.model.InventoryState
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.network.MessageTransport
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DestinationForwardingTest {
    private fun id() = UUID.randomUUID().toString()
    private val a = id()
    private val b = id()
    private val c = id()
    private val d = id()
    private val time = 1700000000000L
    private fun carried(destination: String = c) = Message(id(), a, destination, "Hello C", time, 60,
        MessageStatus.CARRIED, hopCount = 1, carrierId = b)
    private class Wire : MessageTransport {
        override val state = MutableStateFlow(SocketState())
        override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
        val sent = mutableListOf<WirePacket>()
        var succeeds = true
        var onSend: suspend (WirePacket) -> Unit = {}
        override suspend fun send(peerId: String, packet: WirePacket): Boolean {
            if (state.value.peerId != peerId || !succeeds) return false
            sent += packet
            onSend(packet)
            return true
        }
        fun connect(peer: String, session: String = "one") { state.value = SocketState("Connected", peer, sessionId = session) }
    }
    private fun snapshot(vararg ids: String, session: String = "one") = InventoryState(
        peerId = c, sessionId = session, localReady = true, remoteReceived = true, sent = true,
        localMessageIds = ids.toList(), missingOnPeer = ids.toList())

    @Test fun `forwarding waits for current HELLO and completed bidirectional inventory then runs automatically`() = runTest {
        val row = carried()
        val store = Phase4TestStore().apply { insert(row) }
        val wire = Wire()
        val inventory = MutableStateFlow(InventoryState())
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { time }
        engine.start()
        runCurrent()
        inventory.value = snapshot(row.messageId)
        runCurrent()
        assertTrue(wire.sent.isEmpty())
        inventory.value = InventoryState()
        wire.connect(c)
        runCurrent()
        assertTrue(wire.sent.isEmpty())
        inventory.value = snapshot(row.messageId).copy(sent = false)
        runCurrent()
        assertTrue(wire.sent.isEmpty())
        inventory.value = snapshot(row.messageId).copy(remoteReceived = false)
        runCurrent()
        assertTrue(wire.sent.isEmpty())
        inventory.value = snapshot(row.messageId)
        runCurrent()
        assertEquals(row.copy(status = MessageStatus.PENDING, hopCount = 2), wire.sent.single().message)
        assertEquals(row, store.rows[row.messageId]) // Write is not an ACK.
        engine.retryPending()
        assertEquals(1, wire.sent.size) // Repeated inventory/retry does not spin.
    }

    @Test fun `mixed destinations route direct first then relay while invalid and known IDs are filtered`() = runTest {
        val valid = carried()
        val other = carried(d)
        val alreadyThere = carried()
        val expired = carried().copy(createdAt = time - 1000, ttl = 1, expiresAt = time)
        val exhausted = carried().copy(hopLimit = 1)
        val received = carried().copy(status = MessageStatus.RECEIVED)
        val rows = listOf(valid, other, alreadyThere, expired, exhausted, received)
        val store = Phase4TestStore().apply { rows.forEach { insert(it) } }
        val wire = Wire().apply { connect(c) }
        val inventory = MutableStateFlow(snapshot(*rows.filter { it != alreadyThere }.map { it.messageId }.toTypedArray())
            .copy(remoteMessageIds = listOf(alreadyThere.messageId)))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { time }
        engine.retryPending()
        assertEquals(listOf(valid.messageId, other.messageId), wire.sent.map { it.message!!.messageId })
        assertEquals(MessageStatus.EXPIRED, store.rows[expired.messageId]!!.status)
        assertEquals(1, store.rows[exhausted.messageId]!!.hopCount)
    }

    @Test fun `multiple messages keep original fields and only attempted final ACKs confirm delivery once`() = runTest {
        val rows = List(4) { carried() }
        val store = Phase4TestStore().apply { rows.forEach { insert(it) } }
        val wire = Wire().apply { connect(c) }
        val inventory = MutableStateFlow(snapshot(*rows.map { it.messageId }.toTypedArray()))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { time }
        val ack = WirePacket(PacketType.ACK, messageId = rows.first().messageId)
        engine.receive(c, ack, "one") // Unsolicited ACK cannot mark carried rows delivered.
        assertEquals(MessageStatus.CARRIED, store.rows[rows.first().messageId]!!.status)
        engine.retryPending()
        assertEquals(rows.sortedBy { it.messageId }.map { it.copy(status = MessageStatus.PENDING, hopCount = 2) }, wire.sent.map { it.message })
        engine.receive(d, ack, "one")
        engine.receive(c, ack.copy(ackStatus = MessageStatus.CARRIED), "one")
        assertEquals(MessageStatus.CARRIED, store.rows[rows.first().messageId]!!.status)
        repeat(2) { engine.receive(c, ack, "one") }
        assertEquals(MessageStatus.DELIVERED, store.rows[rows.first().messageId]!!.status)
        assertEquals(1, store.rows[rows.first().messageId]!!.hopCount)
    }

    @Test fun `final destination commits once before ACK and adds original sender to conversations`() = runTest {
        val row = carried().copy(status = MessageStatus.PENDING, hopCount = 2)
        val store = Phase4TestStore()
        val wire = Wire().apply { connect(b) }
        val engine = DeliveryEngine(c, store, wire, backgroundScope, NetworkLogger()) { time }
        wire.onSend = { packet ->
            assertEquals(PacketType.ACK, packet.type)
            assertEquals(MessageStatus.RECEIVED, store.rows[row.messageId]?.status)
            assertEquals(MessageStatus.DELIVERED, packet.ackStatus)
        }
        repeat(2) { engine.receive(b, WirePacket(PacketType.MESSAGE, message = row), "one") }
        assertEquals(1, store.rows.size)
        assertEquals(row.copy(status = MessageStatus.RECEIVED), store.rows[row.messageId])
        assertEquals(2, wire.sent.size)
        assertTrue(store.peers.value.any { it.deviceId == a })
        try { engine.receive(b, WirePacket(PacketType.MESSAGE, message = row.copy(payload = "collision")), "one"); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(2, wire.sent.size)
    }

    @Test fun `final storage failure gives no ACK while nonfinal relay is now carried`() = runTest {
        val store = Phase4TestStore().apply { failInsert = true }
        val wire = Wire().apply { connect(b) }
        val engine = DeliveryEngine(c, store, wire, backgroundScope, NetworkLogger()) { time }
        val row = carried().copy(status = MessageStatus.PENDING, hopCount = 2)
        try { engine.receive(b, WirePacket(PacketType.MESSAGE, message = row), "one"); fail() }
        catch (_: IllegalStateException) { }
        assertTrue(store.rows.isEmpty())
        assertTrue(wire.sent.isEmpty())
        store.failInsert = false
        engine.receive(b, WirePacket(PacketType.MESSAGE, message = row.copy(destinationId = d)), "one")
        assertEquals(MessageStatus.CARRIED, store.rows[row.messageId]!!.status)
        assertEquals(MessageStatus.CARRIED, wire.sent.single().ackStatus)
    }

    @Test fun `lost ACK retries same hop in session but received inventory suppresses resend after reconnect`() = runTest {
        var clock = time
        val row = carried()
        val storeB = Phase4TestStore().apply { insert(row) }
        val storeC = Phase4TestStore()
        val wireB = Wire().apply { connect(c) }
        val wireC = Wire().apply { connect(b) }
        val inventory = MutableStateFlow(snapshot(row.messageId))
        val sender = DeliveryEngine(b, storeB, wireB, backgroundScope, NetworkLogger(), inventory) { clock }
        val receiver = DeliveryEngine(c, storeC, wireC, backgroundScope, NetworkLogger()) { clock }
        wireB.onSend = { receiver.receive(b, it, "one") } // ACK deliberately not routed back.
        sender.retryPending()
        clock += 5000
        sender.retryPending()
        assertEquals(2, wireB.sent.size)
        assertTrue(wireB.sent.all { it.message!!.hopCount == 2 })
        assertEquals(1, storeC.rows.size)
        assertEquals(row, storeB.rows[row.messageId])
        wireB.connect(c, "two")
        val ids = storeC.receivedMessageIds(clock)
        assertEquals(listOf(row.messageId), ids)
        val comparison = InventoryManager.compare(storeB.forwardableMessageIds(clock), ids)
        inventory.value = snapshot(session = "two").copy(remoteMessageIds = ids, missingOnPeer = comparison.first)
        sender.retryPending()
        assertEquals(2, wireB.sent.size)
        assertEquals(MessageStatus.CARRIED, storeB.rows[row.messageId]!!.status) // Inventory is not an ACK.
    }

    @Test fun `disconnect during suspended Room read cannot send stale candidates and reconnect requires fresh inventory`() = runTest {
        val row = carried()
        val store = Phase4TestStore().apply { insert(row) }
        val wire = Wire().apply { connect(c) }
        val inventory = MutableStateFlow(snapshot(row.messageId))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { time }
        val gate = CompletableDeferred<Unit>()
        store.beforeGet = { gate.await() }
        val pending = launch { engine.retryPending() }
        runCurrent()
        wire.connect(c, "two")
        gate.complete(Unit)
        pending.join()
        assertTrue(wire.sent.isEmpty())
        engine.retryPending()
        assertTrue(wire.sent.isEmpty())
        inventory.value = snapshot(row.messageId, session = "two")
        engine.retryPending()
        assertEquals(1, wire.sent.size)
        engine.receive(c, WirePacket(PacketType.ACK, messageId = row.messageId), "one")
        assertEquals(row, store.rows[row.messageId])
    }

    @Test fun `failed send retains custody and clears session debug state on disconnect`() = runTest {
        val row = carried()
        val store = Phase4TestStore().apply { insert(row) }
        val wire = Wire().apply { connect(c); succeeds = false }
        val inventory = MutableStateFlow(snapshot(row.messageId))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { time }
        engine.start()
        runCurrent()
        assertEquals(row, store.rows[row.messageId])
        wire.state.value = SocketState()
        inventory.value = InventoryState()
        runCurrent()
        assertNull(engine.forwarding.value.peerId)
        assertTrue(engine.forwarding.value.awaitingAckIds.isEmpty())
        assertTrue(engine.forwarding.value.candidateIds.isEmpty())
        wire.succeeds = true
        wire.connect(c, "two")
        runCurrent()
        assertTrue(wire.sent.isEmpty())
        inventory.value = snapshot(row.messageId, session = "two")
        runCurrent()
        assertEquals(row.messageId, wire.sent.single().message!!.messageId)
    }

    @Test fun `late final ACK after expiry confirms only the previously attempted transfer`() = runTest {
        var clock = time
        val row = carried().copy(ttl = 1, expiresAt = time + 1000)
        val store = Phase4TestStore().apply { insert(row) }
        val wire = Wire().apply { connect(c) }
        val inventory = MutableStateFlow(snapshot(row.messageId))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { clock }
        engine.retryPending()
        clock += 1000
        engine.retryPending()
        assertEquals(MessageStatus.EXPIRED, store.rows[row.messageId]!!.status)
        engine.receive(c, WirePacket(PacketType.ACK, messageId = row.messageId), "one")
        assertEquals(MessageStatus.DELIVERED, store.rows[row.messageId]!!.status)
        assertEquals(1, wire.sent.size)
    }

    @Test fun `expiry that occurs during Room lookup blocks a previously eligible inventory candidate`() = runTest {
        var clock = time
        val row = carried().copy(ttl = 1, expiresAt = time + 1000)
        val store = Phase4TestStore().apply { insert(row); beforeGet = { clock += 1000 } }
        val wire = Wire().apply { connect(c) }
        val inventory = MutableStateFlow(snapshot(row.messageId))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { clock }
        engine.retryPending()
        assertTrue(wire.sent.isEmpty())
        assertEquals(1, store.rows[row.messageId]!!.hopCount)
    }

    @Test fun `received duplicate with different path hop is reACKed without changing stored content or hop`() = runTest {
        var clock = time
        val row = carried().copy(status = MessageStatus.PENDING, hopCount = 2)
        val store = Phase4TestStore()
        val wire = Wire().apply { connect(b) }
        val receiver = DeliveryEngine(c, store, wire, backgroundScope, NetworkLogger()) { clock }
        receiver.receive(b, WirePacket(PacketType.MESSAGE, message = row), "one")
        clock = row.expiresAt
        receiver.receive(b, WirePacket(PacketType.MESSAGE, message = row.copy(hopCount = 3)), "one")
        assertEquals(row.copy(status = MessageStatus.RECEIVED), store.rows[row.messageId])
        assertEquals(2, wire.sent.size)
        receiver.receive(b, WirePacket(PacketType.MESSAGE, message = row.copy(messageId = id())), "one")
        assertEquals(1, store.rows.size)
        assertEquals(2, wire.sent.size) // Expired unseen forwarding is not stored or ACKed.
    }
}
