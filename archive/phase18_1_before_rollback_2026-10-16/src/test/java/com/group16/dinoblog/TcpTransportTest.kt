package com.group16.dinoblog

import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.TcpTransport
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class TcpTransportTest {
    @Test fun `real loopback sockets exchange identity messages ACK and reconnect`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val ownerLogs = NetworkLogger()
        val clientLogs = NetworkLogger()
        val owner = TcpTransport(a, "A", scope, ownerLogs)
        val client = TcpTransport(b, "B", scope, clientLogs)
        val received = MutableStateFlow<WirePacket?>(null)
        val ack = MutableStateFlow<String?>(null)
        owner.onPacket = { peer, packet ->
            received.value = packet
            owner.send(peer, WirePacket(PacketType.ACK, messageId = packet.message!!.messageId))
        }
        client.onPacket = { _, packet -> ack.value = packet.messageId }
        try {
            owner.start(true, "127.0.0.1")
            client.start(false, "127.0.0.1")
            withTimeout(15_000) {
                owner.state.first { it.peerId == b }
                client.state.first { it.peerId == a }
                assertEquals(b, owner.state.value.connectedPeerDeviceId)
                assertEquals(a, client.state.value.connectedPeerDeviceId)
                for ((logs, localId, remoteId) in listOf(Triple(ownerLogs, a, b), Triple(clientLogs, b, a))) {
                    assertTrue(logs.logs.value.any { it.detail == "DTN HELLO SEND\nlocalDeviceId=$localId" })
                    assertTrue(logs.logs.value.any { it.detail == "DTN HELLO RECEIVE\nremoteDeviceId=$remoteId" })
                    assertTrue(logs.logs.value.any { it.detail == "DTN PEER IDENTIFIED\nconnectedPeerDeviceId=$remoteId" })
                }
                val message = Message(UUID.randomUUID().toString(), b, a, "ไทย\nTCP", System.currentTimeMillis(), 60, MessageStatus.PENDING)
                assertTrue(client.send(a, WirePacket(PacketType.MESSAGE, message = message)))
                ack.first { it == message.messageId }
                assertEquals(message, received.value!!.message)
                client.stop()
                assertNull(client.state.value.connectedPeerDeviceId)
                owner.state.first { it.peerId == null }
                assertEquals("None", owner.state.value.peerIdentityLabel)
                assertTrue(clientLogs.logs.value.any { it.detail == "DTN PEER DISCONNECTED\npreviousPeer=$a" })
                // The StateFlow may wake before the log append on the owner thread.
                ownerLogs.logs.first { logs -> logs.any { it.detail == "DTN PEER DISCONNECTED\npreviousPeer=$b" } }
                client.start(false, "127.0.0.1")
                owner.state.first { it.peerId == b }
                client.state.first { it.peerId == a }
            }
        } finally {
            client.stop()
            owner.stop()
            assertNull(owner.state.value.connectedPeerDeviceId)
            assertNull(client.state.value.connectedPeerDeviceId)
            scope.cancel()
        }
    }
}
