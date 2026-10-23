package com.group16.dinoblog.repository

import com.group16.dinoblog.database.DinoblogDatabase
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.Peer

class MessageRepository(private val db: DinoblogDatabase) : MessageStore {
    override val messages = db.messages().observeAll()
    override val peers = db.peers().observeAll()
    override suspend fun insert(message: Message) = db.messages().insert(message) != -1L
    override suspend fun get(id: String) = db.messages().get(id)
    override suspend fun pending(localId: String, peerId: String) = db.messages().pending(localId, peerId)
    override suspend fun delivered(id: String, localId: String, peerId: String) =
        db.messages().markDelivered(id, localId, peerId) > 0
    override suspend fun expire(now: Long) { db.messages().expire(now) }
    override suspend fun savePeer(peer: Peer) = db.peers().save(peer)
}
