package com.group16.dinoblog

import androidx.room.Room
import android.graphics.Bitmap
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.dtn.DtnConfig
import com.group16.dinoblog.model.MediaManifest
import com.group16.dinoblog.model.MediaEntity
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.RelayPolicy
import com.group16.dinoblog.network.MessageRejectReason
import com.group16.dinoblog.repository.MediaChunkResult
import com.group16.dinoblog.repository.MediaFileStore
import com.group16.dinoblog.repository.MediaOffer
import com.group16.dinoblog.repository.MediaRepository
import com.group16.dinoblog.repository.MessageRepository
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase11MediaTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DinoblogDatabase::class.java)
    private fun id() = UUID.randomUUID().toString()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun phaseTenDatabaseUpgradesWithoutLosingPayloadReceiptOrPredictability() = runBlocking {
        val name = "phase11-${id()}.db"; val a = id(); val b = id(); val message = id()
        try {
            migration.createDatabase(name, 7).use { db ->
                db.execSQL("INSERT INTO messages(messageId,senderId,receiverId,payload,timestamp,ttl,status,expiresAt,hopCount,hopLimit,carrierId,copiesRemaining,reservedCopies) VALUES (?,?,?,?,1000,60,'DELIVERED',61000,1,8,NULL,4,0)",
                    arrayOf(message, a, b, "kept text"))
                db.execSQL("INSERT INTO delivery_receipts(originalMessageId,sourceId,destinationId,deliveredAt,originalExpiresAt,createdByDeviceId) VALUES (?,?,?,2000,61000,?)", arrayOf(message, a, b, b))
                db.execSQL("INSERT INTO delivery_predictabilities(destinationDeviceId,predictability,lastUpdatedAt) VALUES (?,0.75,1000)", arrayOf(b))
            }
            migration.runMigrationsAndValidate(name, 11, true, DinoblogDatabase.MIGRATION_7_8, DinoblogDatabase.MIGRATION_8_9, DinoblogDatabase.MIGRATION_9_10, DinoblogDatabase.MIGRATION_10_11).close()
            val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            try {
                assertEquals("kept text", db.messages().get(message)?.payload)
                assertEquals(MessageContentType.TEXT, db.messages().get(message)?.contentType)
                assertNotNull(db.receipts().get(message))
                assertEquals(0.75, db.predictabilities().get(b)?.predictability ?: 0.0, 0.0)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun partialSurvivesRepositoryRestartAndCompletesOnlyAfterHashVerification() = runBlocking {
        val name = "phase11-resume-${id()}.db"
        var db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
        val files = MediaFileStore(context); val local = id(); val sender = id(); val mediaId = id()
        val data = ByteArrayOutputStream().use { output ->
            val random = java.util.Random(42)
            val pixels = IntArray(700 * 700) { 0xff000000.toInt() or (random.nextInt() and 0x00ffffff) }
            Bitmap.createBitmap(700, 700, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, 700, 0, 0, 700, 700)
                compress(Bitmap.CompressFormat.JPEG, 90, output)
                recycle()
            }
            output.toByteArray()
        }
        val total = (data.size + DtnConfig.IMAGE_CHUNK_BYTES - 1) / DtnConfig.IMAGE_CHUNK_BYTES
        assertTrue(total >= 2)
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        val message = Message(id(), sender, local, "", now, 60, MessageStatus.PENDING,
            hopCount = 1, copiesRemaining = 4, contentType = MessageContentType.IMAGE, mediaId = mediaId)
        val manifest = MediaManifest(message.messageId, mediaId, "image/jpeg", "image.jpg", data.size.toLong(),
            hash, DtnConfig.IMAGE_CHUNK_BYTES, total)
        try {
            var messages = MessageRepository(db)
            var repository = MediaRepository(db, files, messages, NetworkLogger()) { true }
            assertEquals(MediaOffer.Accept((0 until total).toList()), repository.offer(message, manifest, local, now, 100))
            assertEquals(MediaChunkResult.Progress(1, total), repository.receiveChunk(message.messageId, mediaId, 0,
                data.copyOfRange(0, DtnConfig.IMAGE_CHUNK_BYTES), local, now, 100))
            assertNull(messages.get(message.messageId))
            assertTrue(files.partFile(mediaId).exists())
            db.close()
            db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
            messages = MessageRepository(db)
            repository = MediaRepository(db, files, messages, NetworkLogger()) { true }
            repository.recover(local, now)
            assertEquals(MediaOffer.Accept((1 until total).toList()), repository.offer(message, manifest, local, now, 100))
            var result: MediaChunkResult? = null
            for (index in 1 until total) {
                val start = index * DtnConfig.IMAGE_CHUNK_BYTES
                result = repository.receiveChunk(message.messageId, mediaId, index,
                    data.copyOfRange(start, minOf(data.size, start + DtnConfig.IMAGE_CHUNK_BYTES)), local, now, 100)
            }
            assertTrue(result is MediaChunkResult.Complete)
            assertTrue(files.verified(manifest))
            assertEquals(MessageStatus.RECEIVED, messages.get(message.messageId)?.status)
            assertNotNull(messages.receipt(message.messageId))
            assertEquals(MediaOffer.Have, repository.offer(message, manifest, local, now, 100))
        } finally { files.delete(mediaId); db.close(); context.deleteDatabase(name) }
    }

    @Test fun relayOptOutRejectsBeforeChunksAndCorruptHashNeverCommits() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        val files = MediaFileStore(context); val local = id(); val mediaId = id(); val now = System.currentTimeMillis()
        val message = Message(id(), id(), id(), "", now, 60, MessageStatus.PENDING,
            hopCount = 1, copiesRemaining = 2, contentType = MessageContentType.IMAGE, mediaId = mediaId)
        val manifest = MediaManifest(message.messageId, mediaId, "image/jpeg", "image.jpg", 3,
            "0".repeat(64), DtnConfig.IMAGE_CHUNK_BYTES, 1)
        try {
            val messages = MessageRepository(db)
            val disabled = MediaRepository(db, files, messages, NetworkLogger()) { false }
            assertEquals(MediaOffer.Reject(MessageRejectReason.RELAY_DISABLED), disabled.offer(message, manifest, local, now, 100))
            assertFalse(files.partFile(mediaId).exists())
            val enabled = MediaRepository(db, files, messages, NetworkLogger()) { true }
            assertEquals(MediaOffer.Accept(listOf(0)), enabled.offer(message, manifest, local, now, 100))
            assertEquals(MediaChunkResult.Reject(MessageRejectReason.INVALID_MEDIA),
                enabled.receiveChunk(message.messageId, mediaId, 0, byteArrayOf(1, 2, 3), local, now, 100))
            assertNull(messages.get(message.messageId))
            assertFalse(db.media().get(mediaId)!!.isComplete)
        } finally { files.delete(mediaId); db.close() }
    }

    @Test fun directOnlyManifestAndPartialChunksRequireOriginalAuthenticatedSource() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        val files = MediaFileStore(context)
        val source = id(); val relay = id(); val destination = id(); val mediaId = id()
        val now = System.currentTimeMillis()
        val bytes = ByteArray(DtnConfig.IMAGE_CHUNK_BYTES + 1) { 7 }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val message = Message(id(), source, destination, "", now, 60, MessageStatus.PENDING,
            hopCount = 1, copiesRemaining = 4, contentType = MessageContentType.IMAGE,
            mediaId = mediaId, relayPolicy = RelayPolicy.DIRECT_ONLY)
        val manifest = MediaManifest(message.messageId, mediaId, "image/jpeg", "image.jpg",
            bytes.size.toLong(), hash, DtnConfig.IMAGE_CHUNK_BYTES, 2)
        try {
            val repository = MediaRepository(db, files, MessageRepository(db), NetworkLogger()) { true }
            assertEquals(MediaOffer.Reject(MessageRejectReason.RELAY_POLICY_DIRECT_ONLY),
                repository.offer(message, manifest, relay, now, 100, source))
            assertEquals(MediaOffer.Reject(MessageRejectReason.RELAY_POLICY_DIRECT_ONLY),
                repository.offer(message, manifest, destination, now, 100, relay))
            assertFalse(files.partFile(mediaId).exists())
            assertEquals(MediaOffer.Accept(listOf(0, 1)),
                repository.offer(message, manifest, destination, now, 100, source))
            assertEquals(MediaChunkResult.Reject(MessageRejectReason.RELAY_POLICY_DIRECT_ONLY),
                repository.receiveChunk(message.messageId, mediaId, 0,
                    bytes.copyOfRange(0, DtnConfig.IMAGE_CHUNK_BYTES), destination, now, 100, relay))
            assertEquals(0, db.media().get(mediaId)?.completedChunks)
        } finally { files.delete(mediaId); db.close() }
    }

    @Test fun receiptCleanupReclaimsOnlyRelayImageAndReleasesByteQuota() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        val files = MediaFileStore(context); val local = id(); val source = id(); val destination = id()
        val mediaId = id(); val now = System.currentTimeMillis()
        val message = Message(id(), source, destination, "", now, 60, MessageStatus.CARRIED,
            hopCount = 1, copiesRemaining = 2, contentType = MessageContentType.IMAGE, mediaId = mediaId)
        val bytes = byteArrayOf(1, 2, 3)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val manifest = MediaManifest(message.messageId, mediaId, "image/jpeg", "image.jpg", 3, hash,
            DtnConfig.IMAGE_CHUNK_BYTES, 1)
        try {
            files.finalFile(mediaId).writeBytes(bytes)
            val messages = MessageRepository(db)
            assertTrue(messages.insert(message))
            assertTrue(db.media().insert(MediaEntity(mediaId, message.messageId, source, destination,
                manifest.mimeType, manifest.originalFileName, manifest.fileSize, manifest.sha256,
                manifest.chunkSize, manifest.totalChunks, "1", true, now, message.expiresAt,
                "{}", now)) > 0)
            val repository = MediaRepository(db, files, messages, NetworkLogger()) { true }
            assertEquals(3L, repository.relayBytes(local, now))
            assertTrue(messages.saveReceipt(DeliveryReceipt.create(message, destination, now + 1)))
            messages.cleanup(local, now + 1)
            assertEquals(1, repository.cleanup(local, now + 1))
            assertFalse(files.finalFile(mediaId).exists())
            assertNull(db.media().get(mediaId))
            assertEquals(0L, repository.relayBytes(local, now + 1))
            assertNotNull(messages.receipt(message.messageId))
        } finally { files.delete(mediaId); db.close() }
    }

    @Test fun relayMediaByteQuotaRejectsBeforeAnyChunk() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        val files = MediaFileStore(context); val local = id(); val source = id(); val destination = id()
        val now = System.currentTimeMillis()
        try {
            repeat(10) {
                val messageId = id(); val mediaId = id()
                db.media().insert(MediaEntity(mediaId, messageId, source, destination, "image/jpeg", "image.jpg",
                    DtnConfig.MAX_IMAGE_BYTES.toLong(), "a".repeat(64), DtnConfig.IMAGE_CHUNK_BYTES, 40,
                    "0".repeat(40), false, now, now + 60_000, "{}", now))
            }
            val mediaId = id()
            val message = Message(id(), source, destination, "", now, 60, MessageStatus.PENDING,
                hopCount = 1, copiesRemaining = 2, contentType = MessageContentType.IMAGE, mediaId = mediaId)
            val manifest = MediaManifest(message.messageId, mediaId, "image/jpeg", "image.jpg", 3,
                "a".repeat(64), DtnConfig.IMAGE_CHUNK_BYTES, 1)
            val repository = MediaRepository(db, files, MessageRepository(db), NetworkLogger()) { true }
            assertEquals(MediaOffer.Reject(MessageRejectReason.BUFFER_FULL),
                repository.offer(message, manifest, local, now, 100))
            assertFalse(files.partFile(mediaId).exists())
        } finally { db.close() }
    }
}
