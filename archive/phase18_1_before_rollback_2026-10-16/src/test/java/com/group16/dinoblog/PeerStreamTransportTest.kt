package com.group16.dinoblog

import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
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
import org.junit.Assert.*
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID

class PeerStreamTransportTest {
    private class PipeConnection(override val input: PipedInputStream,
        override val output: PipedOutputStream) : TcpTransport.PeerConnection {
        override val transportType = "BLUETOOTH"
        override val localEndpoint: String? = null
        override val remoteEndpoint: String? = null
        override fun setReadTimeout(milliseconds: Int) = Unit
        override fun close() { input.close(); output.close() }
    }

    @Test fun bluetoothLabeledStreamUsesTheSameHelloAndFramingPath(): Unit = runBlocking {
        val ab = PipedInputStream(65_536)
        val ba = PipedInputStream(65_536)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val aId = UUID.randomUUID().toString()
        val bId = UUID.randomUUID().toString()
        val a = TcpTransport(aId, "A", scope, NetworkLogger())
        val b = TcpTransport(bId, "B", scope, NetworkLogger())
        val received = MutableStateFlow<String?>(null)
        b.onPacket = { peer, packet ->
            if (packet.type == PacketType.MESSAGE) received.value = packet.message?.messageId
        }
        try {
            a.acceptBluetooth(PipeConnection(ba, PipedOutputStream(ab)))
            b.acceptBluetooth(PipeConnection(ab, PipedOutputStream(ba)))
            withTimeout(5_000) {
                a.state.first { it.peerId == bId }
                b.state.first { it.peerId == aId }
                assertEquals("BLUETOOTH", a.state.value.transportType)
                assertEquals("BLUETOOTH", b.state.value.transportType)
                val message = Message(UUID.randomUUID().toString(), aId, bId, "Shared frame",
                    System.currentTimeMillis(), 60, MessageStatus.PENDING)
                assertTrue(a.send(bId, WirePacket(PacketType.MESSAGE, message = message)))
                received.first { it == message.messageId }
            }
        } finally {
            a.stop(); b.stop(); scope.cancel()
        }
    }
}
