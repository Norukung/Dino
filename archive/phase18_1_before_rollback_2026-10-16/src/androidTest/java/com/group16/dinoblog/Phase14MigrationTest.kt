package com.group16.dinoblog

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.model.ExperimentSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase14MigrationTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DinoblogDatabase::class.java)

    @Test fun phase13HistorySurvivesAndExperimentTablesWork(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "phase14-${UUID.randomUUID()}.db"
        val messageId = UUID.randomUUID().toString()
        try {
            migration.createDatabase(name, 10).use { old ->
                old.execSQL("""INSERT INTO messages(messageId,senderId,receiverId,payload,timestamp,ttl,status,
                    expiresAt,hopCount,hopLimit,carrierId,copiesRemaining,reservedCopies,contentType,mediaId,reservedPeerId)
                    VALUES (?,?,?,?,1000,60,'PENDING',61000,0,8,NULL,4,0,'TEXT',NULL,NULL)""",
                    arrayOf(messageId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Old chat"))
            }
            migration.runMigrationsAndValidate(name, 11, true, DinoblogDatabase.MIGRATION_10_11).close()
            migration.runMigrationsAndValidate(name, 12, true, DinoblogDatabase.MIGRATION_11_12).close()
            val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                assertEquals("Old chat", db.messages().get(messageId)?.payload)
                val experimentId = UUID.randomUUID().toString()
                db.experiments().insertSession(ExperimentSession(experimentId, "migration", 1000L,
                    routingMode = "SMART_SPRAY", deviceHash = "test", appVersion = "test", buildType = "DEBUG"))
                assertNotNull(db.experiments().session(experimentId))
                assertEquals("Old chat", db.messages().get(messageId)?.payload)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
