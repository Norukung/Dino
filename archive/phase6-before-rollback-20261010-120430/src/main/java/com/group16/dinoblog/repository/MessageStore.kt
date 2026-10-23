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
    suspend fun expire(now: Long)
    suspend fun savePeer(peer: Peer)
}
