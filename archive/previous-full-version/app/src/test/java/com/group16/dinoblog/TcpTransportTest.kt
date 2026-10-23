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
import org.junit.Test
import java.util.UUID

class TcpTransportTest {
    @Test fun `real loopback sockets exchange identity messages ACK and reconnect`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val owner = TcpTransport(a, "A", scope, NetworkLogger())
        val client = TcpTransport(b, "B", scope, NetworkLogger())
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
                val message = Message(UUID.randomUUID().toString(), b, a, "ไทย\nTCP", System.currentTimeMillis(), 60, MessageStatus.PENDING)
                assertTrue(client.send(a, WirePacket(PacketType.MESSAGE, message = message)))
                ack.first { it == message.messageId }
                assertEquals(message, received.value!!.message)
                client.stop()
                owner.state.first { it.peerId == null }
                client.start(false, "127.0.0.1")
                owner.state.first { it.peerId == b }
                client.state.first { it.peerId == a }
            }
        } finally {
            client.stop()
            owner.stop()
            scope.cancel()
        }
    }
}
