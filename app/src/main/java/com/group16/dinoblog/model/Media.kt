package com.group16.dinoblog.model

import androidx.room.Entity
import androidx.room.ColumnInfo
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
    val contentType: MessageContentType = MessageContentType.IMAGE,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
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
    /** One 0/1 character per 128 KiB chunk. */
    val receivedChunks: String,
    val isComplete: Boolean,
    val createdAt: Long,
    val expiresAt: Long,
    /** Serialized MESSAGE metadata for partial-transfer recovery; never contains media bytes. */
    val messageJson: String,
    val lastUpdatedAt: Long,
    @ColumnInfo(defaultValue = "'IMAGE'") val contentType: MessageContentType = MessageContentType.IMAGE,
    @ColumnInfo(defaultValue = "NULL") val durationMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val width: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val height: Int? = null,
) {
    val completedChunks: Int get() = receivedChunks.count { it == '1' }
    fun manifest() = MediaManifest(messageId, mediaId, mimeType, originalFileName,
        fileSize, sha256, chunkSize, totalChunks, contentType, durationMs, width, height)
}
