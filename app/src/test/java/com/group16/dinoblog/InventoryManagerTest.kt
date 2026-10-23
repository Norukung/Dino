package com.group16.dinoblog

import com.group16.dinoblog.dtn.InventoryManager
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.network.MessageTransport
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.repository.InventoryStore
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryManagerTest {
    private val b = UUID.randomUUID().toString()
    private val c = UUID.randomUUID().toString()
    private val m1 = UUID.randomUUID().toString()
    private val m2 = UUID.randomUUID().toString()
    private val m3 = UUID.randomUUID().toString()
    private class Store(var ids: List<String>) : InventoryStore {
        var beforeRead: suspend () -> Unit = {}
        val readTimes = mutableListOf<Long>()
        override suspend fun forwardableMessageIds(now: Long): List<String> {
            readTimes += now
            beforeRead()
            return ids
        }
    }
    private class Wire : MessageTransport {
        override val state = MutableStateFlow(SocketState())
        override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
        val sent = mutableListOf<WirePacket>()
        override suspend fun send(peerId: String, packet: WirePacket): Boolean {
            if (state.value.peerId != peerId) return false
            sent += packet
            return true
        }
        fun connect(peer: String, session: String = "session-1") {
            state.value = SocketState("Connected", peer, sessionId = session)
        }
    }
    private fun packet(vararg ids: String) = WirePacket(PacketType.INVENTORY, deviceId = c, messageIds = ids.toList())

    @Test fun `send waits for HELLO then sends one ID only snapshot per session`() = runTest {
        val wire = Wire()
        val store = Store(listOf(m2, m1, m1, "bad-id"))
        val manager = InventoryManager(b, store, wire, backgroundScope, NetworkLogger()) { 1000 }
        manager.start()
        runCurrent()
        wire.state.value = SocketState(awaitingHello = true)
        runCurrent()
        assertTrue(wire.sent.isEmpty())
        assertTrue(store.readTimes.isEmpty())
        wire.connect(c)
        runCurrent()
        assertEquals(1, wire.sent.size)
        assertEquals(listOf(m1, m2).sorted(), wire.sent.single().messageIds)
        assertEquals(b, wire.sent.single().deviceId)
        assertNull(wire.sent.single().message)
        assertEquals(PacketType.INVENTORY, wire.sent.single().type)
        assertEquals(listOf(1000L), store.readTimes)
        wire.state.value = wire.state.value.copy(status = "Still connected")
        runCurrent()
        assertEquals(1, wire.sent.size)
    }

    @Test fun `comparison is set based idempotent and never sends messages or requests`() = runTest {
        val wire = Wire().apply { connect(c) }
        val manager = InventoryManager(b, Store(listOf(m1, m2)), wire, backgroundScope, NetworkLogger()) { 1000 }
        manager.start()
        runCurrent()
        manager.receive(c, "session-1", packet(m1, m3, m1))
        val first = manager.state.value
        assertEquals(listOf(m2), first.missingOnPeer)
        assertEquals(listOf(m3), first.missingLocally)
        assertEquals(listOf(m1, m3).sorted(), first.remoteMessageIds)
        manager.receive(c, "session-1", packet(m1, m3, m1))
        assertEquals(first, manager.state.value)
        assertEquals(1, wire.sent.size) // Receiving inventory is not a reply/forward trigger.
        assertTrue(wire.sent.all { it.type == PacketType.INVENTORY })
    }

    @Test fun `identical and empty inventories are completed comparisons not waiting`() = runTest {
        val wire = Wire().apply { connect(c) }
        val manager = InventoryManager(b, Store(listOf(m1)), wire, backgroundScope, NetworkLogger())
        manager.start()
        runCurrent()
        manager.receive(c, "session-1", packet(m1))
        assertTrue(manager.state.value.remoteReceived)
        assertTrue(manager.state.value.missingOnPeer.isEmpty())
        assertTrue(manager.state.value.missingLocally.isEmpty())
        manager.receive(c, "session-1", packet())
        assertTrue(manager.state.value.remoteReceived)
        assertTrue(manager.state.value.remoteMessageIds.isEmpty())
        assertEquals(listOf(m1), manager.state.value.missingOnPeer)
    }

    @Test fun `disconnect clears remote differences and same peer reconnect exchanges again`() = runTest {
        val wire = Wire().apply { connect(c) }
        val store = Store(listOf(m1, m2))
        val manager = InventoryManager(b, store, wire, backgroundScope, NetworkLogger())
        manager.start()
        runCurrent()
        manager.receive(c, "session-1", packet(m1))
        wire.state.value = SocketState()
        runCurrent()
        assertNull(manager.state.value.peerId)
        assertTrue(manager.state.value.remoteMessageIds.isEmpty())
        assertTrue(manager.state.value.missingOnPeer.isEmpty())
        assertTrue(manager.state.value.missingLocally.isEmpty())
        assertEquals(listOf(m1, m2), store.ids)
        wire.connect(c, "session-2")
        runCurrent()
        assertEquals(2, wire.sent.size)
        assertFalse(manager.state.value.remoteReceived)
        manager.receive(c, "session-1", packet(m1)) // Late old-session packet.
        assertFalse(manager.state.value.remoteReceived)
        manager.receive(c, "session-2", packet(m1, m2))
        assertTrue(manager.state.value.missingOnPeer.isEmpty())
    }

    @Test fun `incoming inventory before collector catches up is not erased`() = runTest {
        val wire = Wire()
        val manager = InventoryManager(b, Store(listOf(m1, m2)), wire, backgroundScope, NetworkLogger())
        manager.start()
        runCurrent()
        wire.connect(c)
        manager.receive(c, "session-1", packet(m1))
        runCurrent()
        assertTrue(manager.state.value.remoteReceived)
        assertEquals(listOf(m2), manager.state.value.missingOnPeer)
        assertEquals(1, wire.sent.size)
    }

    @Test fun `late Room comparison cannot repopulate remote state after disconnect`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val wire = Wire().apply { connect(c) }
        val store = Store(listOf(m1, m2))
        val manager = InventoryManager(b, store, wire, backgroundScope, NetworkLogger())
        manager.start()
        runCurrent()
        store.beforeRead = { gate.await() }
        val receive = launch { manager.receive(c, "session-1", packet(m1)) }
        runCurrent()
        wire.state.value = SocketState()
        runCurrent()
        gate.complete(Unit)
        receive.join()
        assertNull(manager.state.value.peerId)
        assertTrue(manager.state.value.remoteMessageIds.isEmpty())
        assertTrue(manager.state.value.missingOnPeer.isEmpty())
    }

    @Test fun `slow initial build cannot send into reconnect even with identical peer UUID`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var first = true
        val store = Store(listOf(m1)).apply {
            beforeRead = { if (first) { first = false; withContext(NonCancellable) { gate.await() } } }
        }
        val wire = Wire().apply { connect(c) }
        val manager = InventoryManager(b, store, wire, backgroundScope, NetworkLogger())
        manager.start()
        runCurrent()
        wire.connect(c, "session-2") // Conflated StateFlow can skip a brief disconnect.
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, wire.sent.size)
        assertEquals("session-2", manager.state.value.sessionId)
    }

    @Test fun `build and receive use current eligible query rather than cached IDs`() = runTest {
        var time = 1000L
        val wire = Wire().apply { connect(c) }
        val store = Store(listOf(m1, m2))
        val manager = InventoryManager(b, store, wire, backgroundScope, NetworkLogger()) { time }
        manager.start()
        runCurrent()
        store.ids = listOf(m1) // DAO no longer offers expired/deleted m2.
        time = 2000
        manager.receive(c, "session-1", packet(m1))
        assertEquals(listOf(1000L, 2000L), store.readTimes)
        assertEquals(listOf(m1), manager.state.value.localMessageIds)
        assertTrue(manager.state.value.missingOnPeer.isEmpty())
    }

    @Test fun `wrong identity and pre HELLO inventories cannot become peer state`() = runTest {
        val wire = Wire()
        val store = Store(listOf(m1))
        val manager = InventoryManager(b, store, wire, backgroundScope, NetworkLogger())
        manager.receive(c, "session-1", packet(m2))
        assertTrue(store.readTimes.isEmpty())
        wire.connect(c)
        manager.receive(c, "session-1", packet(m2).copy(deviceId = b))
        assertFalse(manager.state.value.remoteReceived)
        assertTrue(store.readTimes.isEmpty())
        assertNotNull(manager.state.value.error)
    }

    @Test fun `database failure and oversized inventories never masquerade as complete empty inventories`() = runTest {
        for (failure in listOf(true, false)) {
            val wire = Wire().apply { connect(c) }
            val store = Store(List(WireProtocol.MAX_INVENTORY_IDS + 1) { UUID.randomUUID().toString() }).apply {
                if (failure) beforeRead = { error("Room unavailable") }
            }
            val manager = InventoryManager(b, store, wire, backgroundScope, NetworkLogger())
            manager.start()
            runCurrent()
            assertTrue(wire.sent.isEmpty())
            assertFalse(manager.state.value.localReady)
            assertNotNull(manager.state.value.error)
        }
    }
}
