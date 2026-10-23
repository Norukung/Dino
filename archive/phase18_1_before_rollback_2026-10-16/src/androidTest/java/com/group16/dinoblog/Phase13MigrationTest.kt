package com.group16.dinoblog

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.model.MessageContentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase13MigrationTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DinoblogDatabase::class.java)

    @Test fun imageMetadataAndHistorySurvive(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "phase13-${UUID.randomUUID()}.db"
        val message = UUID.randomUUID().toString(); val media = UUID.randomUUID().toString()
        val source = UUID.randomUUID().toString(); val destination = UUID.randomUUID().toString()
        try {
            migration.createDatabase(name, 9).use { old ->
                old.execSQL("""INSERT INTO messages(messageId,senderId,receiverId,payload,timestamp,ttl,status,
                    expiresAt,hopCount,hopLimit,carrierId,copiesRemaining,reservedCopies,contentType,mediaId,reservedPeerId)
                    VALUES (?,?,?,?,1000,60,'PENDING',61000,0,8,NULL,4,0,'IMAGE',?,NULL)""",
                    arrayOf(message, source, destination, "", media))
                old.execSQL("""INSERT INTO media(mediaId,messageId,sourceId,destinationId,mimeType,originalFileName,
                    fileSize,sha256,chunkSize,totalChunks,receivedChunks,isComplete,createdAt,expiresAt,messageJson,lastUpdatedAt)
                    VALUES (?,?,?,?,'image/jpeg','image.jpg',3,?,131072,1,'1',1,1000,61000,'{}',1000)""",
                    arrayOf(media, message, source, destination, "a".repeat(64)))
            }
            migration.runMigrationsAndValidate(name, 12, true, DinoblogDatabase.MIGRATION_9_10, DinoblogDatabase.MIGRATION_10_11, DinoblogDatabase.MIGRATION_11_12).close()
            val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                assertEquals(MessageContentType.IMAGE, db.media().get(media)?.mediaType)
                assertEquals(3L, db.media().get(media)?.fileSize)
                assertEquals(media, db.messages().get(message)?.mediaId)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
