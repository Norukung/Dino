package com.group16.dinoblog

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase9MigrationTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DinoblogDatabase::class.java)

    @Test fun phase8DataSurvivesAndAliasIsLocal(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "phase9-${UUID.randomUUID()}.db"
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val message = UUID.randomUUID().toString()
        try {
            migration.createDatabase(name, 5).use { db ->
                db.execSQL("INSERT INTO peers(deviceId,name,lastSeen) VALUES (?, 'SM-A245F', 1000)", arrayOf(b))
                db.execSQL("INSERT INTO messages(messageId,senderId,receiverId,payload,timestamp,ttl,status,expiresAt,hopCount,hopLimit,carrierId,copiesRemaining) VALUES (?,?,?,?,1000,60,'PENDING',61000,0,8,NULL,4)", arrayOf(message, a, b, "history"))
                db.execSQL("INSERT INTO delivery_receipts(originalMessageId,sourceId,destinationId,deliveredAt,originalExpiresAt,createdByDeviceId) VALUES (?,?,?,2000,61000,?)", arrayOf(message, a, b, b))
                db.execSQL("INSERT INTO delivery_predictabilities(destinationDeviceId,predictability,lastUpdatedAt) VALUES (?,0.75,1000)", arrayOf(b))
            }
            migration.runMigrationsAndValidate(name, 12, true, DinoblogDatabase.MIGRATION_5_6, DinoblogDatabase.MIGRATION_6_7, DinoblogDatabase.MIGRATION_7_8, DinoblogDatabase.MIGRATION_8_9, DinoblogDatabase.MIGRATION_9_10, DinoblogDatabase.MIGRATION_10_11, DinoblogDatabase.MIGRATION_11_12).close()
            val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                assertEquals("history", db.messages().get(message)?.payload)
                assertEquals(4, db.messages().get(message)?.copiesRemaining)
                assertNotNull(db.receipts().get(message))
                assertEquals("DinoBlog User", db.peers().get(b)?.displayName)
                db.peers().setAlias(b, "P'William")
                db.peers().saveRemoteName(b, "William", 2000)
                assertEquals("P'William", db.peers().get(b)?.displayName)
                db.peers().saveRemoteName(b, "Will", 3000)
                assertEquals("P'William", db.peers().get(b)?.displayName)
                db.peers().setAlias(b, null)
                assertEquals("Will", db.peers().get(b)?.displayName)
                val other = UUID.randomUUID().toString()
                db.peers().saveRemoteName(other, "Will", 3000)
                assertEquals("Will", db.peers().get(other)?.displayName)
                assertNotEquals(db.peers().get(b)?.deviceId, db.peers().get(other)?.deviceId)
                db.openHelper.readableDatabase.query("SELECT predictability FROM delivery_predictabilities WHERE destinationDeviceId = '$b'").use {
                    assertTrue(it.moveToFirst()); assertEquals(0.75, it.getDouble(0), 0.001)
                }
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
