package com.group16.dinoblog.repository

import android.net.Uri
import com.group16.dinoblog.model.MediaEntity
import com.group16.dinoblog.model.MediaManifest
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.RelayPolicy
import com.group16.dinoblog.network.MessageRejectReason
import kotlinx.coroutines.flow.Flow

data class PreparedImage(val manifest: MediaManifest, val filePath: String)

sealed interface MediaOffer {
    data class Accept(val missingChunks: List<Int>) : MediaOffer
    data object Have : MediaOffer
    data class Reject(val reason: MessageRejectReason) : MediaOffer
}

sealed interface MediaChunkResult {
    data class Progress(val completed: Int, val total: Int) : MediaChunkResult
    data class Complete(val message: Message) : MediaChunkResult
    data class Reject(val reason: MessageRejectReason) : MediaChunkResult
}

interface MediaStore {
    val media: Flow<List<MediaEntity>>
    fun filePath(mediaId: String): String
    suspend fun prepare(uri: Uri): PreparedImage
    suspend fun prepare(uri: Uri, type: MessageContentType): PreparedImage = prepare(uri)
    suspend fun discardPrepared(prepared: PreparedImage)
    suspend fun queuePrepared(prepared: PreparedImage, source: String, destination: String, at: Long,
        ttl: Long, hopLimit: Int, copies: Int, relayPolicy: RelayPolicy = RelayPolicy.ALLOW_RELAY): Message
    suspend fun manifest(messageId: String): MediaManifest?
    suspend fun dtnEligible(messageId: String): Boolean = true
    suspend fun readChunk(mediaId: String, index: Int): ByteArray?
    suspend fun offer(message: Message, manifest: MediaManifest, localId: String, at: Long, relayLimit: Int,
        authenticatedPeerId: String? = null): MediaOffer
    suspend fun receiveChunk(messageId: String, mediaId: String, index: Int, bytes: ByteArray,
        localId: String, at: Long, relayLimit: Int, authenticatedPeerId: String? = null): MediaChunkResult
    suspend fun cleanup(localId: String, at: Long): Int
    suspend fun recover(localId: String, at: Long)
    suspend fun relayBytes(localId: String, at: Long): Long
}
