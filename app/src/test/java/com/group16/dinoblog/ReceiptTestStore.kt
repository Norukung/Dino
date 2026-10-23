package com.group16.dinoblog

import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.model.ForwardingKind
import com.group16.dinoblog.model.*
import com.group16.dinoblog.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ReceiptTestStore(private val base: Phase4TestStore = Phase4TestStore()) :
    MessageStore by base, InventoryStore by base, ReceiptStore {
    override val receipts = MutableStateFlow<List<DeliveryReceipt>>(emptyList())
    private val lock = Mutex()
    val rows get() = base.rows
    var failReceipt = false
    override val inventoryChanges = combine(base.messages, receipts) { _, _ -> Unit }
    override suspend fun receipt(id: String) = receipts.value.find { it.originalMessageId == id }
    override suspend fun receiptIds() = receipts.value.map { it.originalMessageId }.sorted()
    private fun save(r: DeliveryReceipt): Boolean {
        check(!failReceipt)
        val existing = receipts.value.find { it.originalMessageId == r.originalMessageId }
        require(existing == null || existing.copy(deliveredAt = r.deliveredAt) == r)
        rows[r.originalMessageId]?.let {
            require(r.matches(it))
            if (it.status != MessageStatus.RECEIVED) rows[r.originalMessageId] = it.copy(status = MessageStatus.DELIVERED)
        }
        base.messages.value = rows.values.toList()
        if (existing == null) receipts.value += r
        return existing == null
    }
    override suspend fun saveReceipt(receipt: DeliveryReceipt) = lock.withLock { save(receipt) }
    override suspend fun commitReceived(message: Message, receipt: DeliveryReceipt?, localId: String, at: Long, relayLimit: Int) = lock.withLock {
        require(receipt == null || localId == message.destinationId && receipt.createdByDeviceId == localId)
        val known = this.receipt(message.messageId)
        require(known == null || known.matches(message))
        if (known != null) false else {
            check(receipt == null || !failReceipt)
            rows[message.messageId]?.let { require(DeliveryEngine.sameBundle(it, message)) }
            if (!rows.containsKey(message.messageId) && message.sourceId != localId && message.destinationId != localId) {
                rows.entries.removeIf { (_, row) -> row.sourceId != localId && row.destinationId != localId &&
                    row.status != MessageStatus.RECEIVED && (row.expiresAt <= at || receipts.value.any { it.originalMessageId == row.messageId }) }
                if (relayBufferUsage(localId, at) >= relayLimit) throw RelayBufferFullException()
            }
            base.insert(message)
            receipt?.let { save(it) }
            true
        }
    }
    override suspend fun reserveSpray(id: String, localId: String, peerId: String, expectedCopies: Int, at: Long) = lock.withLock {
        if (receipt(id) != null) null else base.reserveSpray(id, localId, peerId, expectedCopies, at)
    }
    override suspend fun restoreSpray(id: String, expectedKept: Int, copiesGiven: Int) = lock.withLock {
        if (receipt(id) != null) false else base.restoreSpray(id, expectedKept, copiesGiven)
    }
    override suspend fun relayBufferUsage(localId: String, at: Long) = rows.values.count {
        it.sourceId != localId && it.destinationId != localId && it.status == MessageStatus.CARRIED &&
            it.expiresAt > at && receipts.value.none { r -> r.originalMessageId == it.messageId }
    }
    override suspend fun forwardableMessageIds(now: Long) = base.forwardableMessageIds(now).filterNot { it in receiptIds() }
}
