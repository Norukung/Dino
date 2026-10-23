package com.group16.dinoblog.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.model.DeliveryReceipt

@Database(entities = [Message::class, Peer::class, DeliveryReceipt::class], version = 3, exportSchema = true)
@TypeConverters(Converters::class)
abstract class DinoblogDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun peers(): PeerDao
    abstract fun receipts(): DeliveryReceiptDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS delivery_receipts (
                    receiptId TEXT NOT NULL PRIMARY KEY, originalMessageId TEXT NOT NULL,
                    originalSourceId TEXT NOT NULL, originalDestinationId TEXT NOT NULL,
                    deliveredAt INTEGER NOT NULL, createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL,
                    hopCount INTEGER NOT NULL, hopLimit INTEGER NOT NULL)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_delivery_receipts_originalMessageId ON delivery_receipts(originalMessageId)")
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
