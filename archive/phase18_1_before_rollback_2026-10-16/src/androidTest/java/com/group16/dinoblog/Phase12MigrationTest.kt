package com.group16.dinoblog

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.model.OnlineMessageState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase12MigrationTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DinoblogDatabase::class.java)

    @Test fun phaseElevenMessageAndReceiptSurviveOnlineStateMigration(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "phase12-${UUID.randomUUID()}.db"
        val messageId = UUID.randomUUID().toString()
        val source = UUID.randomUUID().toString()
        val destination = UUID.randomUUID().toString()
        try {
            migration.createDatabase(name, 8).use { old ->
                old.execSQL("""INSERT INTO messages(messageId,senderId,receiverId,payload,timestamp,ttl,status,
                    expiresAt,hopCount,hopLimit,carrierId,copiesRemaining,reservedCopies,contentType,mediaId,reservedPeerId)
                    VALUES (?,?,?,?,1000,60,'DELIVERED',61000,0,8,NULL,4,0,'TEXT',NULL,NULL)""",
                    arrayOf(messageId, source, destination, "preserved"))
                old.execSQL("""INSERT INTO delivery_receipts(originalMessageId,sourceId,destinationId,
                    deliveredAt,originalExpiresAt,createdByDeviceId) VALUES (?,?,?,2000,61000,?)""",
                    arrayOf(messageId, source, destination, destination))
            }
            migration.runMigrationsAndValidate(name, 12, true,
                DinoblogDatabase.MIGRATION_8_9, DinoblogDatabase.MIGRATION_9_10,
                DinoblogDatabase.MIGRATION_10_11, DinoblogDatabase.MIGRATION_11_12).close()
            val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                assertEquals("preserved", db.messages().get(messageId)?.payload)
                assertEquals(4, db.messages().get(messageId)?.copiesRemaining)
                assertNotNull(db.receipts().get(messageId))
                db.onlineMessages().save(OnlineMessageState(messageId, "SERVER_ACCEPTED", 3000))
                assertEquals(3000L, db.onlineMessages().get(messageId)?.serverAcceptedAt)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
