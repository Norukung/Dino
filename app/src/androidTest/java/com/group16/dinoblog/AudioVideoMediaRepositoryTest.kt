package com.group16.dinoblog

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
import com.group16.dinoblog.network.MessageRejectReason
import com.group16.dinoblog.repository.MediaChunkResult
import com.group16.dinoblog.repository.MediaFileStore
import com.group16.dinoblog.repository.MediaOffer
import com.group16.dinoblog.repository.MediaRepository
import com.group16.dinoblog.repository.MessageRepository
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AudioVideoMediaRepositoryTest {
    private fun id() = UUID.randomUUID().toString()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun completeMediaUsesVerifiedBytesEvenWhenSenderDurationDiffers() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        val files = MediaFileStore(context)
        val mediaId = id(); val local = id(); val now = System.currentTimeMillis()
        val pcmBytes = 8_000 * 9 * 2
        val wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcmBytes); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcmBytes)
            put(ByteArray(pcmBytes))
        }.array()
        val hash = MessageDigest.getInstance("SHA-256").digest(wav).joinToString("") { "%02x".format(it) }
        val message = Message(id(), id(), local, "", now, 60, MessageStatus.PENDING,
            hopCount = 1, contentType = MessageContentType.AUDIO, mediaId = mediaId)
        val manifest = MediaManifest(message.messageId, mediaId, "audio/wav", "sample.wav",
            wav.size.toLong(), hash, DtnConfig.IMAGE_CHUNK_BYTES, 2,
            MessageContentType.AUDIO, durationMs = 8_000L) // The WAV itself is nine seconds.
        try {
            val messages = MessageRepository(db)
            val repository = MediaRepository(db, files, messages, NetworkLogger()) { true }
            assertEquals(MediaOffer.Accept(listOf(0, 1)), repository.offer(message, manifest, local, now, 100))
            assertEquals(MediaChunkResult.Progress(1, 2), repository.receiveChunk(message.messageId,
                mediaId, 0, wav.copyOfRange(0, DtnConfig.IMAGE_CHUNK_BYTES), local, now, 100))
            assertTrue(repository.receiveChunk(message.messageId, mediaId, 1,
                wav.copyOfRange(DtnConfig.IMAGE_CHUNK_BYTES, wav.size), local, now, 100) is MediaChunkResult.Complete)
            assertTrue(repository.verifiedForPlayback(mediaId))
            assertNotNull(messages.get(message.messageId))
            assertNotNull(messages.receipt(message.messageId))
        } finally { files.delete(mediaId); db.close() }
    }

    @Test fun emptyVideoTransferStateIsRemovedForCleanRetry() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        val files = MediaFileStore(context)
        val mediaId = id(); val local = id(); val now = System.currentTimeMillis()
        val message = Message(id(), id(), local, "", now, 3_600, MessageStatus.PENDING,
            hopCount = 1, contentType = MessageContentType.VIDEO, mediaId = mediaId)
        val manifest = MediaManifest(message.messageId, mediaId, "video/mp4", "clip.mp4", 3,
            "a".repeat(64), DtnConfig.IMAGE_CHUNK_BYTES, 1, MessageContentType.VIDEO, 1_000, 320, 240)
        try {
            val repository = MediaRepository(db, files, MessageRepository(db), NetworkLogger()) { true }
            assertEquals(MediaOffer.Accept(listOf(0)), repository.offer(message, manifest, local, now, 100))
            repository.cleanup(local, now + DtnConfig.EMPTY_MEDIA_PART_TIMEOUT_MS + 1)
            assertNull(db.media().get(mediaId))
            assertFalse(files.partFile(mediaId).exists())
            assertEquals(MediaOffer.Accept(listOf(0)), repository.offer(message, manifest, local,
                now + DtnConfig.EMPTY_MEDIA_PART_TIMEOUT_MS + 2, 100))
            files.partFile(mediaId).delete()
            repository.recover(local, now + DtnConfig.EMPTY_MEDIA_PART_TIMEOUT_MS + 3)
            assertNull(db.media().get(mediaId))
        } finally { files.delete(mediaId); db.close() }
    }

    @Test fun audioAndVideoResumeOnlyMissingChunksAndNeverAckCorruptContent() = runBlocking {
        for (type in listOf(MessageContentType.AUDIO, MessageContentType.VIDEO)) {
            val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
            val files = MediaFileStore(context)
            val mediaId = id(); val local = id(); val now = System.currentTimeMillis()
            val message = Message(id(), id(), local, "", now, 60, MessageStatus.PENDING,
                hopCount = 1, copiesRemaining = 2, contentType = type, mediaId = mediaId)
            val manifest = MediaManifest(message.messageId, mediaId,
                if (type == MessageContentType.AUDIO) "audio/mpeg" else "video/mp4", "media.file",
                DtnConfig.IMAGE_CHUNK_BYTES.toLong() + 3, "0".repeat(64), DtnConfig.IMAGE_CHUNK_BYTES, 2,
                type, 1000, if (type == MessageContentType.VIDEO) 320 else null,
                if (type == MessageContentType.VIDEO) 240 else null)
            try {
                val messages = MessageRepository(db)
                var repository = MediaRepository(db, files, messages, NetworkLogger()) { true }
                assertEquals(MediaOffer.Accept(listOf(0, 1)), repository.offer(message, manifest, local, now, 100))
                assertEquals(MediaChunkResult.Progress(1, 2), repository.receiveChunk(message.messageId,
                    mediaId, 0, ByteArray(DtnConfig.IMAGE_CHUNK_BYTES), local, now, 100))
                assertNull(messages.get(message.messageId))
                assertNull(messages.receipt(message.messageId))
                repository = MediaRepository(db, files, messages, NetworkLogger()) { true }
                assertEquals(MediaOffer.Accept(listOf(1)), repository.offer(message, manifest, local, now, 100))
                assertEquals(MediaChunkResult.Reject(MessageRejectReason.INVALID_MEDIA),
                    repository.receiveChunk(message.messageId, mediaId, 1, byteArrayOf(1, 2, 3), local, now, 100))
                assertNull(messages.get(message.messageId))
                assertNull(messages.receipt(message.messageId))
                assertNull(db.media().get(mediaId))
                assertFalse(files.partFile(mediaId).exists())
            } finally { files.delete(mediaId); db.close() }
        }
    }

    @Test fun oversizedAudioAndVideoRejectBeforeAllocatingPartFile() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        val files = MediaFileStore(context); val local = id(); val now = System.currentTimeMillis()
        try {
            val repository = MediaRepository(db, files, MessageRepository(db), NetworkLogger()) { true }
            for (type in listOf(MessageContentType.AUDIO, MessageContentType.VIDEO)) {
                val mediaId = id()
                val message = Message(id(), id(), local, "", now, 60, MessageStatus.PENDING,
                    hopCount = 1, contentType = type, mediaId = mediaId)
                val size = DtnConfig.maxMediaBytes(type).toLong() + 1
                val manifest = MediaManifest(message.messageId, mediaId,
                    if (type == MessageContentType.AUDIO) "audio/mpeg" else "video/mp4", "media.file",
                    size, "a".repeat(64), DtnConfig.IMAGE_CHUNK_BYTES,
                    ((size + DtnConfig.IMAGE_CHUNK_BYTES - 1) / DtnConfig.IMAGE_CHUNK_BYTES).toInt(),
                    type, 1000, if (type == MessageContentType.VIDEO) 320 else null,
                    if (type == MessageContentType.VIDEO) 240 else null)
                assertEquals(MediaOffer.Reject(MessageRejectReason.MEDIA_TOO_LARGE),
                    repository.offer(message, manifest, local, now, 100))
                assertFalse(files.partFile(mediaId).exists())
                assertNull(db.media().get(mediaId))
            }
        } finally { db.close() }
    }

    @Test fun videoAndAudioShareRelayByteQuotaBeforeChunks() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        val files = MediaFileStore(context); val local = id(); val source = id(); val destination = id()
        val now = System.currentTimeMillis()
        try {
            for (type in listOf(MessageContentType.VIDEO, MessageContentType.VIDEO,
                MessageContentType.VIDEO, MessageContentType.AUDIO)) {
                val size = DtnConfig.maxMediaBytes(type).toLong()
                val chunks = ((size + DtnConfig.IMAGE_CHUNK_BYTES - 1) / DtnConfig.IMAGE_CHUNK_BYTES).toInt()
                db.media().insert(MediaEntity(id(), id(), source, destination,
                    if (type == MessageContentType.AUDIO) "audio/mpeg" else "video/mp4",
                    "media.file", size, "a".repeat(64), DtnConfig.IMAGE_CHUNK_BYTES, chunks,
                    "0".repeat(chunks), false, now, now + 60_000, "{}", now, type, 1000,
                    if (type == MessageContentType.VIDEO) 320 else null,
                    if (type == MessageContentType.VIDEO) 240 else null))
            }
            val mediaId = id()
            val message = Message(id(), source, destination, "", now, 60, MessageStatus.PENDING,
                hopCount = 1, contentType = MessageContentType.VIDEO, mediaId = mediaId)
            val manifest = MediaManifest(message.messageId, mediaId, "video/mp4", "video.mp4", 3,
                "a".repeat(64), DtnConfig.IMAGE_CHUNK_BYTES, 1, MessageContentType.VIDEO,
                1000, 320, 240)
            val repository = MediaRepository(db, files, MessageRepository(db), NetworkLogger()) { true }
            assertEquals(MediaOffer.Reject(MessageRejectReason.BUFFER_FULL),
                repository.offer(message, manifest, local, now, 100))
            assertFalse(files.partFile(mediaId).exists())
        } finally { db.close() }
    }
}
