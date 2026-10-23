package com.group16.dinoblog.dtn

import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.network.MessageTransport
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.network.WireProtocol
import com.group16.dinoblog.repository.MessageStore
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class DeliveryEngine(
    private val localId: String,
    private val store: MessageStore,
    private val transport: MessageTransport,
    private val scope: CoroutineScope,
    private val logger: NetworkLogger,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val retryLock = Mutex()
    private var started = false

    fun start() {
        if (started) return
        started = true
        transport.onPacket = ::receive
        scope.launch {
            transport.state.collectLatest { connection ->
                connection.peerId?.let { peerId ->
                    safely {
                        store.savePeer(Peer(peerId, connection.peerName ?: "Android device", now()))
                        retryPending()
                    }
                }
            }
        }
        scope.launch {
            while (isActive) {
                safely { retryPending() }
                delay(5_000)
            }
        }
    }

    suspend fun queue(receiverId: String, payload: String, ttl: Long = 86_400): Message {
        require(receiverId != localId && WireProtocol.isUuid(receiverId)) { "Choose a valid recipient Device ID" }
        val message = Message(UUID.randomUUID().toString(), localId, receiverId, payload.trim(), now(), ttl, MessageStatus.PENDING)
        WireProtocol.validate(WirePacket(PacketType.MESSAGE, message = message))
        check(store.insert(message)) { "Could not store message" }
        logger.log("DB", "Queued durably", message.messageId, ttl, "PENDING")
        // Saving to Room completes before networking; process death cannot lose an unsent message.
        scope.launch { safely { retryPending() } }
        return message
    }

    suspend fun retryPending() = retryLock.withLock {
        store.expire(now())
        val peerId = transport.state.value.peerId ?: return@withLock
        for (message in store.pending(localId, peerId)) {
            if (isExpired(message, now())) { store.expire(now()); continue }
            // A successful TCP write is not proof of storage on B. Keep PENDING until ACK.
            // Retries intentionally retain the same UUID, even after app restart.
            if (!transport.send(peerId, WirePacket(PacketType.MESSAGE, message = message))) break
        }
    }

    suspend fun receive(peerId: String, packet: WirePacket) {
        WireProtocol.validate(packet)
        when (packet.type) {
            PacketType.MESSAGE -> {
                val message = requireNotNull(packet.message)
                require(message.senderId == peerId && message.receiverId == localId) { "Message is not for this direct connection" }
                val existing = store.get(message.messageId)
                if (existing != null) {
                    require(existing.copy(status = MessageStatus.PENDING) == message) { "UUID collision with different content" }
                    // A lost ACK causes a valid duplicate. ACK it again, even after TTL,
                    // because this exact message was already committed before expiration.
                    logger.log("DB", "Duplicate suppressed; re-ACK", message.messageId, message.ttl, existing.status.name)
                } else {
                    if (isExpired(message, now()) || message.timestamp > now() + 300_000) {
                        logger.log("DROP", "Expired or clock skew exceeds 5 minutes", message.messageId, message.ttl, "EXPIRED")
                        return
                    }
                    val inserted = store.insert(message.copy(status = MessageStatus.RECEIVED))
                    if (!inserted) {
                        val saved = store.get(message.messageId)
                        require(saved?.copy(status = MessageStatus.PENDING) == message) { "Conflicting duplicate" }
                    }
                    logger.log("DB", "Message committed", message.messageId, message.ttl, "RECEIVED")
                }
                // Never acknowledge until the Room insert has returned successfully.
                // If storage fails, no ACK is sent and A can safely retry later.
                transport.send(peerId, WirePacket(PacketType.ACK, messageId = message.messageId))
            }
            PacketType.ACK -> {
                val id = requireNotNull(packet.messageId)
                if (store.delivered(id, localId, peerId)) {
                    logger.log("DB", "ACK confirmed durable delivery", id, status = "DELIVERED")
                } else logger.log("DROP", "Unknown, duplicate, or wrong-peer ACK", id)
            }
            else -> Unit
        }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { logger.log("ERROR", "Delivery: ${e.message}") }
    }

    companion object {
        fun isExpired(message: Message, at: Long): Boolean = at >= message.timestamp + message.ttl * 1000
    }
}
