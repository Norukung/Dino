package com.group16.dinoblog.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.group16.dinoblog.model.DeliveryReceipt
import kotlinx.coroutines.flow.Flow

@Dao
interface DeliveryReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(receipt: DeliveryReceipt): Long
    @Query("SELECT * FROM delivery_receipts WHERE receiptId = :id")
    suspend fun get(id: String): DeliveryReceipt?
    @Query("SELECT * FROM delivery_receipts WHERE originalMessageId = :id")
    suspend fun forMessage(id: String): DeliveryReceipt?
    @Query("SELECT * FROM delivery_receipts ORDER BY createdAt, receiptId")
    fun observeAll(): Flow<List<DeliveryReceipt>>
    // Include exhausted copies as peer knowledge, but never forward them again.
    @Query("SELECT receiptId FROM delivery_receipts WHERE expiresAt > :now AND hopCount <= hopLimit ORDER BY receiptId")
    suspend fun activeIds(now: Long): List<String>
}
