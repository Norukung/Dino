package com.group16.dinoblog

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.dtn.InventoryManager
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.network.MessageTransport
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.repository.MessageRepository
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
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InventoryDatabaseTest {
    private fun id() = UUID.randomUUID().toString()
    private fun message() = Message(id(), id(), id(), "payload", 1000, 60, MessageStatus.CARRIED, hopCount = 1)

    @Test fun inventoryFiltersLifecycleExpiryHopsAndInvalidRowsWithoutMutatingThem(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        try {
            val base = message()
            val eligible = listOf(MessageStatus.PENDING, MessageStatus.CARRIED, MessageStatus.FORWARDED)
                .map { base.copy(messageId = id(), status = it) }
            val invalid = listOf(
                base.copy(status = MessageStatus.DELIVERED),
                base.copy(status = MessageStatus.RECEIVED),
                base.copy(status = MessageStatus.EXPIRED),
                base.copy(createdAt = 1, ttl = 1, expiresAt = 1001),
                base.copy(createdAt = 1000, ttl = 1, expiresAt = 2000), // Exact expiry.
                base.copy(hopCount = 9, hopLimit = 8),
                base.copy(hopCount = 8, hopLimit = 8),
                base.copy(hopCount = -1), base.copy(hopLimit = 256),
                base.copy(ttl = 0), base.copy(expiresAt = 999999),
                base.copy(createdAt = 302001, expiresAt = 362001),
                base.copy(payload = ""), base.copy(payload = " \t\r\n"),
                base.copy(payload = "x".repeat(4097)),
                base.copy(sourceId = "invalid"), base.copy(destinationId = "invalid"),
                base.copy(destinationId = base.sourceId),
                base.copy(carrierId = "invalid"), base.copy(carrierId = base.sourceId),
                base.copy(carrierId = base.destinationId),
            ).map { it.copy(messageId = id()) } + base.copy(messageId = "invalid")
            for (row in eligible + invalid) db.messages().insert(row)
            val repository = MessageRepository(db)
            assertEquals(eligible.map { it.messageId }.sorted(), repository.forwardableMessageIds(2000))
            for (row in eligible + invalid) assertEquals(row, db.messages().get(row.messageId))
            val atExpiry = repository.forwardableMessageIds(61000)
            assertFalse(eligible.any { it.messageId in atExpiry })
        } finally { db.close() }
    }

    @Test fun comparisonAndDisconnectPreserveRoomRowsAcrossReopen(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "inventory-${id()}.db"
        val rows = listOf(message(), message())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                rows.forEach { db.messages().insert(it) }
                val peer = id()
                val wire = object : MessageTransport {
                    override val state = MutableStateFlow(SocketState("Connected", peer, sessionId = "one"))
                    override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
                    override suspend fun send(peerId: String, packet: WirePacket): Boolean {
                        check(packet.type == PacketType.INVENTORY)
                        return true
                    }
                }
                val manager = InventoryManager(id(), MessageRepository(db), wire, scope, NetworkLogger()) { 2000 }
                manager.start()
                withTimeout(5000) { manager.state.first { it.sent } }
                repeat(2) {
                    manager.receive(peer, "one", WirePacket(PacketType.INVENTORY, deviceId = peer,
                        messageIds = listOf(rows.first().messageId)))
                    assertEquals(listOf(rows.last().messageId), manager.state.value.missingOnPeer)
                }
                wire.state.value = SocketState()
                val cleared = withTimeout(5000) { manager.state.first { it.peerId == null } }
                assertTrue(cleared.remoteMessageIds.isEmpty())
                assertTrue(cleared.missingOnPeer.isEmpty())
                for (row in rows) assertEquals(row, db.messages().get(row.messageId))
            } finally { scope.cancel(); db.close() }
            val reopened = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try { for (row in rows) assertEquals(row, reopened.messages().get(row.messageId)) }
            finally { reopened.close() }
        } finally { scope.cancel(); context.deleteDatabase(name) }
    }
}
