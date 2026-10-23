package com.group16.dinoblog

import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.dtn.InventoryManager
import com.group16.dinoblog.model.*
import com.group16.dinoblog.network.*
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
class EpidemicRoutingTest {
    private fun id() = UUID.randomUUID().toString()
    private val a = id(); private val b = id(); private val c = id(); private val d = id(); private val e = id()
    private val time = 1700000000000L
    private fun row(destination: String = d) = Message(id(), a, destination, "Hello D", time, 60,
        MessageStatus.CARRIED, hopCount = 1, hopLimit = 3, carrierId = b)
    private class Wire : MessageTransport {
        override val state = MutableStateFlow(SocketState())
        override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
        val sent = mutableListOf<WirePacket>()
        var onSend: suspend (WirePacket) -> Unit = {}
        var succeeds = true
        override suspend fun send(peerId: String, packet: WirePacket): Boolean {
            if (state.value.peerId != peerId || !succeeds) return false
            sent += packet
            onSend(packet)
            return true
        }
        fun connect(peer: String, session: String = "one") { state.value = SocketState("Connected", peer, sessionId = session) }
    }
    private fun snapshot(peer: String, vararg ids: String, session: String = "one") = InventoryState(
        peerId = peer, sessionId = session, localReady = true, sent = true, remoteReceived = true,
        localMessageIds = ids.toList(), missingOnPeer = ids.toList())

    @Test fun `pure decision classifies direct relay and rejects local final status expiry and hop overflow`() {
        val base = row()
        assertEquals(ForwardingKind.RELAY, DeliveryEngine.classify(base, b, c, time))
        assertEquals(ForwardingKind.DIRECT, DeliveryEngine.classify(base, b, d, time))
        for (validStatus in listOf(MessageStatus.PENDING, MessageStatus.CARRIED, MessageStatus.FORWARDED)) {
            assertEquals(ForwardingKind.RELAY, DeliveryEngine.classify(base.copy(status = validStatus), b, c, time))
        }
        for (invalid in listOf(base.copy(destinationId = b), base.copy(hopCount = 3), base.copy(hopCount = -1),
            base.copy(status = MessageStatus.DELIVERED), base.copy(status = MessageStatus.RECEIVED),
            base.copy(status = MessageStatus.EXPIRED), base.copy(messageId = "invalid"))) {
            assertNull(DeliveryEngine.classify(invalid, b, c, time))
        }
        assertNull(DeliveryEngine.classify(base, b, c, base.expiresAt))
        assertNull(DeliveryEngine.classify(base, b, b, time))
        assertEquals(ForwardingKind.RELAY, DeliveryEngine.classify(base.copy(hopCount = 2), b, c, time))
    }

    @Test fun `direct precedes relay and ACK knowledge prevents resends despite stale or repeated inventory`() = runTest {
        var clock = time
        val direct = row(c); val relay = row(); val known = row(c)
        val rows = listOf(relay, known, direct)
        val store = Phase4TestStore().apply { rows.forEach { insert(it) } }
        val wire = Wire().apply { connect(c) }
        val inventory = MutableStateFlow(snapshot(c, relay.messageId, direct.messageId).copy(remoteMessageIds = listOf(known.messageId)))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { clock }
        wire.onSend = { packet -> engine.receive(c, WirePacket(PacketType.ACK, messageId = packet.message!!.messageId,
            ackStatus = if (packet.message!!.destinationId == c) MessageStatus.DELIVERED else MessageStatus.CARRIED), "one") }
        engine.retryPending()
        assertEquals(listOf(direct.messageId, relay.messageId), wire.sent.map { it.message!!.messageId })
        assertEquals(MessageStatus.DELIVERED, store.rows[direct.messageId]!!.status)
        assertEquals(MessageStatus.CARRIED, store.rows[relay.messageId]!!.status)
        assertTrue(store.rows.values.all { it.hopCount == 1 })
        clock += 10000
        inventory.value = inventory.value.copy(remoteMessageIds = emptyList()) // Cannot erase proven knowledge.
        repeat(3) { engine.retryPending() }
        assertEquals(2, wire.sent.size)
        assertTrue(engine.forwarding.value.awaitingAckIds.isEmpty())
        assertTrue(engine.forwarding.value.peerKnownIds.containsAll(rows.map { it.messageId }))
    }

    @Test fun `relay ACK cannot imply final delivery and branches retain sender path depth`() = runTest {
        val message = row()
        val store = Phase4TestStore().apply { insert(message) }
        val wire = Wire().apply { connect(c) }
        val inventory = MutableStateFlow(snapshot(c, message.messageId))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { time }
        engine.retryPending()
        engine.receive(c, WirePacket(PacketType.ACK, messageId = message.messageId), "one") // Wrong ACK kind.
        assertFalse(message.messageId in engine.forwarding.value.peerKnownIds)
        engine.receive(c, WirePacket(PacketType.ACK, messageId = message.messageId, ackStatus = MessageStatus.CARRIED), "one")
        assertEquals(message, store.rows[message.messageId])
        wire.connect(e, "two")
        inventory.value = snapshot(e, message.messageId, session = "two")
        engine.retryPending()
        assertEquals(listOf(2, 2), wire.sent.map { it.message!!.hopCount })
        assertEquals(message, store.rows[message.messageId])
        engine.receive(c, WirePacket(PacketType.ACK, messageId = message.messageId, ackStatus = MessageStatus.CARRIED), "one")
        assertFalse(message.messageId in engine.forwarding.value.peerKnownIds)
    }

    @Test fun `in flight blocks reactive duplicate sends but timed retry uses identical outgoing depth`() = runTest {
        var clock = time
        val message = row()
        val store = Phase4TestStore().apply { insert(message) }
        val wire = Wire().apply { connect(c) }
        val inventory = MutableStateFlow(snapshot(c, message.messageId))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { clock }
        repeat(5) { engine.retryPending() }
        assertEquals(1, wire.sent.size)
        assertFalse(message.messageId in engine.forwarding.value.peerKnownIds) // Successful write is not proof.
        clock += 5000
        engine.retryPending()
        assertEquals(2, wire.sent.size)
        assertEquals(wire.sent[0], wire.sent[1])
        assertEquals(message, store.rows[message.messageId])
    }

    @Test fun `received relay marks peer known before Room refresh and never echoes it back`() = runTest {
        val store = Phase4TestStore()
        val wire = Wire().apply { connect(c) }
        val manager = InventoryManager(b, store, wire, backgroundScope, NetworkLogger()) { time }
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), manager.state) { time }
        manager.start(); engine.start(); runCurrent()
        manager.receive(c, "one", WirePacket(PacketType.INVENTORY, deviceId = c, messageIds = emptyList()))
        runCurrent()
        val incoming = row().copy(status = MessageStatus.PENDING, hopCount = 2)
        engine.receive(c, WirePacket(PacketType.MESSAGE, message = incoming), "one")
        runCurrent()
        assertTrue(incoming.messageId in manager.state.value.missingOnPeer) // Remote snapshot was empty.
        assertTrue(incoming.messageId in engine.forwarding.value.peerKnownIds)
        assertFalse(wire.sent.any { it.type == PacketType.MESSAGE })
        assertEquals(incoming.copy(status = MessageStatus.CARRIED), store.rows[incoming.messageId])
        assertTrue(store.peers.value.none { it.deviceId == a }) // Carrier does not create a final chat.
        wire.state.value = SocketState(); runCurrent()
        assertTrue(engine.forwarding.value.peerKnownIds.isEmpty())
        assertTrue(engine.forwarding.value.awaitingAckIds.isEmpty())
        assertTrue(manager.state.value.remoteMessageIds.isEmpty())
        assertEquals(1, store.rows.size)
    }

    @Test fun `relay at exact hop limit is stored and advertised but cannot propagate again`() = runTest {
        val store = Phase4TestStore()
        val wire = Wire().apply { connect(c) }
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger()) { time }
        val incoming = row().copy(status = MessageStatus.PENDING, hopCount = 3)
        engine.receive(c, WirePacket(PacketType.MESSAGE, message = incoming), "one")
        assertEquals(MessageStatus.CARRIED, store.rows[incoming.messageId]!!.status)
        assertTrue(store.forwardableMessageIds(time).isEmpty())
        assertEquals(listOf(incoming.messageId), store.receivedMessageIds(time))
        assertNull(DeliveryEngine.classify(store.rows[incoming.messageId]!!, b, e, time))
    }

    @Test fun `slow read from C session cannot leak candidates into newly connected E session`() = runTest {
        val message = row()
        val store = Phase4TestStore().apply { insert(message) }
        val wire = Wire().apply { connect(c) }
        val inventory = MutableStateFlow(snapshot(c, message.messageId))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { time }
        val gate = CompletableDeferred<Unit>()
        store.beforeGet = { gate.await() }
        val work = launch { engine.retryPending() }
        runCurrent(); wire.connect(e, "two"); gate.complete(Unit); work.join()
        assertTrue(wire.sent.isEmpty())
        engine.retryPending()
        assertTrue(wire.sent.isEmpty())
        inventory.value = snapshot(e, message.messageId, session = "two")
        engine.retryPending()
        assertEquals(1, wire.sent.size)
    }

    @Test fun `pending origin message also waits for inventory and relay ACK keeps origin depth zero`() = runTest {
        val store = Phase4TestStore()
        val wire = Wire().apply { connect(b) }
        val inventory = MutableStateFlow(InventoryState())
        val engine = DeliveryEngine(a, store, wire, backgroundScope, NetworkLogger(), inventory) { time }
        val message = engine.queue(d, "Hello D", carrierId = b)
        runCurrent()
        assertTrue(wire.sent.isEmpty())
        inventory.value = snapshot(b, message.messageId)
        wire.onSend = { engine.receive(b, WirePacket(PacketType.ACK, messageId = message.messageId, ackStatus = MessageStatus.CARRIED), "one") }
        engine.retryPending()
        assertEquals(1, wire.sent.single().message!!.hopCount)
        assertEquals(message.copy(status = MessageStatus.FORWARDED), store.rows[message.messageId])
        engine.retryPending()
        assertEquals(1, wire.sent.size)
    }

    @Test fun `failed relay transfer retains copy and fresh inventory permits retry after reconnect`() = runTest {
        val message = row()
        val store = Phase4TestStore().apply { insert(message) }
        val wire = Wire().apply { connect(c); succeeds = false }
        val inventory = MutableStateFlow(snapshot(c, message.messageId))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { time }
        engine.retryPending()
        assertEquals(message, store.rows[message.messageId])
        assertTrue(engine.forwarding.value.peerKnownIds.isEmpty())
        wire.connect(c, "two")
        wire.succeeds = true
        engine.retryPending() // An old inventory is not enough, even for the same peer.
        assertTrue(wire.sent.isEmpty())
        inventory.value = snapshot(c, message.messageId, session = "two")
        wire.onSend = { engine.receive(c, WirePacket(PacketType.ACK, messageId = message.messageId,
            ackStatus = MessageStatus.CARRIED), "two") }
        engine.retryPending()
        assertEquals(message.copy(status = MessageStatus.PENDING, hopCount = 2), wire.sent.single().message)
        assertEquals(message, store.rows[message.messageId])
        assertTrue(message.messageId in engine.forwarding.value.peerKnownIds)
    }

    @Test fun `FORWARDED origin replicates to another peer without accumulating branch depth`() = runTest {
        val store = Phase4TestStore()
        val wire = Wire().apply { connect(b) }
        val inventory = MutableStateFlow(InventoryState())
        val engine = DeliveryEngine(a, store, wire, backgroundScope, NetworkLogger(), inventory) { time }
        val message = engine.queue(d, "Created while destination is absent")
        runCurrent()
        inventory.value = snapshot(b, message.messageId)
        engine.retryPending()
        engine.receive(b, WirePacket(PacketType.ACK, messageId = message.messageId, ackStatus = MessageStatus.CARRIED), "one")
        wire.connect(c, "two")
        inventory.value = snapshot(c, message.messageId, session = "two")
        engine.retryPending()
        assertEquals(listOf(1, 1), wire.sent.map { it.message!!.hopCount })
        assertEquals(message.copy(status = MessageStatus.FORWARDED), store.rows[message.messageId])
        assertEquals(listOf(message.messageId, message.messageId), wire.sent.map { it.message!!.messageId })
    }

    @Test fun `late relay ACK does not revive expired copy or imply final delivery`() = runTest {
        var clock = time
        val message = row().copy(ttl = 1, expiresAt = time + 1000)
        val store = Phase4TestStore().apply { insert(message) }
        val wire = Wire().apply { connect(c) }
        val inventory = MutableStateFlow(snapshot(c, message.messageId))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { clock }
        engine.retryPending()
        clock = message.expiresAt
        engine.retryPending()
        engine.receive(c, WirePacket(PacketType.ACK, messageId = message.messageId, ackStatus = MessageStatus.CARRIED), "one")
        assertEquals(message.copy(status = MessageStatus.EXPIRED), store.rows[message.messageId])
        assertTrue(engine.forwarding.value.awaitingAckIds.isEmpty())
        assertTrue(message.messageId in engine.forwarding.value.peerKnownIds)
        clock += 5000
        engine.retryPending()
        assertEquals(1, wire.sent.size)
    }

    @Test fun `locally final content never leaves destination even when stale inventory calls it missing`() = runTest {
        val message = row(b).copy(carrierId = null, status = MessageStatus.RECEIVED)
        val store = Phase4TestStore().apply { insert(message) }
        val wire = Wire().apply { connect(e) }
        val inventory = MutableStateFlow(snapshot(e, message.messageId))
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger(), inventory) { time }
        engine.retryPending()
        assertTrue(wire.sent.isEmpty())
        assertTrue(engine.forwarding.value.candidateIds.isEmpty())
        assertEquals(message, store.rows[message.messageId])
    }
}
