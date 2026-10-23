package com.group16.dinoblog.repository

import com.group16.dinoblog.dtn.DtnConfig
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.model.Message
import kotlinx.coroutines.flow.Flow

interface ReceiptStore {
    val receipts: Flow<List<DeliveryReceipt>>
    suspend fun receipt(id: String): DeliveryReceipt?
    suspend fun receiptIds(): List<String>
    /** Atomic receipt insert and message status update. First delivery event wins. */
    suspend fun saveReceipt(receipt: DeliveryReceipt): Boolean
    /** Atomic tombstone check, message admission, and optional destination receipt. */
    suspend fun commitReceived(message: Message, receipt: DeliveryReceipt?, localId: String,
        at: Long = System.currentTimeMillis(), relayLimit: Int = DtnConfig.MAX_RELAY_BUFFER_MESSAGES): Boolean
}
