package com.group16.dinoblog

import com.group16.dinoblog.dtn.InventoryManager
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.TcpTransport
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.repository.InventoryStore
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class InventoryTcpTest {
    @Test fun `two real TCP peers compare duplicate inventories clear sessions and exchange after reconnect`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val b = UUID.randomUUID().toString()
        val c = UUID.randomUUID().toString()
        val m1 = UUID.randomUUID().toString()
        val m2 = UUID.randomUUID().toString()
        val bLog = NetworkLogger()
        val cLog = NetworkLogger()
        val owner = TcpTransport(b, "B", scope, bLog)
        val client = TcpTransport(c, "C", scope, cLog)
        val bInventory = InventoryManager(b, InventoryStore { listOf(m1, m2) }, owner, scope, bLog)
        val cInventory = InventoryManager(c, InventoryStore { listOf(m1) }, client, scope, cLog)
        owner.onInventory = bInventory::receive
        client.onInventory = cInventory::receive
        bInventory.start()
        cInventory.start()
        try {
            owner.start(true, "127.0.0.1")
            withTimeout(5000) { owner.state.first { it.status.startsWith("Listening") } }
            client.start(false, "127.0.0.1")
            withTimeout(15000) {
                val first = bInventory.state.first { it.remoteReceived && it.sent }
                assertEquals(listOf(m1), first.remoteMessageIds)
                assertEquals(listOf(m2), first.missingOnPeer)
                val other = cInventory.state.first { it.remoteReceived && it.sent }
                assertTrue(other.missingOnPeer.isEmpty())
                assertEquals(listOf(m2), other.missingLocally)

                assertTrue(client.send(b, WirePacket(PacketType.INVENTORY, deviceId = c, messageIds = listOf(m1, m1))))
                // TCP reads in order: PONG receipt proves the preceding inventory handler completed.
                assertTrue(client.send(b, WirePacket(PacketType.PING)))
                cLog.logs.first { logs -> logs.any { it.direction == "RX" && it.detail == "PONG" } }
                assertEquals(first.remoteMessageIds, bInventory.state.value.remoteMessageIds)
                assertEquals(first.missingOnPeer, bInventory.state.value.missingOnPeer)

                client.stop()
                for (manager in listOf(bInventory, cInventory)) {
                    val cleared = manager.state.first { it.peerId == null }
                    assertTrue(cleared.remoteMessageIds.isEmpty())
                    assertTrue(cleared.missingOnPeer.isEmpty())
                    assertTrue(cleared.missingLocally.isEmpty())
                }
                client.start(false, "127.0.0.1")
                val reconnected = bInventory.state.first { it.remoteReceived && it.sent && it.sessionId != first.sessionId }
                cInventory.state.first { it.remoteReceived && it.sent && it.sessionId != other.sessionId }
                assertEquals(listOf(m2), reconnected.missingOnPeer)
                assertFalse(owner.sendToSession(c, first.sessionId!!,
                    WirePacket(PacketType.INVENTORY, deviceId = b, messageIds = emptyList())))
                for (logger in listOf(bLog, cLog)) {
                    assertFalse(logger.logs.value.any { it.detail == "MESSAGE" || it.detail == "ACK" })
                }
            }
        } finally {
            client.stop()
            owner.stop()
            scope.cancel()
        }
    }
}
