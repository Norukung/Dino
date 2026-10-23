package com.group16.dinoblog.repository

import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.model.Message
import kotlinx.coroutines.flow.Flow

enum class MessageAdmission { INSERTED, DUPLICATE, SUPPRESSED }

/** The same Room repository implements this boundary; no separate database or transport. */
interface ReceiptStore {
    val receipts: Flow<List<DeliveryReceipt>>
    suspend fun receipt(id: String): DeliveryReceipt?
    // Intentionally includes expired receipts: forwarding expiry must not erase delivery knowledge.
    suspend fun receiptForMessage(id: String): DeliveryReceipt?
    suspend fun saveReceipt(receipt: DeliveryReceipt): Boolean
    // Atomic admission + optional final receipt, including the receipt-before-message race.
    suspend fun commitReceived(message: Message, finalReceipt: DeliveryReceipt?): MessageAdmission
}
