package com.group16.dinoblog

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.model.RelayPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase18MigrationTest {
    @get:Rule val migration = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), DinoblogDatabase::class.java)

    @Test fun oldMessageIsConservativelyMarkedAttemptedAndHistorySurvives(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "phase18-${UUID.randomUUID()}.db"
        val id = UUID.randomUUID().toString()
        try {
            migration.createDatabase(name, 12).use { old ->
                old.execSQL("""INSERT INTO messages(messageId,senderId,receiverId,payload,timestamp,ttl,status,
                    expiresAt,hopCount,hopLimit,carrierId,copiesRemaining,reservedCopies,contentType,mediaId,reservedPeerId)
                    VALUES (?,?,?,?,1000,60,'PENDING',61000,0,8,NULL,4,0,'TEXT',NULL,NULL)""",
                    arrayOf(id, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Old message"))
            }
            migration.runMigrationsAndValidate(name, 14, true,
                DinoblogDatabase.MIGRATION_12_13, DinoblogDatabase.MIGRATION_13_14).close()
            val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name)
                .addMigrations(DinoblogDatabase.MIGRATION_12_13, DinoblogDatabase.MIGRATION_13_14).build()
            try {
                assertEquals("Old message", db.messages().get(id)?.payload)
                assertEquals(true, db.messages().get(id)?.deliveryAttempted)
                assertEquals(RelayPolicy.ALLOW_RELAY, db.messages().get(id)?.relayPolicy)
                assertNotNull(db.recalls().observeStates())
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
