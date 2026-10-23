package com.group16.dinoblog

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.model.PeerIdentityBinding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase15MigrationTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DinoblogDatabase::class.java)

    @Test fun existingHistoryAndReceiptsRemainWhileKeyColumnsStartUnsigned(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "phase15-${UUID.randomUUID()}.db"
        val messageId = UUID.randomUUID().toString()
        val source = UUID.randomUUID().toString()
        val destination = UUID.randomUUID().toString()
        try {
            migration.createDatabase(name, 11).use { old ->
                old.execSQL("""INSERT INTO messages(messageId,senderId,receiverId,payload,timestamp,ttl,status,
                    expiresAt,hopCount,hopLimit,carrierId,copiesRemaining,reservedCopies,contentType,mediaId,reservedPeerId)
                    VALUES (?,?,?,?,1000,60,'DELIVERED',61000,1,8,NULL,1,0,'TEXT',NULL,NULL)""",
                    arrayOf(messageId, source, destination, "Old chat"))
                old.execSQL("""INSERT INTO delivery_receipts(originalMessageId,sourceId,destinationId,
                    deliveredAt,originalExpiresAt,createdByDeviceId) VALUES (?,?,?,2000,61000,?)""",
                    arrayOf(messageId, source, destination, destination))
            }
            migration.runMigrationsAndValidate(name, 12, true, DinoblogDatabase.MIGRATION_11_12).close()
            val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                assertEquals("Old chat", db.messages().get(messageId)?.payload)
                assertNull(db.messages().get(messageId)?.originSignature)
                assertNotNull(db.receipts().get(messageId))
                assertNull(db.receipts().get(messageId)?.destinationSignature)
                db.identities().save(PeerIdentityBinding(source, "test-key", "test-fingerprint",
                    "TOFU_TRUSTED", 1000, 2000, "TOFU"))
                assertEquals("TOFU_TRUSTED", db.identities().get(source)?.trustState)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
