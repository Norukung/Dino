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
            it.senderId == localId && it.receiverId == peerId && it.status == MessageStatus.PENDING
        }
        override suspend fun delivered(id: String, localId: String, peerId: String): Boolean {
            val message = rows[id] ?: return false
            if (message.senderId != localId || message.receiverId != peerId || message.status !in listOf(MessageStatus.PENDING, MessageStatus.EXPIRED)) return false
            rows[id] = message.copy(status = MessageStatus.DELIVERED)
            return true
        }
        override suspend fun expire(now: Long) {
            rows.replaceAll { _, message ->
                if (message.status == MessageStatus.PENDING && DeliveryEngine.isExpired(message, now)) message.copy(status = MessageStatus.EXPIRED) else message
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

    @Test fun `duplicate message is stored once and ACKed again after commit`() = runTest {
        val store = Store()
        val transport = Transport().apply { connect(a) }
        val engine = DeliveryEngine(b, store, transport, backgroundScope, NetworkLogger()) { time }
        val message = Message(UUID.randomUUID().toString(), a, b, "hello", time, 60, MessageStatus.PENDING)
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
        val message = Message(UUID.randomUUID().toString(), a, b, "hello", time, 60, MessageStatus.PENDING)
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
        val message = Message(UUID.randomUUID().toString(), a, b, "late", time, 1, MessageStatus.PENDING)
        engine.receive(a, WirePacket(PacketType.MESSAGE, message = message))
        assertTrue(store.rows.isEmpty())
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun `already saved duplicate is ACKed even after TTL elapsed`() = runTest {
        val store = Store()
        val transport = Transport()
        var now = time
        val engine = DeliveryEngine(b, store, transport, backgroundScope, NetworkLogger()) { now }
        val message = Message(UUID.randomUUID().toString(), a, b, "saved", time, 1, MessageStatus.PENDING)
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

    @Test fun `wrong recipient and UUID content collisions are rejected`() = runTest {
        val store = Store()
        val transport = Transport()
        val engine = DeliveryEngine(b, store, transport, backgroundScope, NetworkLogger()) { time }
        val message = Message(UUID.randomUUID().toString(), a, b, "original", time, 60, MessageStatus.PENDING)
        engine.receive(a, WirePacket(PacketType.MESSAGE, message = message))
        for (invalid in listOf(message.copy(payload = "changed"), message.copy(receiverId = c))) {
            try { engine.receive(a, WirePacket(PacketType.MESSAGE, message = invalid)); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(1, transport.sent.size)
        assertEquals("original", store.rows[message.messageId]!!.payload)
    }
}
