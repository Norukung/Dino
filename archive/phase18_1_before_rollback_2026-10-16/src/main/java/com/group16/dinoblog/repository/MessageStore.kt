package com.group16.dinoblog.repository

import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.Peer
import kotlinx.coroutines.flow.Flow

interface MessageStore {
    val messages: Flow<List<Message>>
    val peers: Flow<List<Peer>>
    suspend fun insert(message: Message): Boolean
    suspend fun get(id: String): Message?
    suspend fun pending(localId: String, peerId: String): List<Message>
    suspend fun delivered(id: String, localId: String, peerId: String): Boolean
    suspend fun forwarded(id: String, localId: String, peerId: String): Boolean
    suspend fun relayDelivered(id: String, localId: String, peerId: String, previousHop: Int): Boolean
    suspend fun replicated(id: String, peerId: String, previousHop: Int): Boolean
    /** Implementations without atomic durable accounting fail closed for relay spraying. */
    suspend fun reserveSpray(id: String, localId: String, peerId: String, expectedCopies: Int, at: Long): SprayReservation? = null
    suspend fun restoreSpray(id: String, expectedKept: Int, copiesGiven: Int): Boolean = false
    /** Confirm custody and atomically commit the durable spray escrow after ACK. */
    suspend fun confirmSpray(id: String, peerId: String, previousHop: Int, kept: Int, given: Int): Boolean =
        replicated(id, peerId, previousHop)
    suspend fun relayBufferUsage(localId: String, at: Long): Int = 0
    suspend fun expire(now: Long)
    /** Expire and reclaim only transport-only relay copies; user history remains intact. */
    suspend fun cleanup(localId: String, now: Long): Int { expire(now); return 0 }
    suspend fun savePeer(peer: Peer)
}

/** Proposed retained budget and immutable copy budget offered to one relay. */
data class SprayReservation(val retained: Message, val copiesGiven: Int)

/** A definitive relay admission failure; no message was persisted by this transaction. */
class RelayBufferFullException : IllegalStateException("BUFFER_FULL")
class RelayDisabledException : IllegalStateException("RELAY_DISABLED")
