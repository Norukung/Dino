package com.group16.dinoblog

import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.model.ForwardingKind
import com.group16.dinoblog.model.InventoryState
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.RoutingExperimentMode
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.network.MessageTransport
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class Phase14RoutingModeTest {
    private class Wire(peer: String) : MessageTransport {
        override val state = MutableStateFlow(SocketState("Connected", peer, sessionId = "session"))
        override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
        val sent = mutableListOf<WirePacket>()
        override suspend fun send(peerId: String, packet: WirePacket): Boolean {
            sent += packet
            return true
        }
    }

    @Test fun `epidemic forwards one-copy relay while spray waits without mutating budget`() = runTest {
        val source = UUID.randomUUID().toString()
        val local = UUID.randomUUID().toString()
        val peer = UUID.randomUUID().toString()
        val destination = UUID.randomUUID().toString()
        val message = Message(UUID.randomUUID().toString(), source, destination, "Test", 1_700_000_000_000L,
            60, MessageStatus.CARRIED, hopCount = 1, hopLimit = 4, carrierId = local, copiesRemaining = 1)
        val inventory = MutableStateFlow(InventoryState(peerId = peer, sessionId = "session", localReady = true,
            sent = true, remoteReceived = true, localMessageIds = listOf(message.messageId),
            missingOnPeer = listOf(message.messageId)))
        val epidemicStore = Phase4TestStore().apply { insert(message) }
        val epidemicWire = Wire(peer)
        val epidemic = DeliveryEngine(local, epidemicStore, epidemicWire, backgroundScope, NetworkLogger(), inventory,
            routingMode = { RoutingExperimentMode.EPIDEMIC }, now = { message.createdAt + 1_000 })
        epidemic.retryPending()
        assertEquals(1, epidemicWire.sent.size)
        assertEquals(ForwardingKind.EPIDEMIC, epidemic.forwarding.value.lastSentKind)
        assertEquals(1, epidemicWire.sent.single().message!!.copiesRemaining)
        epidemic.receive(peer, WirePacket(PacketType.ACK, messageId = message.messageId,
            ackStatus = MessageStatus.CARRIED), "session")
        assertEquals(1, epidemicStore.rows[message.messageId]!!.copiesRemaining)

        val sprayStore = Phase4TestStore().apply { insert(message) }
        val sprayWire = Wire(peer)
        val spray = DeliveryEngine(local, sprayStore, sprayWire, backgroundScope, NetworkLogger(), inventory,
            routingMode = { RoutingExperimentMode.SPRAY_AND_WAIT }, now = { message.createdAt + 1_000 })
        spray.retryPending()
        assertTrue(sprayWire.sent.isEmpty())
        assertEquals(1, sprayStore.rows[message.messageId]!!.copiesRemaining)
    }
}
