package com.group16.dinoblog

import com.group16.dinoblog.dtn.*
import com.group16.dinoblog.model.*
import com.group16.dinoblog.network.*
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
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
            assertTrue(node.inventory.state.value.remoteReceiptIds.isEmpty())
            assertTrue(node.inventory.state.value.receiptMissingOnPeer.isEmpty())
            assertTrue(node.engine.forwarding.value.receiptPeerKnownIds.isEmpty())
            assertTrue(node.engine.forwarding.value.receiptAwaitingAckIds.isEmpty())
        }
    }
    @Test fun `offline branched message delivery returns receipt through alternative peers to origin`() = runBlocking {
        exercise(false)
    }
    @Test fun `lost receipt ACK is recovered by receipt inventory with no duplicate transfer`() = runBlocking {
        exercise(true)
    }

    private suspend fun exercise(dropReceiptAck: Boolean) {
        val a = Node(); val b = Node(); val c = Node(); val d = Node(); val e = Node(); val f = Node()
        val nodes = listOf(a, b, c, d, e, f)
        var stage = "A-B"
        try {
            withTimeout(45000) {
                val messages = List(3) { a.engine.queue(d.id, "Offline receipt demo $it") }
                val ids = messages.map { it.messageId }
                val receiptIds = ids.map(DeliveryReceipt::idFor)
                connect(b, a)
                a.store.base.messages.first { rows -> ids.all { id -> rows.any { it.messageId == id && it.status == MessageStatus.FORWARDED } } }
                disconnect(a, b) // A cannot participate while B meets C/E.
                stage = "B-C"
                connect(b, c)
                b.engine.forwarding.first { it.peerKnownIds.containsAll(ids) }
                assertTrue(c.store.base.rows.values.all { it.status == MessageStatus.CARRIED && it.hopCount == 2 })
                disconnect(b, c)
                stage = "B-E"
                connect(b, e) // A second original message branch.
                b.engine.forwarding.first { it.peerKnownIds.containsAll(ids) }
                assertTrue(e.store.base.rows.values.all { it.status == MessageStatus.CARRIED && it.hopCount == 2 })
                assertTrue(b.store.base.rows.values.all { it.hopCount == 1 })
                disconnect(b, e)
                assertTrue(nodes.all { it.store.receipts.value.isEmpty() })

                stage = "C-D"
                connect(c, d) // Only C and D connected. D is the actual final destination.
                d.store.receipts.first { it.size == ids.size }
                c.store.receipts.first { it.size == ids.size }
                d.engine.forwarding.first { it.receiptPeerKnownIds.containsAll(receiptIds) }
                assertTrue(d.store.base.rows.values.all { it.status == MessageStatus.RECEIVED && it.hopCount == 3 })
                assertTrue(d.store.receipts.value.all { it.hopCount == 0 })
                assertTrue(c.store.receipts.value.all { it.hopCount == 1 })
                assertEquals(ids.size, d.store.base.rows.size)
                assertTrue(a.store.receipts.value.isEmpty()); assertTrue(b.store.receipts.value.isEmpty())
                disconnect(c, d); c.close()

                stage = "D-E"
                connect(d, e) // Receipt takes D -> E -> B -> A, not a reverse route.
                e.store.receipts.first { it.size == ids.size }
                d.engine.forwarding.first { it.receiptPeerKnownIds.containsAll(receiptIds) }
                assertTrue(e.store.base.rows.values.all { it.status == MessageStatus.DELIVERED && it.hopCount == 2 })
                assertTrue(e.store.receipts.value.all { it.hopCount == 1 })
                assertTrue(d.store.receipts.value.all { it.hopCount == 0 })
                disconnect(d, e); d.close()

                stage = "E-B"
                connect(e, b)
                b.store.receipts.first { it.size == ids.size }
                e.engine.forwarding.first { it.receiptPeerKnownIds.containsAll(receiptIds) }
                assertTrue(b.store.base.rows.values.all { it.status == MessageStatus.DELIVERED && it.hopCount == 1 })
                assertTrue(b.store.receipts.value.all { it.hopCount == 2 })
                disconnect(e, b); e.close()

                stage = "B-A receipts"
                val dropped = MutableStateFlow(0)
                if (dropReceiptAck) b.tcp.onSessionPacket = { peer, session, packet ->
                    if (peer == a.id && packet.type == PacketType.ACK && packet.ackFor == PacketType.DELIVERY_RECEIPT) dropped.value += 1
                    else b.engine.receive(peer, packet, session)
                }
                connect(b, a)
                a.store.receipts.first { it.size == ids.size }
                if (dropReceiptAck) dropped.first { it >= ids.size }
                else b.engine.forwarding.first { it.receiptPeerKnownIds.containsAll(receiptIds) }
                assertTrue(a.store.base.rows.values.all { it.status == MessageStatus.DELIVERED && it.hopCount == 0 })
                assertTrue(a.store.receipts.value.all { it.hopCount == 3 })
                assertEquals(ForwardingKind.DIRECT, b.engine.forwarding.value.lastReceiptSentKind)
                assertFalse(a.log.logs.value.any { it.direction == "TX" && it.detail == "DELIVERY_RECEIPT" }) // No echo.
                messages.forEach { original ->
                    assertEquals(original.copy(status = MessageStatus.DELIVERED), a.store.get(original.messageId))
                    assertTrue(a.store.receiptForMessage(original.messageId)!!.matches(original))
                }
                disconnect(b, a)
                stage = "B-A reconnect"
                b.log.clear(); connect(b, a)
                assertTrue(b.inventory.state.value.remoteReceiptIds.containsAll(receiptIds))
                b.engine.retryPending()
                assertFalse(b.log.logs.value.any { it.direction == "TX" && it.detail in listOf("DELIVERY_RECEIPT", "MESSAGE") })
                assertEquals(ids.size, a.store.receipts.value.size)
                disconnect(b, a); a.close()

                stage = "B-F suppression"
                b.log.clear(); connect(b, f) // Empty F gets delivery knowledge, never old payloads.
                f.store.receipts.first { it.size == ids.size }
                b.engine.forwarding.first { it.receiptPeerKnownIds.containsAll(receiptIds) }
                assertTrue(f.store.base.rows.isEmpty())
                assertFalse(b.log.logs.value.any { it.direction == "TX" && it.detail == "MESSAGE" })
                assertTrue(b.store.receipts.value.all { it.hopCount == 2 })
                assertTrue(f.store.receipts.value.all { it.hopCount == 3 })
            }
        } catch (failure: Throwable) {
            throw AssertionError("Stage $stage\n" + nodes.mapIndexed { index, node ->
                "Node $index ${node.id}: ${node.inventory.state.value}\n${node.engine.forwarding.value}\n" +
                    node.log.logs.value.take(15).joinToString("\n") { it.detail }
            }.joinToString("\n"), failure)
        } finally { nodes.forEach { it.close() } }
    }
}
