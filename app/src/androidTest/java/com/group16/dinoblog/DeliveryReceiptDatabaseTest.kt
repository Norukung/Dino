package com.group16.dinoblog

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.model.*
import com.group16.dinoblog.repository.MessageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeliveryReceiptDatabaseTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DinoblogDatabase::class.java)
    private fun id() = UUID.randomUUID().toString()
    @Test fun receiptPersistsAfterReopenRetainsHistoryAndPreventsResurrection(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "receipts-${id()}.db"
        val m = Message(id(), id(), id(), "history", 1000, 60, MessageStatus.CARRIED, hopCount = 1)
        val r = DeliveryReceipt.create(m, m.destinationId, 2000)
        try {
            val db = Room.databaseBuilder(ctx, DinoblogDatabase::class.java, name).build()
            try {
                val repo = MessageRepository(db)
                assertTrue(repo.insert(m)); assertTrue(repo.saveReceipt(r))
                assertFalse(repo.saveReceipt(r.copy(deliveredAt = 3000)))
                assertEquals(r, repo.receipt(m.messageId))
                assertEquals(MessageStatus.DELIVERED, repo.get(m.messageId)!!.status)
                assertEquals("history", repo.get(m.messageId)!!.payload)
                assertEquals(1, repo.get(m.messageId)!!.hopCount)
            } finally { db.close() }
            val reopened = Room.databaseBuilder(ctx, DinoblogDatabase::class.java, name).build()
            try {
                val repo = MessageRepository(reopened)
                assertEquals(listOf(m.messageId), repo.receiptIds())
                assertTrue(repo.forwardableMessageIds(3000).isEmpty())
                repo.expire(61000)
                assertEquals(r, repo.receipt(m.messageId)) // No premature or automatic GC.
                assertFalse(repo.commitReceived(m, null, id()))
                val absent = m.copy(messageId = id())
                val tombstone = DeliveryReceipt.create(absent, absent.destinationId, 2000)
                repo.saveReceipt(tombstone)
                assertFalse(repo.insert(absent))
                assertFalse(repo.commitReceived(absent, null, id()))
                assertNull(repo.get(absent.messageId))
                val final = m.copy(messageId = id(), status = MessageStatus.RECEIVED)
                assertTrue(repo.commitReceived(final, DeliveryReceipt.create(final, final.destinationId, 2000), final.destinationId))
                assertEquals(MessageStatus.RECEIVED, repo.get(final.messageId)!!.status)
            } finally { reopened.close() }
        } finally { ctx.deleteDatabase(name) }
    }
    @Test fun additiveMigrationFromPhase5PreservesMessagesAndPeers(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "receipt-migration-${id()}.db"
        try {
            migration.createDatabase(name, 2).use {
                it.execSQL("INSERT INTO messages (messageId,senderId,receiverId,payload,timestamp,ttl,status,expiresAt,hopCount,hopLimit) VALUES ('old','a','d','keep',1000,60,'CARRIED',61000,1,8)")
                it.execSQL("INSERT INTO peers (deviceId,name,lastSeen) VALUES ('b','Phone B',1000)")
            }
            migration.runMigrationsAndValidate(name, 8, true, DinoblogDatabase.MIGRATION_2_3, DinoblogDatabase.MIGRATION_3_4, DinoblogDatabase.MIGRATION_4_5, DinoblogDatabase.MIGRATION_5_6, DinoblogDatabase.MIGRATION_6_7, DinoblogDatabase.MIGRATION_7_8).close()
            val db = Room.databaseBuilder(ctx, DinoblogDatabase::class.java, name).build()
            try {
                assertEquals("keep", db.messages().get("old")!!.payload)
                assertTrue(db.receipts().ids().isEmpty())
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM peers").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            } finally { db.close() }
        } finally { ctx.deleteDatabase(name) }
    }
}
