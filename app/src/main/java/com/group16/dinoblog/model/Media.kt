package com.group16.dinoblog.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
data class MediaManifest(
    val messageId: String,
    val mediaId: String,
    val mimeType: String,
    val originalFileName: String,
    val fileSize: Long,
    val sha256: String,
    val chunkSize: Int,
    val totalChunks: Int,
)

/** File bytes live in files/dinoblog_media; this row is metadata and resume state only. */
@Entity(tableName = "media", indices = [Index(value = ["messageId"], unique = true)])
data class MediaEntity(
    @PrimaryKey val mediaId: String,
    val messageId: String,
    val sourceId: String,
    val destinationId: String,
    val mimeType: String,
    val originalFileName: String,
    val fileSize: Long,
    val sha256: String,
    val chunkSize: Int,
    val totalChunks: Int,
    /** One 0/1 character per chunk, at most 40 for the Phase 11 limits. */
    val receivedChunks: String,
    val isComplete: Boolean,
    val createdAt: Long,
    val expiresAt: Long,
    /** Serialized MESSAGE metadata for partial-transfer recovery; never contains image bytes. */
    val messageJson: String,
    val lastUpdatedAt: Long,
) {
    val completedChunks: Int get() = receivedChunks.count { it == '1' }
    fun manifest() = MediaManifest(messageId, mediaId, mimeType, originalFileName,
        fileSize, sha256, chunkSize, totalChunks)
}
