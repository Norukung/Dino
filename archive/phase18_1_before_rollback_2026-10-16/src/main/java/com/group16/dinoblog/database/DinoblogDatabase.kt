package com.group16.dinoblog.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.group16.dinoblog.model.DeliveryPredictability
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.model.MediaEntity
import com.group16.dinoblog.model.OnlineMessageState
import com.group16.dinoblog.model.ExperimentSession
import com.group16.dinoblog.model.ExperimentEvent
import com.group16.dinoblog.model.ExperimentMessageResult
import com.group16.dinoblog.model.PeerIdentityBinding
import com.group16.dinoblog.model.PendingSecurityReceipt
import com.group16.dinoblog.model.MessageRecall
import com.group16.dinoblog.model.Revoke
import com.group16.dinoblog.model.RevokeResult

@Database(entities = [Message::class, Peer::class, DeliveryReceipt::class, DeliveryPredictability::class, MediaEntity::class, OnlineMessageState::class,
    ExperimentSession::class, ExperimentEvent::class, ExperimentMessageResult::class,
    PeerIdentityBinding::class, PendingSecurityReceipt::class, MessageRecall::class,
    Revoke::class, RevokeResult::class], version = 14, exportSchema = true)
@TypeConverters(Converters::class)
abstract class DinoblogDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun peers(): PeerDao
    abstract fun receipts(): DeliveryReceiptDao
    abstract fun predictabilities(): DeliveryPredictabilityDao
    abstract fun media(): MediaDao
    abstract fun onlineMessages(): OnlineMessageDao
    abstract fun experiments(): ExperimentDao
    abstract fun identities(): PeerIdentityDao
    abstract fun recalls(): RecallDao

    companion object {
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN relayPolicy TEXT NOT NULL DEFAULT 'ALLOW_RELAY'")
            }
        }
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Pre-upgrade messages may already have escaped; default conservatively.
                db.execSQL("ALTER TABLE messages ADD COLUMN deliveryAttempted INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE TABLE IF NOT EXISTS message_recalls (messageId TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS revokes (revokeId TEXT NOT NULL PRIMARY KEY,
                    originalMessageId TEXT NOT NULL, originalSourceId TEXT NOT NULL,
                    originalDestinationId TEXT NOT NULL, originalExpiresAt INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL, retentionUntil INTEGER NOT NULL,
                    signatureVersion INTEGER NOT NULL, sourcePublicKey TEXT NOT NULL,
                    sourcePublicKeyFingerprint TEXT NOT NULL, sourceSignature TEXT NOT NULL,
                    verificationState TEXT NOT NULL)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_revokes_originalMessageId ON revokes (originalMessageId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS revoke_results (resultId TEXT NOT NULL PRIMARY KEY,
                    revokeId TEXT NOT NULL, originalMessageId TEXT NOT NULL,
                    originalSourceId TEXT NOT NULL, originalDestinationId TEXT NOT NULL,
                    result TEXT NOT NULL, processedAt INTEGER NOT NULL, signatureVersion INTEGER NOT NULL,
                    destinationPublicKey TEXT NOT NULL, destinationPublicKeyFingerprint TEXT NOT NULL,
                    destinationSignature TEXT NOT NULL, verificationState TEXT NOT NULL)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_revoke_results_originalMessageId ON revoke_results (originalMessageId)")
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for ((name, definition) in listOf(
                    "signatureVersion" to "INTEGER", "originPublicKey" to "TEXT",
                    "originPublicKeyFingerprint" to "TEXT", "contentDigest" to "TEXT",
                    "originSignature" to "TEXT", "signedMediaMimeType" to "TEXT",
                    "signedMediaFileSize" to "INTEGER", "signedMediaSha256" to "TEXT"))
                    db.execSQL("ALTER TABLE messages ADD COLUMN $name $definition")
                for ((name, definition) in listOf(
                    "signatureVersion" to "INTEGER", "destinationPublicKey" to "TEXT",
                    "destinationPublicKeyFingerprint" to "TEXT", "destinationSignature" to "TEXT"))
                    db.execSQL("ALTER TABLE delivery_receipts ADD COLUMN $name $definition")
                db.execSQL("""CREATE TABLE IF NOT EXISTS peer_identity_bindings (
                    deviceId TEXT NOT NULL PRIMARY KEY, publicKey TEXT NOT NULL,
                    fingerprint TEXT NOT NULL, trustState TEXT NOT NULL,
                    firstSeenAt INTEGER NOT NULL, lastVerifiedAt INTEGER NOT NULL,
                    verificationSource TEXT NOT NULL)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS pending_security_receipts (
                    originalMessageId TEXT NOT NULL PRIMARY KEY,
                    receiptJson TEXT NOT NULL, receivedAt INTEGER NOT NULL)""")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS experiment_sessions (
                    experimentId TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL, startedAt INTEGER NOT NULL,
                    endedAt INTEGER, routingMode TEXT NOT NULL, transportMode TEXT NOT NULL,
                    deviceHash TEXT NOT NULL, appVersion TEXT NOT NULL, buildType TEXT NOT NULL,
                    nodeCount INTEGER, messageCountTarget INTEGER, notes TEXT NOT NULL,
                    bytesSent INTEGER NOT NULL, bytesReceived INTEGER NOT NULL,
                    mediaBytesSent INTEGER NOT NULL, mediaBytesReceived INTEGER NOT NULL,
                    dtnControlBytesSent INTEGER NOT NULL, dtnPayloadBytesSent INTEGER NOT NULL,
                    onlineControlBytesSent INTEGER NOT NULL, onlinePayloadBytesSent INTEGER NOT NULL,
                    currentBufferBytes INTEGER NOT NULL, peakBufferBytes INTEGER NOT NULL,
                    partialMediaBytes INTEGER NOT NULL)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS experiment_events (
                    eventId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, experimentId TEXT NOT NULL,
                    eventTime INTEGER NOT NULL, elapsedRealtimeMs INTEGER NOT NULL, eventType TEXT NOT NULL,
                    messageId TEXT, mediaId TEXT, localDeviceHash TEXT NOT NULL, peerDeviceHash TEXT,
                    routingMode TEXT NOT NULL, decision TEXT, bytes INTEGER, hopCount INTEGER,
                    copiesRemaining INTEGER, bufferBytes INTEGER, contentType TEXT, transport TEXT,
                    durationMs INTEGER, result TEXT, reason TEXT)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_experiment_events_experimentId_eventTime ON experiment_events (experimentId, eventTime)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS experiment_messages (
                    experimentId TEXT NOT NULL, messageId TEXT NOT NULL, sourceHash TEXT NOT NULL,
                    destinationHash TEXT NOT NULL, contentType TEXT NOT NULL, fileSizeBytes INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL, deliveredAt INTEGER, receiptReceivedAt INTEGER,
                    finalHopCount INTEGER, deliveryTransport TEXT, routingMode TEXT NOT NULL,
                    finalStatus TEXT NOT NULL, createdLocally INTEGER NOT NULL,
                    PRIMARY KEY (experimentId, messageId))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_experiment_messages_experimentId ON experiment_messages (experimentId)")
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'IMAGE'")
                db.execSQL("ALTER TABLE media ADD COLUMN durationMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE media ADD COLUMN width INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE media ADD COLUMN height INTEGER DEFAULT NULL")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS online_message_state (
                    messageId TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL,
                    serverAcceptedAt INTEGER)""")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN contentType TEXT NOT NULL DEFAULT 'TEXT'")
                db.execSQL("ALTER TABLE messages ADD COLUMN mediaId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN reservedPeerId TEXT DEFAULT NULL")
                db.execSQL("""CREATE TABLE IF NOT EXISTS media (
                    mediaId TEXT NOT NULL PRIMARY KEY, messageId TEXT NOT NULL,
                    sourceId TEXT NOT NULL, destinationId TEXT NOT NULL,
                    mimeType TEXT NOT NULL, originalFileName TEXT NOT NULL,
                    fileSize INTEGER NOT NULL, sha256 TEXT NOT NULL,
                    chunkSize INTEGER NOT NULL, totalChunks INTEGER NOT NULL,
                    receivedChunks TEXT NOT NULL, isComplete INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL,
                    messageJson TEXT NOT NULL, lastUpdatedAt INTEGER NOT NULL)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_media_messageId ON media (messageId)")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reservedCopies INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE peers ADD COLUMN localAlias TEXT DEFAULT NULL")
                // Phase 8 peer names came from Android hardware or carrier placeholders.
                db.execSQL("UPDATE peers SET name = 'DinoBlog User'")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS delivery_predictabilities (
                    destinationDeviceId TEXT NOT NULL PRIMARY KEY,
                    predictability REAL NOT NULL, lastUpdatedAt INTEGER NOT NULL)""")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Historical sources and relays all enter WAIT. Never mint budgets on upgrade.
                db.execSQL("ALTER TABLE messages ADD COLUMN copiesRemaining INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS delivery_receipts (
                    originalMessageId TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL,
                    destinationId TEXT NOT NULL, deliveredAt INTEGER NOT NULL,
                    originalExpiresAt INTEGER NOT NULL, createdByDeviceId TEXT NOT NULL)""")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Additive migration: never discard existing conversations or pending work.
                db.execSQL("ALTER TABLE messages ADD COLUMN expiresAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN hopCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN hopLimit INTEGER NOT NULL DEFAULT 8")
                db.execSQL("ALTER TABLE messages ADD COLUMN carrierId TEXT")
                db.execSQL("UPDATE messages SET expiresAt = timestamp + ttl * 1000")
                db.execSQL("UPDATE messages SET hopCount = 1 WHERE status IN ('DELIVERED', 'RECEIVED')")
            }
        }
    }
}
