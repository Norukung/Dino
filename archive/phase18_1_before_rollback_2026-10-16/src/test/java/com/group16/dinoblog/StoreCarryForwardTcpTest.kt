package com.group16.dinoblog

import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.dtn.InventoryManager
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.TcpTransport
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class StoreCarryForwardTcpTest {
    private class Node(val id: String = UUID.randomUUID().toString()) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = Phase4TestStore()
        val log = NetworkLogger()
        val transport = TcpTransport(id, id.take(8), scope, log)
        val inventory = InventoryManager(id, store, transport, scope, log)
        val delivery = DeliveryEngine(id, store, transport, scope, log, inventory.state, smartRelaySelection = false)
        init {
            transport.onInventory = inventory::receive
            inventory.start()
            delivery.start()
            transport.onSessionPacket = { peer, session, packet -> delivery.receive(peer, packet, session) }
        }
        fun close() { transport.stop(); scope.cancel() }
    }

    @Test fun `real A to B then B to C preserves bundles and reconnect inventory prevents resend`(): Unit = runBlocking {
        exercise(dropAck = false)
    }

    @Test fun `real final ACK loss keeps B custody while C receipts prevent resend on reconnect`(): Unit = runBlocking {
        exercise(dropAck = true)
    }

    private suspend fun exercise(dropAck: Boolean) {
        val a = Node()
        val b = Node()
        val c = Node()
        val dropped = MutableStateFlow(0)
        try {
            withTimeout(20000) {
                b.transport.start(true, "127.0.0.1")
                b.transport.state.first { it.status.startsWith("Listening") }
                a.transport.start(false, "127.0.0.1")
                a.inventory.state.first { it.sent && it.remoteReceived }
                b.inventory.state.first { it.sent && it.remoteReceived }
                val originals = List(3) { a.delivery.queue(c.id, "Hello C $it", carrierId = b.id) }
                a.store.messages.first { rows -> originals.all { original -> rows.any { it.messageId == original.messageId && it.status == MessageStatus.FORWARDED } } }
                val forD = a.delivery.queue(UUID.randomUUID().toString(), "Only D", carrierId = b.id)
                a.store.messages.first { rows -> rows.any { it.messageId == forD.messageId && it.status == MessageStatus.FORWARDED } }
                originals.forEach { assertEquals(it.copy(status = MessageStatus.CARRIED, hopCount = 1, copiesRemaining = 2), b.store.rows[it.messageId]) }
                a.close() // A disappears completely before C connects.
                b.inventory.state.first { it.peerId == null }

                b.transport.onSessionPacket = { peer, session, packet ->
                    if (dropAck && packet.type == PacketType.ACK && peer == c.id) dropped.value += 1
                    else b.delivery.receive(peer, packet, session)
                }
                c.transport.start(false, "127.0.0.1")
                c.store.messages.first { rows -> originals.all { original -> rows.any { it.messageId == original.messageId } } }
                c.store.peers.first { peers -> peers.any { it.deviceId == a.id } }
                if (dropAck) dropped.first { it >= originals.size }
                else b.store.messages.first { rows -> originals.all { original -> rows.any { it.messageId == original.messageId && it.status == MessageStatus.DELIVERED } } }
                val firstSession = b.transport.state.value.sessionId
                originals.forEach {
                    assertEquals(it.copy(status = MessageStatus.RECEIVED, hopCount = 2, copiesRemaining = 2), c.store.rows[it.messageId])
                    assertEquals(if (dropAck) MessageStatus.CARRIED else MessageStatus.DELIVERED, b.store.rows[it.messageId]!!.status)
                    assertEquals(1, b.store.rows[it.messageId]!!.hopCount)
                    assertEquals(MessageStatus.FORWARDED, a.store.rows[it.messageId]!!.status) // No receipt propagation to A.
                }
                c.store.messages.first { rows -> rows.any { it.messageId == forD.messageId } }
                assertEquals(MessageStatus.CARRIED, c.store.rows[forD.messageId]!!.status)
                assertEquals(MessageStatus.CARRIED, b.store.rows[forD.messageId]!!.status)

                c.transport.stop()
                b.inventory.state.first { it.peerId == null }
                b.delivery.forwarding.first { it.peerId == null }
                c.inventory.state.first { it.peerId == null }
                assertTrue(b.inventory.state.value.remoteMessageIds.isEmpty())
                assertTrue(b.delivery.forwarding.value.awaitingAckIds.isEmpty())
                b.log.clear()
                c.transport.start(false, "127.0.0.1")
                val next = b.inventory.state.first { it.sessionId != firstSession && it.sent && it.remoteReceived }
                assertTrue(next.remoteMessageIds.containsAll(originals.map { it.messageId }))
                assertFalse(next.missingOnPeer.any { id -> originals.any { it.messageId == id } })
                b.delivery.retryPending() // Deterministically exercise the completed new comparison.
                assertFalse(b.log.logs.value.any { it.direction == "TX" && it.detail == "MESSAGE" })
                assertEquals(originals.size + 1, c.store.rows.size)
            }
        } finally { a.close(); c.close(); b.close() }
    }
}
