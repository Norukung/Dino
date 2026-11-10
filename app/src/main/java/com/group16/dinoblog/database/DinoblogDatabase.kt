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

@Database(entities = [Message::class, Peer::class, DeliveryReceipt::class, DeliveryPredictability::class, MediaEntity::class], version = 9, exportSchema = true)
@TypeConverters(Converters::class)
abstract class DinoblogDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun peers(): PeerDao
    abstract fun receipts(): DeliveryReceiptDao
    abstract fun predictabilities(): DeliveryPredictabilityDao
    abstract fun media(): MediaDao

    companion object {
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN contentType TEXT NOT NULL DEFAULT 'IMAGE'")
                db.execSQL("ALTER TABLE media ADD COLUMN durationMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE media ADD COLUMN width INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE media ADD COLUMN height INTEGER DEFAULT NULL")
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
