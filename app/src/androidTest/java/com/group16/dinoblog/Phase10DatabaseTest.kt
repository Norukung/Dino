package com.group16.dinoblog

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.repository.MessageRepository
import com.group16.dinoblog.repository.RelayDisabledException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase10DatabaseTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DinoblogDatabase::class.java)
    private fun id() = UUID.randomUUID().toString()
    private fun row(source: String, destination: String, status: MessageStatus = MessageStatus.CARRIED,
        ttl: Long = 60, hopCount: Int = 1) = Message(id(), source, destination, "history", 1000, ttl,
        status, hopCount = hopCount, copiesRemaining = 4)

    @Test fun phaseNineDatabaseUpgradesWithoutLosingMessagesReceiptsOrRouting() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "phase10-${id()}.db"; val source = id(); val destination = id(); val messageId = id()
        try {
            migration.createDatabase(name, 6).use { db ->
                db.execSQL("INSERT INTO messages(messageId,senderId,receiverId,payload,timestamp,ttl,status,expiresAt,hopCount,hopLimit,carrierId,copiesRemaining) VALUES (?,?,?,?,1000,60,'DELIVERED',61000,1,8,NULL,4)",
                    arrayOf(messageId, source, destination, "history"))
                db.execSQL("INSERT INTO delivery_receipts(originalMessageId,sourceId,destinationId,deliveredAt,originalExpiresAt,createdByDeviceId) VALUES (?,?,?,2000,61000,?)",
                    arrayOf(messageId, source, destination, destination))
                db.execSQL("INSERT INTO delivery_predictabilities(destinationDeviceId,predictability,lastUpdatedAt) VALUES (?,0.75,1000)", arrayOf(destination))
            }
            migration.runMigrationsAndValidate(name, 9, true, DinoblogDatabase.MIGRATION_6_7, DinoblogDatabase.MIGRATION_7_8, DinoblogDatabase.MIGRATION_8_9).close()
            val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                assertEquals("history", db.messages().get(messageId)?.payload)
                assertEquals(4, db.messages().get(messageId)?.copiesRemaining)
                assertEquals(0, db.messages().get(messageId)?.reservedCopies)
                assertNotNull(db.receipts().get(messageId))
                assertEquals(0.75, db.predictabilities().get(destination)?.predictability ?: 0.0, 0.0)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun sprayEscrowCommitsOnlyAfterAckAndRejectDoesNotSpendCopies() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        try {
            val source = id(); val destination = id(); val peer = id(); val m = row(source, destination, MessageStatus.PENDING, hopCount = 0)
            val repo = MessageRepository(db); assertTrue(repo.insert(m))
            assertNotNull(repo.reserveSpray(m.messageId, source, peer, 4, 2000))
            assertEquals(4, repo.get(m.messageId)?.copiesRemaining)
            assertEquals(2, repo.get(m.messageId)?.reservedCopies)
            assertNull(repo.reserveSpray(m.messageId, source, id(), 4, 2000))
            assertTrue(repo.restoreSpray(m.messageId, 2, 2))
            assertEquals(4, repo.get(m.messageId)?.copiesRemaining)
            assertEquals(0, repo.get(m.messageId)?.reservedCopies)
            assertNotNull(repo.reserveSpray(m.messageId, source, peer, 4, 2000))
            assertTrue(repo.confirmSpray(m.messageId, peer, 0, 2, 2))
            assertEquals(2, repo.get(m.messageId)?.copiesRemaining)
            assertFalse(repo.confirmSpray(m.messageId, peer, 0, 2, 2))
        } finally { db.close() }
    }

    @Test fun relayOptOutAndCleanupPreserveUserHistoryAndReceipts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        try {
            val local = id(); val source = id(); val destination = id()
            val blocked = MessageRepository(db, relayEnabled = { false })
            val rejected = row(source, destination)
            assertTrue(runCatching { blocked.commitReceived(rejected, null, local, 2000, 8) }.exceptionOrNull() is RelayDisabledException)
            assertNull(blocked.get(rejected.messageId))
            val final = row(source, local, MessageStatus.RECEIVED)
            assertTrue(blocked.commitReceived(final, DeliveryReceipt.create(final, local, 2000), local, 2000, 8))
            val repo = MessageRepository(db)
            val own = row(local, destination, MessageStatus.PENDING, hopCount = 0)
            val deliveredRelay = row(source, destination)
            val expiredRelay = row(source, destination, ttl = 1)
            val hopBlocked = row(source, destination, hopCount = 8)
            listOf(own, deliveredRelay, expiredRelay, hopBlocked).forEach { assertTrue(repo.insert(it)) }
            assertEquals(2, repo.relayBufferUsage(local, 2000))
            val receipt = DeliveryReceipt.create(deliveredRelay, destination, 2000)
            assertTrue(repo.saveReceipt(receipt))
            assertEquals(3, repo.cleanup(local, 2000))
            assertNull(repo.get(deliveredRelay.messageId))
            assertNull(repo.get(expiredRelay.messageId))
            assertNull(repo.get(hopBlocked.messageId))
            assertNotNull(repo.receipt(deliveredRelay.messageId))
            assertEquals("history", repo.get(own.messageId)?.payload)
            assertEquals("history", repo.get(final.messageId)?.payload)
            assertEquals(0, repo.relayBufferUsage(local, 2000))
        } finally { db.close() }
    }
}
