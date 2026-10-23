package com.group16.dinoblog

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.network.MessageTransport
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.repository.MessageRepository
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StoreCarryDatabaseTest {
    @get:Rule val migration = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), DinoblogDatabase::class.java)

    @Test fun migrationPreservesOldConversationsAndPendingMessages(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        try {
            migration.createDatabase(name, 1).use { old ->
                for (status in listOf("PENDING", "DELIVERED", "RECEIVED", "EXPIRED")) {
                    old.execSQL("INSERT INTO messages (messageId, senderId, receiverId, payload, timestamp, ttl, status) VALUES (?, 'a', 'b', 'old history', 1000, 60, ?)",
                        arrayOf(status, status))
                }
                old.execSQL("INSERT INTO peers (deviceId, name, lastSeen) VALUES ('b', 'Phone B', 1000)")
            }
            migration.runMigrationsAndValidate(name, 11, true, DinoblogDatabase.MIGRATION_1_2, DinoblogDatabase.MIGRATION_2_3, DinoblogDatabase.MIGRATION_3_4, DinoblogDatabase.MIGRATION_4_5, DinoblogDatabase.MIGRATION_5_6, DinoblogDatabase.MIGRATION_6_7, DinoblogDatabase.MIGRATION_7_8, DinoblogDatabase.MIGRATION_8_9, DinoblogDatabase.MIGRATION_9_10, DinoblogDatabase.MIGRATION_10_11).close()
            val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name)
                .addMigrations(DinoblogDatabase.MIGRATION_1_2, DinoblogDatabase.MIGRATION_2_3, DinoblogDatabase.MIGRATION_3_4, DinoblogDatabase.MIGRATION_4_5, DinoblogDatabase.MIGRATION_5_6, DinoblogDatabase.MIGRATION_6_7, DinoblogDatabase.MIGRATION_7_8, DinoblogDatabase.MIGRATION_8_9, DinoblogDatabase.MIGRATION_9_10, DinoblogDatabase.MIGRATION_10_11).build()
            try {
                for (status in listOf(MessageStatus.PENDING, MessageStatus.DELIVERED, MessageStatus.RECEIVED, MessageStatus.EXPIRED)) {
                    val row = db.messages().get(status.name)!!
                    assertEquals(status, row.status)
                    assertEquals("old history", row.payload)
                    assertEquals("a", row.sourceId)
                    assertEquals("b", row.destinationId)
                    assertEquals(1000L, row.createdAt)
                    assertEquals(61000L, row.expiresAt)
                    assertEquals(8, row.hopLimit)
                    assertEquals(if (status in listOf(MessageStatus.DELIVERED, MessageStatus.RECEIVED)) 1 else 0, row.hopCount)
                    assertNull(row.carrierId)
                }
                assertEquals(1, db.messages().pending("a", "b").size)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun carriedRowSurvivesReopenAndIsNeverSelectedForForwarding(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "carry-${UUID.randomUUID()}.db"
        val message = Message(UUID.randomUUID().toString(), "a", "c", "for C", 1000, 60,
            MessageStatus.CARRIED, hopCount = 1, hopLimit = 8, carrierId = "b")
        try {
            val first = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try { assertTrue(first.messages().insert(message) != -1L) }
            finally { first.close() }
            val reopened = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                val dao = reopened.messages()
                assertEquals(message, dao.get(message.messageId))
                assertEquals(-1L, dao.insert(message))
                assertTrue(dao.pending("b", "c").isEmpty())
                assertTrue(dao.pending("a", "b").isEmpty())
                assertEquals(0, dao.markDelivered(message.messageId, "b", "c"))
                assertEquals(1, dao.expire(61000))
                assertEquals(MessageStatus.EXPIRED, dao.get(message.messageId)!!.status)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun custodyAckGuardsAndRetryPeerSelectionUseActualSqlite(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        try {
            val dao = db.messages()
            val message = Message(UUID.randomUUID().toString(), "a", "c", "via B", 1000, 60,
                MessageStatus.PENDING, carrierId = "b")
            dao.insert(message)
            assertEquals(listOf(message), dao.pending("a", "b"))
            assertTrue(dao.pending("a", "c").isEmpty())
            assertEquals(0, dao.markDelivered(message.messageId, "a", "b"))
            assertEquals(0, dao.markDelivered(message.messageId, "a", "c"))
            assertEquals(0, dao.markForwarded(message.messageId, "a", "c"))
            assertEquals(1, dao.markForwarded(message.messageId, "a", "b"))
            assertEquals(0, dao.markForwarded(message.messageId, "a", "b"))
            assertEquals(MessageStatus.FORWARDED, dao.get(message.messageId)!!.status)
            assertEquals(0, dao.get(message.messageId)!!.hopCount)
            assertTrue(dao.pending("a", "b").isEmpty())
        } finally { db.close() }
    }

    @Test fun receiveIntoRealRoomRejectsBoundariesAndRetainsOneCarriedRowAfterReopen(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "receive-carry-${UUID.randomUUID()}.db"
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val c = UUID.randomUUID().toString()
        val incoming = Message(UUID.randomUUID().toString(), a, c, "Hello C", 1000, 60,
            MessageStatus.PENDING, hopCount = 1)
        try {
            val first = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                val acks = mutableListOf<WirePacket>()
                val transport = object : MessageTransport {
                    override val state = MutableStateFlow(SocketState("Connected", a, "Phone A"))
                    override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
                    override suspend fun send(peerId: String, packet: WirePacket): Boolean {
                        // This callback must observe the durable row before custody ACK.
                        assertEquals(MessageStatus.CARRIED, first.messages().get(packet.messageId!!)!!.status)
                        assertEquals(MessageStatus.CARRIED, packet.ackStatus)
                        acks += packet
                        return true
                    }
                }
                val engine = DeliveryEngine(b, MessageRepository(first), transport, this, NetworkLogger(), smartRelaySelection = false) { 2000 }
                for (invalid in listOf(
                    incoming.copy(messageId = UUID.randomUUID().toString(), hopCount = 0),
                    incoming.copy(messageId = UUID.randomUUID().toString(), createdAt = 1000, ttl = 1, expiresAt = 2000))) {
                    try { engine.receive(a, WirePacket(PacketType.MESSAGE, message = invalid)) }
                    catch (_: IllegalArgumentException) { /* Invalid zero-hop received copy. */ }
                    assertNull(first.messages().get(invalid.messageId))
                }
                assertTrue(acks.isEmpty())
                repeat(2) { engine.receive(a, WirePacket(PacketType.MESSAGE, message = incoming)) }
                assertEquals(2, acks.size)
            } finally { first.close() }
            val reopened = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                assertEquals(incoming.copy(status = MessageStatus.CARRIED), reopened.messages().get(incoming.messageId))
                assertEquals(-1L, reopened.messages().insert(incoming))
                assertTrue(reopened.messages().pending(b, c).isEmpty())
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
