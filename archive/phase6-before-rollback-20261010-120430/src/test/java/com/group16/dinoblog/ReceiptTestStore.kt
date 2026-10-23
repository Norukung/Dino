package com.group16.dinoblog

import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.model.*
import com.group16.dinoblog.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory transactional stand-in. Actual Room transaction/migration tests are in androidTest. */
internal class ReceiptTestStore(val base: Phase4TestStore = Phase4TestStore()) :
    MessageStore by base, InventoryStore, ReceiptStore {
    private val lock = Mutex()
    override val receipts = MutableStateFlow<List<DeliveryReceipt>>(emptyList())
    override val inventoryChanges = combine(base.messages, receipts) { _, _ -> Unit }
    var failCommit = false
    var beforeReceiptRead: suspend () -> Unit = {}
    var beforeMessageCommit: suspend () -> Unit = {}
    override suspend fun receipt(id: String): DeliveryReceipt? { beforeReceiptRead(); return receipts.value.find { it.receiptId == id } }
    override suspend fun receiptForMessage(id: String) = receipts.value.find { it.originalMessageId == id }
    override suspend fun activeReceiptIds(now: Long) = receipts.value.filter { it.expiresAt > now }.map { it.receiptId }.sorted()
    override suspend fun forwardableMessageIds(now: Long) = base.forwardableMessageIds(now).filter { receiptForMessage(it) == null }
    override suspend fun receivedMessageIds(now: Long) = base.receivedMessageIds(now).filter { receiptForMessage(it) == null }
    override suspend fun saveReceipt(receipt: DeliveryReceipt): Boolean = lock.withLock {
        check(!failCommit) { "Receipt disk failure" }
        val existing = receiptForMessage(receipt.originalMessageId)
        require(existing == null || existing.sameReceipt(receipt))
        val message = base.get(receipt.originalMessageId)
        require(message == null || receipt.matches(message))
        if (existing != null) return@withLock false
        message?.let {
            if (it.status != MessageStatus.RECEIVED) base.rows[it.messageId] = it.copy(status = MessageStatus.DELIVERED)
        }
        receipts.value = receipts.value + receipt
        base.messages.value = base.rows.values.toList()
        true
    }
    override suspend fun commitReceived(message: Message, finalReceipt: DeliveryReceipt?): MessageAdmission {
        beforeMessageCommit()
        return lock.withLock {
            check(!failCommit) { "Message/receipt transaction failure" }
            val known = receiptForMessage(message.messageId)
            require(known == null || known.matches(message))
            val existing = base.get(message.messageId)
            require(existing == null || DeliveryEngine.sameBundle(existing, message))
            if (known != null && existing == null) return@withLock MessageAdmission.SUPPRESSED
            if (existing == null) base.insert(message)
            if (finalReceipt != null && known == null) receipts.value = receipts.value + finalReceipt
            if (existing == null) MessageAdmission.INSERTED else MessageAdmission.DUPLICATE
        }
    }
}
