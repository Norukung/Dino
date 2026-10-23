package com.group16.dinoblog

import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.dtn.InventoryManager
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.TcpTransport
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class EpidemicTcpTest {
    private class Node {
        val id = UUID.randomUUID().toString()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = Phase4TestStore()
        val log = NetworkLogger()
        val tcp = TcpTransport(id, id.take(8), scope, log)
        val inventory = InventoryManager(id, store, tcp, scope, log)
        val engine = DeliveryEngine(id, store, tcp, scope, log, inventory.state)
        init {
            tcp.onInventory = inventory::receive
            inventory.start(); engine.start()
            tcp.onSessionPacket = { peer, session, packet -> engine.receive(peer, packet, session) }
        }
        fun close() { tcp.stop(); scope.cancel() }
    }
    private suspend fun connect(server: Node, client: Node) {
        server.tcp.start(true, "127.0.0.1")
        server.tcp.state.first { it.status.startsWith("Listening") }
        client.tcp.start(false, "127.0.0.1")
        server.inventory.state.first { it.sent && it.remoteReceived }
        client.inventory.state.first { it.sent && it.remoteReceived }
    }
    private suspend fun disconnect(one: Node, two: Node) {
        one.tcp.stop(); two.tcp.stop()
        for (node in listOf(one, two)) {
            node.inventory.state.first { it.peerId == null }
            node.engine.forwarding.first { it.peerId == null }
            assertTrue(node.engine.forwarding.value.peerKnownIds.isEmpty())
            assertTrue(node.engine.forwarding.value.awaitingAckIds.isEmpty())
        }
    }

    @Test fun `offline A B C D chain and B E branch use same UUID with depths zero one two three`(): Unit = runBlocking {
        exercise(false)
    }
    @Test fun `lost relay ACK reconnect advertises stored copy and prevents unnecessary replication`(): Unit = runBlocking {
        exercise(true)
    }

    private suspend fun exercise(dropRelayAck: Boolean) {
        val a = Node(); val b = Node(); val c = Node(); val d = Node(); val e = Node()
        try {
            withTimeout(30000) {
                connect(b, a)
                val messages = List(3) { a.engine.queue(d.id, "Hello D $it", hopLimit = 3, carrierId = b.id) }
                val ids = messages.map { it.messageId }
                a.store.messages.first { rows -> ids.all { id -> rows.any { it.messageId == id && it.status == MessageStatus.FORWARDED } } }
                assertTrue(a.store.rows.values.all { it.hopCount == 0 })
                assertTrue(b.store.rows.values.all { it.hopCount == 1 && it.status == MessageStatus.CARRIED })
                disconnect(a, b); a.close()

                val dropped = MutableStateFlow(0)
                if (dropRelayAck) b.tcp.onSessionPacket = { peer, session, packet ->
                    if (packet.type == PacketType.ACK && peer == c.id) dropped.value += 1
                    else b.engine.receive(peer, packet, session)
                }
                connect(b, c)
                c.store.messages.first { rows -> ids.all { id -> rows.any { it.messageId == id } } }
                if (dropRelayAck) dropped.first { it >= ids.size }
                else b.engine.forwarding.first { it.peerKnownIds.containsAll(ids) && it.awaitingAckIds.isEmpty() }
                messages.forEach { original ->
                    assertEquals(original.copy(status = MessageStatus.CARRIED, hopCount = 2), c.store.rows[original.messageId])
                    assertEquals(original.copy(status = MessageStatus.CARRIED, hopCount = 1), b.store.rows[original.messageId])
                }
                assertFalse(c.store.peers.value.any { it.deviceId == a.id })
                assertFalse(c.log.logs.value.any { it.direction == "TX" && it.detail == "MESSAGE" })
                disconnect(b, c)

                b.log.clear(); connect(b, c)
                assertTrue(b.inventory.state.value.remoteMessageIds.containsAll(ids))
                b.engine.retryPending()
                assertFalse(b.log.logs.value.any { it.direction == "TX" && it.detail == "MESSAGE" })
                assertEquals(ids.size, c.store.rows.size)
                disconnect(b, c)

                // B's second independent branch must also receive depth 2, never 3.
                connect(b, e)
                e.store.messages.first { rows -> ids.all { id -> rows.any { it.messageId == id } } }
                assertTrue(e.store.rows.values.all { it.hopCount == 2 && it.status == MessageStatus.CARRIED })
                assertTrue(b.store.rows.values.all { it.hopCount == 1 })
                disconnect(b, e); b.close(); e.close()

                // Neither A nor B remains alive when C meets final destination D.
                connect(c, d)
                c.store.messages.first { rows -> ids.all { id -> rows.any { it.messageId == id && it.status == MessageStatus.DELIVERED } } }
                d.store.peers.first { peers -> peers.any { it.deviceId == a.id } }
                messages.forEach { original ->
                    assertEquals(original.copy(status = MessageStatus.RECEIVED, hopCount = 3), d.store.rows[original.messageId])
                    assertEquals(original.copy(status = MessageStatus.DELIVERED, hopCount = 2), c.store.rows[original.messageId])
                }
                assertEquals(ids.size, d.store.rows.size)
                disconnect(c, d)
                c.log.clear(); connect(c, d); c.engine.retryPending(); d.engine.retryPending()
                assertFalse(c.log.logs.value.any { it.direction == "TX" && it.detail == "MESSAGE" })
                assertFalse(d.log.logs.value.any { it.direction == "TX" && it.detail == "MESSAGE" })
            }
        } finally { a.close(); b.close(); c.close(); d.close(); e.close() }
    }
}
