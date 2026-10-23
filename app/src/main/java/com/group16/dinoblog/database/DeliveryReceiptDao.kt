package com.group16.dinoblog.database

import androidx.room.*
import com.group16.dinoblog.model.DeliveryReceipt
import kotlinx.coroutines.flow.Flow

@Dao
interface DeliveryReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(receipt: DeliveryReceipt): Long
    @Query("SELECT * FROM delivery_receipts WHERE originalMessageId = :id")
    suspend fun get(id: String): DeliveryReceipt?
    @Query("SELECT originalMessageId FROM delivery_receipts ORDER BY originalMessageId")
    suspend fun ids(): List<String>
    @Query("SELECT * FROM delivery_receipts ORDER BY originalMessageId")
    fun observeAll(): Flow<List<DeliveryReceipt>>
}
