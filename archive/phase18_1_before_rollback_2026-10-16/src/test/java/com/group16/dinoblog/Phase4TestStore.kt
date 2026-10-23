package com.group16.dinoblog

import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.repository.SprayReservation
import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.model.ForwardingKind
import com.group16.dinoblog.repository.MessageStore
import com.group16.dinoblog.repository.InventoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/** Deterministic durable-store stand-in; SQLite guards are also covered by instrumentation. */
internal class Phase4TestStore : MessageStore, InventoryStore {
    val rows = ConcurrentHashMap<String, Message>()
    override val messages = MutableStateFlow<List<Message>>(emptyList())
    override val peers = MutableStateFlow<List<Peer>>(emptyList())
    override val inventoryChanges = messages.map { Unit }
    var beforeGet: suspend () -> Unit = {}
    var failInsert = false
    override suspend fun insert(message: Message): Boolean {
        check(!failInsert) { "Storage unavailable" }
        val inserted = rows.putIfAbsent(message.messageId, message) == null
        messages.value = rows.values.toList()
        return inserted
    }
    override suspend fun get(id: String): Message? { beforeGet(); return rows[id] }
    override suspend fun pending(localId: String, peerId: String) = rows.values.filter {
        it.sourceId == localId && (it.carrierId ?: it.destinationId) == peerId && it.status == MessageStatus.PENDING
    }
    private fun change(id: String, predicate: (Message) -> Boolean, status: MessageStatus): Boolean {
        var changed = false
        rows.computeIfPresent(id) { _, old ->
            if (predicate(old)) { changed = true; old.copy(status = status) } else old
        }
        messages.value = rows.values.toList()
        return changed
    }
    override suspend fun delivered(id: String, localId: String, peerId: String) = change(id, {
        it.sourceId == localId && it.destinationId == peerId && it.carrierId == null && it.hopCount < it.hopLimit &&
            it.status in listOf(MessageStatus.PENDING, MessageStatus.EXPIRED)
    }, MessageStatus.DELIVERED)
    override suspend fun forwarded(id: String, localId: String, peerId: String) = change(id, {
        it.sourceId == localId && it.carrierId == peerId && it.destinationId != peerId && it.hopCount < it.hopLimit &&
            it.status in listOf(MessageStatus.PENDING, MessageStatus.EXPIRED)
    }, MessageStatus.FORWARDED)
    override suspend fun relayDelivered(id: String, localId: String, peerId: String, previousHop: Int) = change(id, {
        it.destinationId == peerId && it.destinationId != localId && it.hopCount == previousHop && it.hopCount < it.hopLimit &&
            it.status in listOf(MessageStatus.PENDING, MessageStatus.CARRIED, MessageStatus.FORWARDED, MessageStatus.EXPIRED)
    }, MessageStatus.DELIVERED)
    override suspend fun replicated(id: String, peerId: String, previousHop: Int): Boolean {
        val row = rows[id] ?: return false
        return change(id, { it.destinationId != peerId && it.hopCount == previousHop && it.hopCount < it.hopLimit &&
            it.status in listOf(MessageStatus.PENDING, MessageStatus.CARRIED, MessageStatus.FORWARDED, MessageStatus.EXPIRED) },
            if (row.status == MessageStatus.PENDING) MessageStatus.FORWARDED else row.status)
    }
    override suspend fun reserveSpray(id: String, localId: String, peerId: String, expectedCopies: Int, at: Long): SprayReservation? {
        var reserved: SprayReservation? = null
        rows.computeIfPresent(id) { _, row ->
            if (row.copiesRemaining != expectedCopies || DeliveryEngine.classify(row, localId, peerId, at) != ForwardingKind.SPRAY) row
            else {
                val give = row.copiesRemaining / 2
                val kept = row.copy(copiesRemaining = row.copiesRemaining - give)
                reserved = SprayReservation(kept, give)
                kept
            }
        }
        messages.value = rows.values.toList()
        return reserved
    }
    override suspend fun restoreSpray(id: String, expectedKept: Int, copiesGiven: Int): Boolean {
        var restored = false
        rows.computeIfPresent(id) { _, row ->
            if (row.copiesRemaining == expectedKept && row.status in listOf(MessageStatus.PENDING, MessageStatus.CARRIED, MessageStatus.FORWARDED)) {
                restored = true; row.copy(copiesRemaining = expectedKept + copiesGiven)
            } else row
        }
        messages.value = rows.values.toList()
        return restored
    }
    override suspend fun expire(now: Long) {
        rows.replaceAll { _, row ->
            if (row.status in listOf(MessageStatus.PENDING, MessageStatus.CARRIED, MessageStatus.FORWARDED) && now >= row.expiresAt)
                row.copy(status = MessageStatus.EXPIRED) else row
        }
        messages.value = rows.values.toList()
    }
    override suspend fun savePeer(peer: Peer) { peers.value = peers.value.filterNot { it.deviceId == peer.deviceId } + peer }
    override suspend fun forwardableMessageIds(now: Long) = rows.values.filter {
        it.status in listOf(MessageStatus.PENDING, MessageStatus.CARRIED, MessageStatus.FORWARDED) &&
            it.expiresAt > now && it.hopCount >= 0 && it.hopCount < it.hopLimit
    }.map { it.messageId }.sorted()
    override suspend fun receivedMessageIds(now: Long) = rows.values.filter {
        it.status in listOf(MessageStatus.CARRIED, MessageStatus.FORWARDED, MessageStatus.RECEIVED, MessageStatus.DELIVERED) && it.expiresAt > now
    }.map { it.messageId }.sorted()
}
