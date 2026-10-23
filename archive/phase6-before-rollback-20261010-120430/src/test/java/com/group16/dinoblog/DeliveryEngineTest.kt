package com.group16.dinoblog

import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.network.MessageTransport
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.repository.MessageStore
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryEngineTest {
    private val a = UUID.randomUUID().toString()
    private val b = UUID.randomUUID().toString()
    private val c = UUID.randomUUID().toString()
    private val time = 1_700_000_000_000L

    private class Store : MessageStore {
        val rows = linkedMapOf<String, Message>()
        var failInsert = false
        override val messages = MutableStateFlow<List<Message>>(emptyList())
        override val peers = MutableStateFlow<List<Peer>>(emptyList())
        override suspend fun insert(message: Message): Boolean {
            check(!failInsert) { "Disk full" }
            if (rows.containsKey(message.messageId)) return false
            rows[message.messageId] = message
            return true
        }
        override suspend fun get(id: String) = rows[id]
        override suspend fun pending(localId: String, peerId: String) = rows.values.filter {
            it.senderId == localId && (it.carrierId ?: it.receiverId) == peerId && it.status == MessageStatus.PENDING
        }
        override suspend fun delivered(id: String, localId: String, peerId: String): Boolean {
            val message = rows[id] ?: return false
            if (message.senderId != localId || message.receiverId != peerId || message.carrierId != null || message.hopCount >= message.hopLimit || message.status !in listOf(MessageStatus.PENDING, MessageStatus.EXPIRED)) return false
            rows[id] = message.copy(status = MessageStatus.DELIVERED)
            return true
        }
        override suspend fun forwarded(id: String, localId: String, peerId: String): Boolean {
            val message = rows[id] ?: return false
            if (message.sourceId != localId || message.carrierId != peerId || message.destinationId == peerId || message.hopCount >= message.hopLimit || message.status !in listOf(MessageStatus.PENDING, MessageStatus.EXPIRED)) return false
            rows[id] = message.copy(status = MessageStatus.FORWARDED)
            return true
        }
        override suspend fun relayDelivered(id: String, localId: String, peerId: String, previousHop: Int) = false
        override suspend fun replicated(id: String, peerId: String, previousHop: Int) = false
        override suspend fun expire(now: Long) {
            rows.replaceAll { _, message ->
                if (message.status in listOf(MessageStatus.PENDING, MessageStatus.CARRIED) && DeliveryEngine.isExpired(message, now)) message.copy(status = MessageStatus.EXPIRED) else message
            }
        }
        override suspend fun savePeer(peer: Peer) { peers.value = peers.value.filterNot { it.deviceId == peer.deviceId } + peer }
    }

    private class Transport : MessageTransport {
        override val state = MutableStateFlow(SocketState())
        override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
        val sent = mutableListOf<Pair<String, WirePacket>>()
        var beforeSend: suspend (WirePacket) -> Unit = {}
        override suspend fun send(peerId: String, packet: WirePacket): Boolean {
            beforeSend(packet)
            sent += peerId to packet
            return state.value.peerId == peerId
        }
        fun connect(id: String) { state.value = SocketState("Connected", id, "Test peer") }
    }

    @Test fun `offline queue survives engine recreation and automatically retries on connection`() = runTest {
        val store = Store()
        val transport = Transport()
        val first = DeliveryEngine(a, store, transport, backgroundScope, NetworkLogger()) { time }
        val queued = first.queue(b, "Offline")
        runCurrent()
        assertEquals(MessageStatus.PENDING, store.rows[queued.messageId]!!.status)
        assertTrue(transport.sent.isEmpty())
        val resumed = DeliveryEngine(a, store, transport, backgroundScope, NetworkLogger()) { time }
        resumed.start()
        runCurrent()
        transport.connect(b)
        runCurrent()
        assertEquals(queued.messageId, transport.sent.single().second.message!!.messageId)
        assertEquals(MessageStatus.PENDING, store.rows[queued.messageId]!!.status)
        assertEquals(b, store.peers.value.single().deviceId)
    }

    @Test fun `only matching recipient ACK marks delivered`() = runTest {
        val store = Store()
        val engine = DeliveryEngine(a, store, Transport(), backgroundScope, NetworkLogger()) { time }
        val queued = engine.queue(b, "Hello")
        engine.receive(c, WirePacket(PacketType.ACK, messageId = queued.messageId))
        assertEquals(MessageStatus.PENDING, store.rows[queued.messageId]!!.status)
        engine.receive(b, WirePacket(PacketType.ACK, messageId = queued.messageId))
        assertEquals(MessageStatus.DELIVERED, store.rows[queued.messageId]!!.status)
    }

    @Test fun `lost ACK is recovered by retransmitting the same UUID without duplicate storage`() = runTest {
        val storeA = Store()
        val storeB = Store()
        val wireA = Transport().apply { connect(b) }
        val wireB = Transport().apply { connect(a) }
        val engineA = DeliveryEngine(a, storeA, wireA, backgroundScope, NetworkLogger()) { time }
        val engineB = DeliveryEngine(b, storeB, wireB, backgroundScope, NetworkLogger()) { time }
        var dropFirstAck = true
        wireA.beforeSend = { engineB.receive(a, it) }
        wireB.beforeSend = {
            if (dropFirstAck) dropFirstAck = false else engineA.receive(b, it)
        }
        val queued = engineA.queue(b, "สวัสดี 🦖\nข้อความที่ต้องเก็บไว้  ")
        runCurrent()
        assertEquals(MessageStatus.PENDING, storeA.rows[queued.messageId]!!.status)
        assertEquals(MessageStatus.RECEIVED, storeB.rows[queued.messageId]!!.status)
        engineA.retryPending()
        assertEquals(MessageStatus.DELIVERED, storeA.rows[queued.messageId]!!.status)
        assertEquals(1, storeB.rows.size)
        assertEquals(queued.payload, storeB.rows[queued.messageId]!!.payload)
        assertEquals(2, wireA.sent.size)
        assertTrue(wireA.sent.all { it.second.message!!.messageId == queued.messageId })
    }

    @Test fun `queue preserves free text and rejects blank payload without writing storage`() = runTest {
        val store = Store()
        val engine = DeliveryEngine(a, store, Transport(), backgroundScope, NetworkLogger()) { time }
        val payload = "  สวัสดีครับ 😊\n  line two  "
        val queued = engine.queue(b, payload)
        assertEquals(payload, queued.payload)
        assertEquals(payload, store.rows[queued.messageId]!!.payload)
        try { engine.queue(b, " \n "); fail("Blank messages must be rejected") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, store.rows.size)
    }

    @Test fun `late ACK can confirm an already saved message after sender TTL expired`() = runTest {
        val store = Store()
        val wire = Transport()
        var now = time
        val engine = DeliveryEngine(a, store, wire, backgroundScope, NetworkLogger()) { now }
        val queued = engine.queue(b, "already saved on B", 1)
        now += 2_000
        engine.retryPending()
        assertEquals(MessageStatus.EXPIRED, store.rows[queued.messageId]!!.status)
        engine.receive(b, WirePacket(PacketType.ACK, messageId = queued.messageId))
        assertEquals(MessageStatus.DELIVERED, store.rows[queued.messageId]!!.status)
        assertTrue(wire.sent.isEmpty())
    }

    @Test fun `duplicate message is stored once and ACKed again after commit`() = runTest {
        val store = Store()
        val transport = Transport().apply { connect(a) }
        val engine = DeliveryEngine(b, store, transport, backgroundScope, NetworkLogger()) { time }
        val message = Message(UUID.randomUUID().toString(), a, b, "hello", time, 60, MessageStatus.PENDING, hopCount = 1)
        transport.beforeSend = { assertEquals(MessageStatus.RECEIVED, store.rows[message.messageId]?.status) }
        repeat(2) { engine.receive(a, WirePacket(PacketType.MESSAGE, message = message)) }
        assertEquals(1, store.rows.size)
        assertEquals(2, transport.sent.size)
        assertTrue(transport.sent.all { it.second.type == PacketType.ACK })
    }

    @Test fun `failed receiver database insert produces no ACK`() = runTest {
        val store = Store().apply { failInsert = true }
        val transport = Transport()
        val engine = DeliveryEngine(b, store, transport, backgroundScope, NetworkLogger()) { time }
        val message = Message(UUID.randomUUID().toString(), a, b, "hello", time, 60, MessageStatus.PENDING, hopCount = 1)
        try { engine.receive(a, WirePacket(PacketType.MESSAGE, message = message)); fail("Expected storage failure") }
        catch (_: IllegalStateException) { }
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun `TTL expiry stops sending but retains expired history`() = runTest {
        val store = Store()
        val transport = Transport().apply { connect(b) }
        var now = time
        val engine = DeliveryEngine(a, store, transport, backgroundScope, NetworkLogger()) { now }
        val queued = engine.queue(b, "short lived", 1)
        now += 1_000
        engine.retryPending()
        assertEquals(MessageStatus.EXPIRED, store.rows[queued.messageId]!!.status)
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun `expired unseen message is not stored or ACKed`() = runTest {
        val store = Store()
        val transport = Transport()
        val engine = DeliveryEngine(b, store, transport, backgroundScope, NetworkLogger()) { time + 1_000 }
        val message = Message(UUID.randomUUID().toString(), a, b, "late", time, 1, MessageStatus.PENDING, hopCount = 1)
        engine.receive(a, WirePacket(PacketType.MESSAGE, message = message))
        assertTrue(store.rows.isEmpty())
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun `already saved duplicate is ACKed even after TTL elapsed`() = runTest {
        val store = Store()
        val transport = Transport()
        var now = time
        val engine = DeliveryEngine(b, store, transport, backgroundScope, NetworkLogger()) { now }
        val message = Message(UUID.randomUUID().toString(), a, b, "saved", time, 1, MessageStatus.PENDING, hopCount = 1)
        engine.receive(a, WirePacket(PacketType.MESSAGE, message = message))
        now += 2_000
        engine.receive(a, WirePacket(PacketType.MESSAGE, message = message))
        assertEquals(1, store.rows.size)
        assertEquals(2, transport.sent.size)
    }

    @Test fun `connecting another peer never forwards third party messages`() = runTest {
        val store = Store()
        val transport = Transport().apply { connect(c) }
        val engine = DeliveryEngine(a, store, transport, backgroundScope, NetworkLogger()) { time }
        engine.queue(b, "only B")
        engine.retryPending()
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun `custody survives lost ACK without duplicate storage or hop inflation`() = runTest {
        val storeA = Store()
        val storeB = Store()
        val wireA = Transport().apply { connect(b) }
        val wireB = Transport().apply { connect(a) }
        val loggerB = NetworkLogger()
        val engineA = DeliveryEngine(a, storeA, wireA, backgroundScope, NetworkLogger()) { time }
        val engineB = DeliveryEngine(b, storeB, wireB, backgroundScope, loggerB) { time }
        var dropFirstAck = true
        wireA.beforeSend = { engineB.receive(a, it) }
        wireB.beforeSend = {
            assertEquals(MessageStatus.CARRIED, storeB.rows[it.messageId]!!.status)
            assertEquals(MessageStatus.CARRIED, it.ackStatus)
            if (dropFirstAck) dropFirstAck = false else engineA.receive(b, it)
        }
        val queued = engineA.queue(c, "For C, carried by B", carrierId = b)
        runCurrent()
        assertEquals(MessageStatus.PENDING, storeA.rows[queued.messageId]!!.status)
        engineA.retryPending()
        val carried = storeB.rows[queued.messageId]!!
        val forwarded = storeA.rows[queued.messageId]!!
        assertEquals(a, carried.sourceId)
        assertEquals(c, carried.destinationId)
        assertEquals(1, carried.hopCount)
        assertEquals(queued.expiresAt, carried.expiresAt)
        assertEquals(MessageStatus.FORWARDED, forwarded.status)
        assertEquals(0, forwarded.hopCount)
        assertEquals(1, storeB.rows.size)
        assertEquals(2, wireA.sent.size)
        assertTrue(wireA.sent.all { it.second.message!!.hopCount == 1 })
        assertTrue(loggerB.logs.value.any { it.status == "CARRIED" && it.messageId == queued.messageId &&
            it.detail.contains("DTN RECEIVE") && it.detail.contains("$a -> $b") &&
            it.detail.contains("messageId=${queued.messageId}") && it.detail.contains("source=$a") &&
            it.detail.contains("destination=$c") && it.detail.contains("currentNode=$b") &&
            it.detail.contains("hopCount=1") && it.detail.contains("status=CARRIED") })
        engineA.retryPending()
        assertEquals(2, wireA.sent.size)
    }

    @Test fun `carried message is not forwarded before an inventory comparison after restart`() = runTest {
        val store = Store()
        val wire = Transport().apply { connect(a) }
        val first = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger()) { time }
        val message = Message(UUID.randomUUID().toString(), a, c, "carry", time, 60,
            MessageStatus.PENDING, hopCount = 1, carrierId = b)
        first.receive(a, WirePacket(PacketType.MESSAGE, message = message))
        wire.sent.clear()
        val resumed = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger()) { time }
        resumed.start()
        wire.connect(c)
        runCurrent()
        resumed.retryPending()
        assertEquals(MessageStatus.CARRIED, store.rows[message.messageId]!!.status)
        assertTrue(wire.sent.isEmpty())
    }

    @Test fun `custody ACK cannot masquerade as final delivery and wrong peer ACK is ignored`() = runTest {
        val store = Store()
        val engine = DeliveryEngine(a, store, Transport(), backgroundScope, NetworkLogger()) { time }
        val message = engine.queue(c, "for C", carrierId = b)
        engine.receive(b, WirePacket(PacketType.ACK, messageId = message.messageId))
        engine.receive(c, WirePacket(PacketType.ACK, messageId = message.messageId, ackStatus = MessageStatus.CARRIED))
        engine.receive(c, WirePacket(PacketType.ACK, messageId = message.messageId))
        assertEquals(MessageStatus.PENDING, store.rows[message.messageId]!!.status)
        engine.receive(b, WirePacket(PacketType.ACK, messageId = message.messageId, ackStatus = MessageStatus.CARRIED))
        assertEquals(MessageStatus.FORWARDED, store.rows[message.messageId]!!.status)
        engine.receive(b, WirePacket(PacketType.ACK, messageId = message.messageId, ackStatus = MessageStatus.CARRIED))
        assertEquals(0, store.rows[message.messageId]!!.hopCount)
        val direct = engine.queue(b, "direct")
        engine.receive(b, WirePacket(PacketType.ACK, messageId = direct.messageId, ackStatus = MessageStatus.CARRIED))
        assertEquals(MessageStatus.PENDING, store.rows[direct.messageId]!!.status)
    }

    @Test fun `hop limit accepts last hop but exhausted stored copy cannot send again`() = runTest {
        val store = Store()
        val wire = Transport().apply { connect(b) }
        val engine = DeliveryEngine(a, store, wire, backgroundScope, NetworkLogger()) { time }
        val exhausted = Message(UUID.randomUUID().toString(), a, c, "exhausted", time, 60,
            MessageStatus.PENDING, hopCount = 1, hopLimit = 1, carrierId = b)
        store.insert(exhausted)
        engine.retryPending()
        assertTrue(wire.sent.isEmpty())
        val receiverStore = Store()
        val receiverWire = Transport().apply { connect(a) }
        val receiver = DeliveryEngine(b, receiverStore, receiverWire, backgroundScope, NetworkLogger()) { time }
        receiver.receive(a, WirePacket(PacketType.MESSAGE, message = exhausted))
        assertEquals(exhausted.copy(status = MessageStatus.CARRIED), receiverStore.rows[exhausted.messageId])
        assertEquals(1, receiverWire.sent.size)
        engine.queue(c, "can arrive at limit", carrierId = b, hopLimit = 1)
        engine.retryPending()
        assertEquals(1, wire.sent.single().second.message!!.hopCount)
        val admissible = exhausted.copy(messageId = UUID.randomUUID().toString(), hopLimit = 2)
        receiver.receive(a, WirePacket(PacketType.MESSAGE, message = admissible))
        assertEquals(MessageStatus.CARRIED, receiverStore.rows[admissible.messageId]!!.status)
        assertEquals(2, receiverWire.sent.size)
        try { engine.queue(c, "invalid", carrierId = b, hopLimit = 0); fail("Invalid limit") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun `non destination accepts a first hop without optional carrier hint`() = runTest {
        val store = Store()
        val wire = Transport().apply { connect(a) }
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger()) { time }
        val message = Message(UUID.randomUUID().toString(), a, c, "Hello C", time, 60,
            MessageStatus.PENDING, hopCount = 1)
        repeat(2) { engine.receive(a, WirePacket(PacketType.MESSAGE, message = message)) }
        assertEquals(1, store.rows.size)
        assertEquals(message.copy(status = MessageStatus.CARRIED), store.rows[message.messageId])
        assertEquals(2, wire.sent.size)
        assertTrue(wire.sent.all { it.second.ackStatus == MessageStatus.CARRIED })
    }

    @Test fun `admission rejects expired and exhausted bundles for both final and carrier nodes`() = runTest {
        for (destination in listOf(b, c)) {
            val store = Store()
            val wire = Transport().apply { connect(a) }
            val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger()) { time }
            val base = Message(UUID.randomUUID().toString(), a, destination, "boundary", time - 1000, 1,
                MessageStatus.PENDING, hopCount = 1)
            // Exact expiration boundary, one millisecond after it,
            // and over-budget wire input must all leave Room untouched with no ACK.
            for (invalid in listOf(base, base.copy(createdAt = time - 1001, expiresAt = time - 1),
                base.copy(createdAt = time, expiresAt = time + 1000, hopCount = 2, hopLimit = 1))) {
                try { engine.receive(a, WirePacket(PacketType.MESSAGE, message = invalid)) }
                catch (_: IllegalArgumentException) { /* Invalid wire hop is rejected before storage. */ }
                assertTrue(store.rows.isEmpty())
                assertTrue(wire.sent.isEmpty())
            }
            val justBeforeExpiry = base.copy(createdAt = time - 999, expiresAt = time + 1)
            engine.receive(a, WirePacket(PacketType.MESSAGE, message = justBeforeExpiry))
            assertEquals(if (destination == b) MessageStatus.RECEIVED else MessageStatus.CARRIED,
                store.rows[base.messageId]!!.status)
        }
    }

    @Test fun `carried messages expire persistently and unseen expired custody is not acknowledged`() = runTest {
        val store = Store()
        val wire = Transport().apply { connect(a) }
        var clock = time
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger()) { clock }
        val message = Message(UUID.randomUUID().toString(), a, c, "carry briefly", time, 1,
            MessageStatus.PENDING, hopCount = 1, carrierId = b)
        engine.receive(a, WirePacket(PacketType.MESSAGE, message = message))
        clock += 1000
        engine.retryPending()
        assertEquals(MessageStatus.EXPIRED, store.rows[message.messageId]!!.status)
        engine.receive(a, WirePacket(PacketType.MESSAGE, message = message))
        assertEquals(2, wire.sent.size) // Re-ACK only an already committed UUID.
        engine.receive(a, WirePacket(PacketType.MESSAGE, message = message.copy(messageId = UUID.randomUUID().toString())))
        assertEquals(1, store.rows.size)
        assertEquals(2, wire.sent.size)
    }

    @Test fun `custody insert failure and conflicting duplicates never produce a false ACK`() = runTest {
        val store = Store().apply { failInsert = true }
        val wire = Transport().apply { connect(a) }
        val engine = DeliveryEngine(b, store, wire, backgroundScope, NetworkLogger()) { time }
        val message = Message(UUID.randomUUID().toString(), a, c, "carry", time, 60,
            MessageStatus.PENDING, hopCount = 1, carrierId = b)
        try { engine.receive(a, WirePacket(PacketType.MESSAGE, message = message)); fail("Disk failure") }
        catch (_: IllegalStateException) { }
        assertTrue(wire.sent.isEmpty())
        store.failInsert = false
        engine.receive(a, WirePacket(PacketType.MESSAGE, message = message))
        for (invalid in listOf(message.copy(payload = "collision"), message.copy(hopLimit = 9),
            message.copy(carrierId = null))) {
            try { engine.receive(a, WirePacket(PacketType.MESSAGE, message = invalid)); fail("Invalid transfer") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(1, wire.sent.size)
        assertEquals(1, store.rows.size)
    }

    @Test fun `selected carrier is retained offline and other peers cannot receive the bundle`() = runTest {
        val store = Store()
        val wire = Transport()
        val engine = DeliveryEngine(a, store, wire, backgroundScope, NetworkLogger()) { time }
        val message = engine.queue(c, "only via B", carrierId = b)
        runCurrent()
        wire.connect(c)
        engine.retryPending()
        assertTrue(wire.sent.isEmpty())
        val resumed = DeliveryEngine(a, store, wire, backgroundScope, NetworkLogger()) { time }
        resumed.start()
        runCurrent() // Finish startup while the wrong peer is still connected.
        wire.connect(b)
        runCurrent()
        assertEquals(message.messageId, wire.sent.single().second.message!!.messageId)
        assertEquals(1, wire.sent.single().second.message!!.hopCount)
    }

    @Test fun `wrong recipient and UUID content collisions are rejected`() = runTest {
        val store = Store()
        val transport = Transport()
        val engine = DeliveryEngine(b, store, transport, backgroundScope, NetworkLogger()) { time }
        val message = Message(UUID.randomUUID().toString(), a, b, "original", time, 60, MessageStatus.PENDING, hopCount = 1)
        engine.receive(a, WirePacket(PacketType.MESSAGE, message = message))
        for (invalid in listOf(message.copy(payload = "changed"), message.copy(destinationId = c))) {
            try { engine.receive(a, WirePacket(PacketType.MESSAGE, message = invalid)); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(1, transport.sent.size)
        assertEquals("original", store.rows[message.messageId]!!.payload)
    }
}
