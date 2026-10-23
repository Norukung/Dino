package com.group16.dinoblog

import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.dtn.InventoryManager
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.TcpTransport
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.repository.MessageStore
import com.group16.dinoblog.repository.InventoryStore
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

class PeerIdentityHandshakeTest {
    private fun id() = UUID.randomUUID().toString()
    private fun connectRaw() = Socket("127.0.0.1", WireProtocol.PORT).apply { soTimeout = 3000 }
    private fun hello(socket: Socket, id: String) =
        WireProtocol.write(DataOutputStream(socket.getOutputStream()), WirePacket(PacketType.HELLO, deviceId = id))

    @Test fun `debug labels distinguish disconnected awaiting and identified states`() {
        assertEquals("None", SocketState().peerIdentityLabel)
        val waiting = SocketState(status = "TCP connected", remoteIp = "127.0.0.1", awaitingHello = true)
        assertEquals("Waiting for HELLO...", waiting.peerIdentityLabel)
        assertNull(waiting.connectedPeerDeviceId)
        assertFalse(waiting.connected)
        val remote = id()
        val identified = SocketState("Connected", remote, remoteIp = "127.0.0.1")
        assertEquals(remote, identified.peerIdentityLabel)
        assertEquals(remote, identified.connectedPeerDeviceId)
        assertTrue(identified.connected)
    }

    @Test fun `TCP sends HELLO before user action waits for identity and clears it on remote EOF`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val localId = id()
        val remoteId = id()
        val logs = NetworkLogger()
        val transport = TcpTransport(localId, "B", scope, logs)
        try {
            transport.start(true, "127.0.0.1")
            withTimeout(5000) { transport.state.first { it.status.startsWith("Listening") } }
            connectRaw().use { socket ->
                val sentHello = WireProtocol.read(DataInputStream(socket.getInputStream()))
                assertEquals(PacketType.HELLO, sentHello.type)
                assertEquals(localId, sentHello.deviceId)
                assertEquals("Waiting for HELLO...", transport.state.value.peerIdentityLabel)
                assertEquals("127.0.0.1", transport.state.value.remoteIp)
                assertFalse(transport.send(remoteId, WirePacket(PacketType.PING)))
                hello(socket, remoteId)
                withTimeout(5000) { transport.state.first { it.connectedPeerDeviceId == remoteId } }
                assertFalse(transport.state.value.awaitingHello)
            }
            withTimeout(5000) { transport.state.first { it.connectedPeerDeviceId == null } }
            assertEquals("None", transport.state.value.peerIdentityLabel)
            withTimeout(5000) { logs.logs.first { rows -> rows.any { it.detail == "DTN PEER DISCONNECTED\npreviousPeer=$remoteId" } } }
        } finally { transport.stop(); scope.cancel() }
    }

    @Test fun `reconnect uses the new UUID even on the same IP and stop during HELLO leaves no identity`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = TcpTransport(id(), "B", scope, NetworkLogger())
        try {
            transport.start(true, "127.0.0.1")
            withTimeout(5000) { transport.state.first { it.status.startsWith("Listening") } }
            connectRaw().use { abandoned ->
                WireProtocol.read(DataInputStream(abandoned.getInputStream()))
                assertTrue(transport.state.value.awaitingHello)
                transport.stop()
                assertEquals("None", transport.state.value.peerIdentityLabel)
                assertEquals(-1, abandoned.getInputStream().read())
            }
            transport.start(true, "127.0.0.1")
            withTimeout(5000) { transport.state.first { it.status.startsWith("Listening") } }
            val firstId = id()
            val secondId = id()
            for (remoteId in listOf(firstId, secondId)) {
                connectRaw().use { socket ->
                    WireProtocol.read(DataInputStream(socket.getInputStream()))
                    assertNull(transport.state.value.connectedPeerDeviceId)
                    hello(socket, remoteId)
                    withTimeout(5000) { transport.state.first { it.connectedPeerDeviceId == remoteId } }
                    assertEquals("127.0.0.1", transport.state.value.remoteIp)
                    assertEquals(remoteId, transport.state.value.peerId)
                }
                withTimeout(5000) { transport.state.first { it.connectedPeerDeviceId == null } }
            }
        } finally { transport.stop(); scope.cancel() }
    }

    @Test fun `non HELLO self identity malformed identity and repeated HELLO never leave stale peer state`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val localId = id()
        val transport = TcpTransport(localId, "B", scope, NetworkLogger())
        try {
            transport.start(true, "127.0.0.1")
            withTimeout(5000) { transport.state.first { it.status.startsWith("Listening") } }
            for (json in listOf(
                """{"packetType":"ACK","messageId":"${id()}"}""",
                """{"packetType":"HELLO","deviceId":"$localId"}""",
                """{"packetType":"HELLO","deviceId":"127.0.0.1"}""")) {
                connectRaw().use { socket ->
                    WireProtocol.read(DataInputStream(socket.getInputStream()))
                    val bytes = json.toByteArray(Charsets.UTF_8)
                    DataOutputStream(socket.getOutputStream()).apply { writeInt(bytes.size); write(bytes); flush() }
                    assertEquals(-1, socket.getInputStream().read())
                }
                assertNull(transport.state.value.connectedPeerDeviceId)
                assertFalse(transport.state.value.awaitingHello)
            }
            connectRaw().use { socket ->
                WireProtocol.read(DataInputStream(socket.getInputStream()))
                val remoteId = id()
                hello(socket, remoteId)
                withTimeout(5000) { transport.state.first { it.connectedPeerDeviceId == remoteId } }
                hello(socket, id()) // An established session cannot silently change identity.
                assertEquals(-1, socket.getInputStream().read())
                assertNull(transport.state.value.connectedPeerDeviceId)
            }
        } finally { transport.stop(); scope.cancel() }
    }

    @Test fun `silent TCP peer times out without keeping waiting or old identity state`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = TcpTransport(id(), "B", scope, NetworkLogger())
        try {
            transport.start(true, "127.0.0.1")
            withTimeout(5000) { transport.state.first { it.status.startsWith("Listening") } }
            connectRaw().use { socket ->
                WireProtocol.read(DataInputStream(socket.getInputStream()))
                assertTrue(transport.state.value.awaitingHello)
                socket.soTimeout = 15000
                assertEquals(-1, socket.getInputStream().read()) // Production HELLO timeout is 10s.
                assertEquals("None", transport.state.value.peerIdentityLabel)
            }
        } finally { transport.stop(); scope.cancel() }
    }

    @Test fun `inventory manager alone never sends carried message without delivery coordination`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val b = id()
        val c = id()
        val bundle = Message(id(), id(), c, "Stored for C", System.currentTimeMillis(), 86400,
            MessageStatus.CARRIED, hopCount = 1, carrierId = b)
        val store = object : MessageStore {
            override val messages = MutableStateFlow(listOf(bundle))
            override val peers = MutableStateFlow<List<Peer>>(emptyList())
            override suspend fun get(id: String) = messages.value.find { it.messageId == id }
            override suspend fun insert(message: Message): Boolean = error("No new message expected")
            override suspend fun pending(localId: String, peerId: String) = messages.value.filter {
                it.sourceId == localId && (it.carrierId ?: it.destinationId) == peerId && it.status == MessageStatus.PENDING
            }
            override suspend fun delivered(id: String, localId: String, peerId: String) = false
            override suspend fun forwarded(id: String, localId: String, peerId: String) = false
            override suspend fun relayDelivered(id: String, localId: String, peerId: String, previousHop: Int) = false
            override suspend fun replicated(id: String, peerId: String, previousHop: Int) = false
            override suspend fun expire(now: Long) { }
            override suspend fun savePeer(peer: Peer) { peers.value = listOf(peer) }
        }
        val transport = TcpTransport(b, "B", scope, NetworkLogger())
        val engine = DeliveryEngine(b, store, transport, scope, NetworkLogger())
        val inventory = InventoryManager(b, InventoryStore { listOf(bundle.messageId) }, transport, scope, NetworkLogger())
        transport.onInventory = inventory::receive
        try {
            engine.start()
            inventory.start()
            transport.start(true, "127.0.0.1")
            withTimeout(5000) { transport.state.first { it.status.startsWith("Listening") } }
            connectRaw().use { socket ->
                WireProtocol.read(DataInputStream(socket.getInputStream()))
                hello(socket, c)
                withTimeout(5000) {
                    transport.state.first { it.connectedPeerDeviceId == c }
                    store.peers.first { peers -> peers.any { it.deviceId == c } }
                }
                val advertised = WireProtocol.read(DataInputStream(socket.getInputStream()))
                assertEquals(PacketType.INVENTORY, advertised.type)
                assertEquals(listOf(bundle.messageId), advertised.messageIds)
                WireProtocol.write(DataOutputStream(socket.getOutputStream()),
                    WirePacket(PacketType.INVENTORY, deviceId = c, messageIds = emptyList()))
                withTimeout(5000) { inventory.state.first { it.remoteReceived } }
                assertEquals(listOf(bundle.messageId), inventory.state.value.missingOnPeer)
                engine.retryPending()
                socket.soTimeout = 500
                assertThrows(SocketTimeoutException::class.java) { socket.getInputStream().read() }
                assertEquals(bundle, store.messages.value.single())
            }
        } finally { transport.stop(); scope.cancel() }
    }
}
