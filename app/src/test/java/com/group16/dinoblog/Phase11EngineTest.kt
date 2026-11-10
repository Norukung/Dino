package com.group16.dinoblog

import android.net.Uri
import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.dtn.DtnConfig
import com.group16.dinoblog.model.InventoryState
import com.group16.dinoblog.model.MediaEntity
import com.group16.dinoblog.model.MediaManifest
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.network.MessageTransport
import com.group16.dinoblog.network.PacketType
import com.group16.dinoblog.network.WirePacket
import com.group16.dinoblog.repository.MediaChunkResult
import com.group16.dinoblog.repository.MediaOffer
import com.group16.dinoblog.repository.MediaStore
import com.group16.dinoblog.repository.PreparedImage
import com.group16.dinoblog.util.NetworkLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class Phase11EngineTest {
    private fun id() = UUID.randomUUID().toString()
    private fun image(source: String, destination: String) = bundle(source, destination, MessageContentType.IMAGE)
    private fun bundle(source: String, destination: String, type: MessageContentType) = Message(id(), source, destination, "", 1000, 60,
        MessageStatus.PENDING, copiesRemaining = 4, contentType = type, mediaId = id())
    private class Wire(peer: String) : MessageTransport {
        override val state = MutableStateFlow(SocketState("Connected", peer, sessionId = "s"))
        override var onPacket: suspend (String, WirePacket) -> Unit = { _, _ -> }
        val sent = mutableListOf<WirePacket>()
        override suspend fun send(peerId: String, packet: WirePacket): Boolean { sent += packet; return true }
    }
    private class Media(private val message: Message, private val chunks: Int = 1) : MediaStore {
        override val media = MutableStateFlow<List<MediaEntity>>(emptyList())
        val manifest = MediaManifest(message.messageId, message.mediaId!!, when (message.contentType) {
            MessageContentType.AUDIO -> "audio/mpeg"
            MessageContentType.VIDEO -> "video/mp4"
            else -> "image/jpeg"
        }, "media.file", (chunks - 1L) * DtnConfig.IMAGE_CHUNK_BYTES + 3, "a".repeat(64), DtnConfig.IMAGE_CHUNK_BYTES, chunks,
            message.contentType, if (message.contentType == MessageContentType.IMAGE) null else 1000,
            if (message.contentType == MessageContentType.VIDEO) 320 else null,
            if (message.contentType == MessageContentType.VIDEO) 240 else null)
        var complete = false
        override fun filePath(mediaId: String) = ""
        override suspend fun prepare(uri: Uri, type: MessageContentType): PreparedImage = error("unused")
        override suspend fun discardPrepared(prepared: PreparedImage) = Unit
        override suspend fun queuePrepared(prepared: PreparedImage, source: String, destination: String,
            at: Long, ttl: Long, hopLimit: Int, copies: Int): Message = error("unused")
        override suspend fun manifest(messageId: String): MediaManifest? = manifest.takeIf { it.messageId == messageId }
        override suspend fun readChunk(mediaId: String, index: Int): ByteArray? =
            if (index == chunks - 1) byteArrayOf(1, 2, 3) else ByteArray(DtnConfig.IMAGE_CHUNK_BYTES)
        override suspend fun offer(message: Message, manifest: MediaManifest, localId: String,
            at: Long, relayLimit: Int): MediaOffer = MediaOffer.Accept(listOf(0))
        override suspend fun receiveChunk(messageId: String, mediaId: String, index: Int, bytes: ByteArray,
            localId: String, at: Long, relayLimit: Int): MediaChunkResult = if (complete)
            MediaChunkResult.Complete(message.copy(status = MessageStatus.RECEIVED)) else MediaChunkResult.Progress(0, 1)
        override suspend fun cleanup(localId: String, at: Long) = 0
        override suspend fun recover(localId: String, at: Long) = Unit
        override suspend fun relayBytes(localId: String, at: Long) = 0L
    }

    @Test fun receiverAcknowledgesOnlyAfterMediaStoreReportsComplete() = runTest {
        val source = id(); val local = id(); val message = image(source, local).copy(hopCount = 1)
        val media = Media(message); val wire = Wire(source)
        val engine = DeliveryEngine(local, Phase4TestStore(), wire, backgroundScope, NetworkLogger(),
            smartRelaySelection = false, mediaStore = media, now = { 2000 })
        engine.receive(source, WirePacket(PacketType.MEDIA_MANIFEST, message = message, manifest = media.manifest), "s")
        assertEquals(PacketType.MEDIA_ACCEPT, wire.sent.single().type)
        val chunk = WirePacket(PacketType.MEDIA_CHUNK, messageId = message.messageId, mediaId = message.mediaId,
            chunkIndex = 0, dataLength = 3, binaryData = byteArrayOf(1, 2, 3))
        engine.receive(source, chunk, "s")
        assertEquals(0, wire.sent.count { it.type == PacketType.ACK })
        media.complete = true
        engine.receive(source, chunk, "s")
        assertEquals(1, wire.sent.count { it.type == PacketType.ACK })
        assertEquals(MessageStatus.DELIVERED, wire.sent.last().ackStatus)
    }

    @Test fun senderOffersManifestAndKeepsMessagePendingUntilCompleteAck() = runTest {
        val local = id(); val destination = id(); val message = image(local, destination)
        val store = Phase4TestStore(); store.insert(message)
        val media = Media(message); val wire = Wire(destination)
        val inventory = MutableStateFlow(InventoryState(peerId = destination, sessionId = "s", sent = true,
            localReady = true, remoteReceived = true, missingOnPeer = listOf(message.messageId)))
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), inventory,
            smartRelaySelection = false, mediaStore = media, now = { 2000 })
        engine.retryPending()
        assertEquals(PacketType.MEDIA_MANIFEST, wire.sent.single().type)
        assertEquals(MessageStatus.PENDING, store.get(message.messageId)?.status)
        engine.receive(destination, WirePacket(PacketType.MEDIA_ACCEPT, messageId = message.messageId,
            mediaId = message.mediaId, missingChunks = listOf(0)), "s")
        runCurrent()
        assertEquals(1, wire.sent.count { it.type == PacketType.MEDIA_CHUNK })
        assertEquals(MessageStatus.PENDING, store.get(message.messageId)?.status)
        engine.receive(destination, WirePacket(PacketType.ACK, messageId = message.messageId,
            ackStatus = MessageStatus.DELIVERED), "s")
        assertEquals(MessageStatus.DELIVERED, store.get(message.messageId)?.status)
    }

    @Test fun audioAndVideoCarrierOfferOriginalBundleToDestination() = runTest {
        for (type in listOf(MessageContentType.AUDIO, MessageContentType.VIDEO)) {
            val source = id(); val carrier = id(); val destination = id()
            val message = bundle(source, destination, type).copy(status = MessageStatus.CARRIED, hopCount = 1)
            val store = Phase4TestStore().apply { insert(message) }
            val media = Media(message); val wire = Wire(destination)
            val inventory = MutableStateFlow(InventoryState(peerId = destination, sessionId = "s", sent = true,
                localReady = true, remoteReceived = true, missingOnPeer = listOf(message.messageId)))
            val engine = DeliveryEngine(carrier, store, wire, backgroundScope, NetworkLogger(), inventory,
                smartRelaySelection = false, mediaStore = media, now = { 2000 })
            engine.retryPending()
            val offered = wire.sent.single()
            assertEquals(PacketType.MEDIA_MANIFEST, offered.type)
            assertEquals(type, offered.message!!.contentType)
            assertEquals(source, offered.message!!.sourceId)
            assertEquals(destination, offered.message!!.destinationId)
            assertEquals(message.messageId, offered.message!!.messageId)
            assertEquals(message.mediaId, offered.manifest!!.mediaId)
            assertEquals(2, offered.message!!.hopCount)
        }
    }

    @Test fun acceptedVideoStartsAllMissingBinaryChunks() = runTest {
        val local = id(); val destination = id(); val message = bundle(local, destination, MessageContentType.VIDEO)
        val store = Phase4TestStore().apply { insert(message) }
        val media = Media(message, chunks = 3); val wire = Wire(destination)
        val inventory = MutableStateFlow(InventoryState(peerId = destination, sessionId = "s", sent = true,
            localReady = true, remoteReceived = true, missingOnPeer = listOf(message.messageId)))
        val engine = DeliveryEngine(local, store, wire, backgroundScope, NetworkLogger(), inventory,
            smartRelaySelection = false, mediaStore = media, now = { 2000 })
        engine.retryPending()
        assertEquals(PacketType.MEDIA_MANIFEST, wire.sent.single().type)
        engine.receive(destination, WirePacket(PacketType.MEDIA_ACCEPT, messageId = message.messageId,
            mediaId = message.mediaId, missingChunks = listOf(0, 1, 2)), "s")
        runCurrent()
        val sent = wire.sent.filter { it.type == PacketType.MEDIA_CHUNK }
        assertEquals(listOf(0, 1, 2), sent.map { it.chunkIndex })
        assertEquals(listOf(DtnConfig.IMAGE_CHUNK_BYTES, DtnConfig.IMAGE_CHUNK_BYTES, 3), sent.map { it.dataLength })
        assertEquals(MessageStatus.PENDING, store.get(message.messageId)?.status)
    }
}
