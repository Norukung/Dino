package com.group16.dinoblog.repository

import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.database.InventoryCandidate
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.network.WireProtocol
import kotlinx.coroutines.flow.combine
import androidx.room.withTransaction
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.dtn.DeliveryEngine

class MessageRepository(private val db: DinoblogDatabase) : MessageStore, InventoryStore, ReceiptStore {
    override val messages = db.messages().observeAll()
    override val peers = db.peers().observeAll()
    override suspend fun insert(message: Message) = db.messages().insert(message) != -1L
    override suspend fun get(id: String) = db.messages().get(id)
    override suspend fun pending(localId: String, peerId: String) = db.messages().pending(localId, peerId)
    override suspend fun delivered(id: String, localId: String, peerId: String) =
        db.messages().markDelivered(id, localId, peerId) > 0
    override suspend fun forwarded(id: String, localId: String, peerId: String) =
        db.messages().markForwarded(id, localId, peerId) > 0
    override suspend fun relayDelivered(id: String, localId: String, peerId: String, previousHop: Int) =
        db.messages().markRelayDelivered(id, localId, peerId, previousHop) > 0
    override suspend fun replicated(id: String, peerId: String, previousHop: Int) =
        db.messages().markReplicated(id, peerId, previousHop) > 0
    override val receipts = db.receipts().observeAll()
    override val inventoryChanges = combine(messages, receipts) { _, _ -> Unit }
    override suspend fun receipt(id: String) = db.receipts().get(id)
    override suspend fun receiptForMessage(id: String) = db.receipts().forMessage(id)
    override suspend fun activeReceiptIds(now: Long) = db.receipts().activeIds(now)

    override suspend fun saveReceipt(receipt: DeliveryReceipt): Boolean = db.withTransaction {
        val existing = db.receipts().forMessage(receipt.originalMessageId)
        require(existing == null || existing.sameReceipt(receipt)) { "Conflicting delivery receipt" }
        val message = db.messages().get(receipt.originalMessageId)
        require(message == null || receipt.matches(message)) { "Receipt does not match stored message" }
        if (existing != null) return@withTransaction false
        check(db.receipts().insert(receipt) != -1L) { "Receipt ID collision" }
        db.messages().markDeliveryKnown(receipt.originalMessageId)
        true
    }

    override suspend fun commitReceived(message: Message, finalReceipt: DeliveryReceipt?): MessageAdmission = db.withTransaction {
        val known = db.receipts().forMessage(message.messageId)
        require(known == null || known.matches(message)) { "Receipt/message identity conflict" }
        val existing = db.messages().get(message.messageId)
        require(existing == null || DeliveryEngine.sameBundle(existing, message)) { "Conflicting duplicate" }
        if (known != null && existing == null) return@withTransaction MessageAdmission.SUPPRESSED
        if (existing == null) check(db.messages().insert(message) != -1L)
        if (finalReceipt != null && known == null) {
            require(finalReceipt.matches(message))
            check(db.receipts().insert(finalReceipt) != -1L)
        }
        if (existing == null) MessageAdmission.INSERTED else MessageAdmission.DUPLICATE
    }
    override suspend fun receivedMessageIds(now: Long) = validatedIds(db.messages().inventoryCandidates(now, includeKnown = true))
    override suspend fun expire(now: Long) { db.messages().expire(now) }
    override suspend fun savePeer(peer: Peer) = db.peers().save(peer)
    override suspend fun forwardableMessageIds(now: Long): List<String> =
        validatedIds(db.messages().inventoryCandidates(now))
    private fun validatedIds(candidates: List<InventoryCandidate>): List<String> = candidates.filter {
            WireProtocol.isUuid(it.messageId) && WireProtocol.isUuid(it.sourceId) &&
                WireProtocol.isUuid(it.destinationId) && it.sourceId != it.destinationId &&
                (it.carrierId == null || (WireProtocol.isUuid(it.carrierId) &&
                    it.carrierId != it.sourceId && it.carrierId != it.destinationId))
        }.map { it.messageId }
}
