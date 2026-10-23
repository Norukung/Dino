package com.group16.dinoblog

import com.group16.dinoblog.dtn.*
import com.group16.dinoblog.model.*
import com.group16.dinoblog.network.*
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DeliveryReceiptTcpTest {
    private class Node {
        val id = UUID.randomUUID().toString()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ReceiptTestStore()
        val log = NetworkLogger()
        val tcp = TcpTransport(id, id.take(8), scope, log)
        val inventory = InventoryManager(id, store, tcp, scope, log)
        val engine = DeliveryEngine(id, store, tcp, scope, log, inventory.state, smartRelaySelection = false)
        var dropReceiptAck = false
        init {
            tcp.onInventory = inventory::receive
            inventory.start(); engine.start()
            tcp.onSessionPacket = { peer, session, packet ->
                if (!dropReceiptAck || packet.type != PacketType.DELIVERY_RECEIPT_ACK) engine.receive(peer, packet, session)
            }
        }
        fun close() { tcp.stop(); scope.cancel() }
    }
    private suspend fun connect(one: Node, two: Node) {
        one.tcp.start(true, "127.0.0.1")
        one.tcp.state.first { it.status.startsWith("Listening") }
        two.tcp.start(false, "127.0.0.1")
        one.inventory.state.first { it.sent && it.remoteReceived }
        two.inventory.state.first { it.sent && it.remoteReceived }
    }
    private suspend fun disconnect(one: Node, two: Node) {
        one.tcp.stop(); two.tcp.stop()
        for (n in listOf(one, two)) {
            n.inventory.state.first { it.peerId == null }
            n.engine.forwarding.first { it.peerId == null }
            assertTrue(n.engine.forwarding.value.inFlightReceiptIds.isEmpty())
            assertTrue(n.engine.forwarding.value.peerKnownReceiptIds.isEmpty())
            assertTrue(n.inventory.state.value.remoteDeliveredMessageIds.isEmpty())
        }
    }
    @Test fun disconnectedDeliveryKnowledgeReturnsToSourceAndSuppressesOldReplicas(): Unit = runBlocking {
        val a = Node(); val b = Node(); val c = Node(); val d = Node(); val e = Node()
        try { withTimeout(40000) {
            connect(b, a)
            val m = a.engine.queue(d.id, "Phase 6", carrierId = b.id)
            a.store.messages.first { rows -> rows.any { it.messageId == m.messageId && it.status == MessageStatus.FORWARDED } }
            assertTrue(b.store.receipts.value.isEmpty())
            assertNotEquals(MessageStatus.DELIVERED, a.store.get(m.messageId)!!.status)
            disconnect(a, b)
            connect(b, c)
            c.store.messages.first { it.any { row -> row.messageId == m.messageId } }
            disconnect(b, c)
            // A/B are disconnected. Only D originates delivery knowledge.
            connect(c, d)
            d.store.receipts.first { it.any { r -> r.originalMessageId == m.messageId } }
            d.store.peers.first { it.any { p -> p.deviceId == a.id } }
            assertEquals(MessageStatus.RECEIVED, d.store.get(m.messageId)!!.status)
            val receipt = d.store.receipt(m.messageId)!!
            disconnect(c, d)
            connect(d, c)
            c.store.receipts.first { it.contains(receipt) }
            disconnect(d, c)
            // Lost receipt ACK is repaired by inventory on reconnect.
            c.dropReceiptAck = true
            connect(c, b)
            b.store.receipts.first { it.contains(receipt) }
            disconnect(c, b)
            c.dropReceiptAck = false; c.log.clear()
            connect(c, b); c.engine.retryPending()
            assertFalse(c.log.logs.value.any { it.direction == "TX" && it.detail == "DELIVERY_RECEIPT" })
            disconnect(c, b)
            connect(b, a)
            a.store.receipts.first { it.contains(receipt) }
            assertEquals(MessageStatus.DELIVERED, a.store.get(m.messageId)!!.status)
            assertEquals(0, a.store.get(m.messageId)!!.hopCount)
            assertEquals(1, b.store.get(m.messageId)!!.hopCount)
            assertEquals(2, c.store.get(m.messageId)!!.hopCount)
            assertEquals(3, d.store.get(m.messageId)!!.hopCount)
            disconnect(b, a)
            b.log.clear(); connect(b, e); b.engine.retryPending()
            e.store.receipts.first { it.contains(receipt) }
            assertNull(e.store.get(m.messageId))
            assertFalse(b.log.logs.value.any { it.direction == "TX" && it.detail == "MESSAGE" })
            for (n in listOf(a,b,c,d,e)) assertEquals(1, n.store.receipts.value.size)
        } } finally { listOf(a,b,c,d,e).forEach { it.close() } }
    }
}
