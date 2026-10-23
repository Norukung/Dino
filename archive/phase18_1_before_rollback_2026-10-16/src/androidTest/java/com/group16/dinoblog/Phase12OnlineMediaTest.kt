package com.group16.dinoblog

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.dtn.DtnConfig
import com.group16.dinoblog.model.MediaEntity
import com.group16.dinoblog.model.MediaManifest
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.repository.MediaFileStore
import com.group16.dinoblog.repository.MediaRepository
import com.group16.dinoblog.repository.MessageRepository
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase12OnlineMediaTest {
    @Test fun onlineDownloadCompletesPartialDtnMediaWithoutDuplicateMessage(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "phase12-media-${UUID.randomUUID()}.db"
        val files = MediaFileStore(context)
        val mediaId = UUID.randomUUID().toString()
        val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, name).build()
        try {
            val output = ByteArrayOutputStream()
            val random = java.util.Random(12)
            val bitmap = Bitmap.createBitmap(700, 700, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(700 * 700) { 0xff000000.toInt() or (random.nextInt() and 0x00ffffff) }
            bitmap.setPixels(pixels, 0, 700, 0, 0, 700, 700)
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            bitmap.recycle()
            val bytes = output.toByteArray()
            assertTrue(bytes.size > DtnConfig.IMAGE_CHUNK_BYTES)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            val source = UUID.randomUUID().toString()
            val destination = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val message = Message(UUID.randomUUID().toString(), source, destination, "", now, 86_400,
                MessageStatus.RECEIVED, contentType = MessageContentType.IMAGE, mediaId = mediaId)
            val chunks = (bytes.size + DtnConfig.IMAGE_CHUNK_BYTES - 1) / DtnConfig.IMAGE_CHUNK_BYTES
            val manifest = MediaManifest(message.messageId, mediaId, "image/jpeg", "image.jpg", bytes.size.toLong(),
                sha, DtnConfig.IMAGE_CHUNK_BYTES, chunks)
            files.writeChunk(manifest, 0, bytes.copyOfRange(0, DtnConfig.IMAGE_CHUNK_BYTES))
            db.media().insert(MediaEntity(mediaId, message.messageId, source, destination, "image/jpeg", "image.jpg",
                bytes.size.toLong(), sha, DtnConfig.IMAGE_CHUNK_BYTES, chunks, "1" + "0".repeat(chunks - 1),
                false, now, message.expiresAt, Json.encodeToString(message), now))
            val repository = MessageRepository(db)
            val media = MediaRepository(db, files, repository, NetworkLogger()) { true }
            assertTrue(media.receiveOnline(message, manifest, bytes, destination, now + 1))
            assertTrue(files.verified(manifest))
            assertFalse(files.partFile(mediaId).exists())
            assertTrue(db.media().get(mediaId)!!.isComplete)
            assertNotNull(repository.get(message.messageId))
            assertNotNull(repository.receipt(message.messageId))
            assertTrue(media.receiveOnline(message, manifest, null, destination, now + 2))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages WHERE messageId='${message.messageId}'").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        } finally {
            db.close(); files.delete(mediaId); context.deleteDatabase(name)
        }
    }
}
