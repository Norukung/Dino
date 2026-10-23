package com.group16.dinoblog

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.MediaEntity
import com.group16.dinoblog.model.RecallState
import com.group16.dinoblog.model.Revoke
import com.group16.dinoblog.model.RevokeOutcome
import com.group16.dinoblog.repository.MediaFileStore
import com.group16.dinoblog.repository.MediaRepository
import com.group16.dinoblog.repository.MessageRepository
import com.group16.dinoblog.repository.MessageRevokedException
import com.group16.dinoblog.repository.RecallRepository
import com.group16.dinoblog.security.CanonicalSignatures
import com.group16.dinoblog.security.CryptoIdentityManager
import com.group16.dinoblog.util.DeviceIdentity
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase18RecallDatabaseTest {
    @Test fun localCancelRequiresProofThatNoDeliveryWasAttempted(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        try {
            val source = DeviceIdentity(context).id
            val security = CryptoIdentityManager(context, source, db.identities())
            val logger = NetworkLogger()
            val messages = MessageRepository(db, logger, security = security)
            val media = MediaRepository(db, MediaFileStore(context), messages, logger, security) { true }
            val recalls = RecallRepository(db, source, security, media, logger)
            val destination = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            suspend fun queued() = security.signMessage(Message(UUID.randomUUID().toString(), source,
                destination, "Recall test", now, 3600, MessageStatus.PENDING)).also {
                assertTrue(messages.insert(it))
            }
            val local = queued()
            assertEquals(RecallState.LOCAL_CANCELLED, recalls.cancelOwn(local.messageId))
            assertNull(db.recalls().revoke(local.messageId))
            assertFalse(recalls.markAttempted(local.messageId))
            val distributed = queued()
            assertTrue(recalls.markAttempted(distributed.messageId))
            assertEquals(RecallState.REQUESTED, recalls.cancelOwn(distributed.messageId))
            val signed = requireNotNull(db.recalls().revoke(distributed.messageId))
            assertEquals("SERVER_VERIFIED", security.verifyRevoke(signed))
            assertEquals(RecallState.REQUESTED, recalls.cancelOwn(distributed.messageId))
            assertEquals(signed.revokeId, db.recalls().revoke(distributed.messageId)?.revokeId)
        } finally { db.close() }
    }

    @Test fun undeliveredCompleteOrPartialDestinationMediaIsReclaimed(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        val files = MediaFileStore(context)
        val source = UUID.randomUUID().toString()
        val destination = UUID.randomUUID().toString()
        val messages = MessageRepository(db)
        val media = MediaRepository(db, files, messages, NetworkLogger()) { true }
        val ids = mutableListOf<String>()
        try {
            for (complete in listOf(false, true)) {
                val mediaId = UUID.randomUUID().toString()
                ids += mediaId
                val messageId = UUID.randomUUID().toString()
                db.media().insert(MediaEntity(mediaId, messageId, source, destination, "image/jpeg",
                    "image.jpg", 3, "a".repeat(64), 3, 1, if (complete) "1" else "0",
                    complete, 1000, 61_000, "{}", 1000))
                val file = if (complete) files.finalFile(mediaId) else files.partFile(mediaId)
                file.writeBytes(byteArrayOf(1, 2, 3))
                assertEquals(3L, media.cleanupRevoked(messageId, destination))
                assertNull(db.media().get(mediaId))
                assertFalse(file.exists())
            }
            // Simulate a crash after the verified tombstone transaction but before file deletion.
            val staleMediaId = UUID.randomUUID().toString()
            ids += staleMediaId
            val staleMessageId = UUID.randomUUID().toString()
            db.media().insert(MediaEntity(staleMediaId, staleMessageId, source, destination,
                "image/jpeg", "image.jpg", 3, "a".repeat(64), 3, 1, "0", false,
                1000, System.currentTimeMillis() + 3_600_000, "{}", 1000))
            files.partFile(staleMediaId).writeBytes(byteArrayOf(1, 2, 3))
            val security = CryptoIdentityManager(context, destination, db.identities())
            val recalls = RecallRepository(db, destination, security, media, NetworkLogger())
            val at = System.currentTimeMillis()
            db.recalls().insertRevoke(Revoke(UUID.randomUUID().toString(), staleMessageId,
                source, destination, at + 3_600_000, at, at + 90_000_000,
                1, "test", "test", "test"))
            recalls.cleanup(at)
            assertNull(db.media().get(staleMediaId))
            assertFalse(files.partFile(staleMediaId).exists())
        } finally { ids.forEach(files::delete); db.close() }
    }

    @Test fun requestedRecallAndRevokeIdSurviveDatabaseReopen(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "phase18-restart-${UUID.randomUUID()}.db"
        val source = DeviceIdentity(context).id
        val messageId = UUID.randomUUID().toString()
        var revokeId = ""
        try {
            val db = Room.databaseBuilder(context, DinoblogDatabase::class.java, databaseName).build()
            try {
                val security = CryptoIdentityManager(context, source, db.identities())
                val logger = NetworkLogger()
                val messages = MessageRepository(db, logger, security = security)
                val media = MediaRepository(db, MediaFileStore(context), messages, logger, security) { true }
                val recalls = RecallRepository(db, source, security, media, logger)
                val outgoing = security.signMessage(Message(messageId, source, UUID.randomUUID().toString(),
                    "Persist recall", System.currentTimeMillis(), 3600, MessageStatus.PENDING))
                assertTrue(messages.insert(outgoing))
                assertTrue(recalls.markAttempted(messageId))
                assertEquals(RecallState.REQUESTED, recalls.cancelOwn(messageId))
                revokeId = requireNotNull(db.recalls().revoke(messageId)).revokeId
            } finally { db.close() }
            val reopened = Room.databaseBuilder(context, DinoblogDatabase::class.java, databaseName).build()
            try {
                assertEquals("REQUESTED", reopened.recalls().state(messageId)?.state)
                assertEquals(revokeId, reopened.recalls().revoke(messageId)?.revokeId)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(databaseName) }
    }

    @Test fun forgedRevokeCannotSuppressButTrustedRevokeWinsBeforeDelivery(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DinoblogDatabase::class.java).build()
        try {
            val destination = DeviceIdentity(context).id
            val source = UUID.randomUUID().toString()
            val attacker = UUID.randomUUID().toString()
            val sourceKey = CryptoIdentityManager(context, source, db.identities())
            val destinationKey = CryptoIdentityManager(context, destination, db.identities())
            val attackerKey = CryptoIdentityManager(context, attacker, db.identities())
            assertEquals("TOFU_TRUSTED", destinationKey.pin(source, sourceKey.publicKey, "TOFU"))
            val logger = NetworkLogger()
            val messages = MessageRepository(db, logger, security = destinationKey)
            val media = MediaRepository(db, MediaFileStore(context), messages, logger, destinationKey) { true }
            val recalls = RecallRepository(db, destination, destinationKey, media, logger)
            val now = System.currentTimeMillis()
            val original = sourceKey.signMessage(Message(UUID.randomUUID().toString(), source,
                destination, "Before recall", now, 3600, MessageStatus.PENDING))
            val unsigned = Revoke(UUID.randomUUID().toString(), original.messageId, source,
                destination, original.expiresAt, now + 1, original.expiresAt + 86_400_000,
                1, "", "", "")
            val forgedFields = unsigned.copy(sourcePublicKey = attackerKey.publicKey,
                sourcePublicKeyFingerprint = attackerKey.fingerprint)
            val forged = forgedFields.copy(sourceSignature = attackerKey.sign(CanonicalSignatures.revoke(forgedFields)))
            assertTrue(runCatching { recalls.receiveRevoke(forged) }.isFailure)
            assertNull(db.recalls().revoke(original.messageId))
            val trusted = sourceKey.signRevoke(unsigned)
            assertTrue(recalls.receiveRevoke(trusted))
            assertEquals(RevokeOutcome.CANCELLED_BEFORE_DELIVERY,
                db.recalls().result(original.messageId)?.result)
            try {
                messages.commitReceived(original.copy(status = MessageStatus.RECEIVED), null,
                    destination, System.currentTimeMillis(), 100)
                throw AssertionError("Revoked message was accepted")
            } catch (_: MessageRevokedException) { }
            assertNull(db.messages().get(original.messageId))
        } finally { db.close() }
    }
}
