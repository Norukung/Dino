package com.group16.dinoblog.model

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class MessageStatus { PENDING, CARRIED, FORWARDED, DELIVERED, RECEIVED, EXPIRED }

@Serializable
enum class MessageContentType { TEXT, IMAGE }

@Serializable
@Entity(tableName = "messages", indices = [Index(value = ["receiverId", "status"]), Index(value = ["timestamp"])])
data class Message(
    @PrimaryKey val messageId: String,
    @ColumnInfo(name = "senderId") val sourceId: String,
    @ColumnInfo(name = "receiverId") val destinationId: String,
    val payload: String,
    @ColumnInfo(name = "timestamp") val createdAt: Long,
    /** Lifetime in seconds from timestamp (Unix milliseconds), not a hop counter. */
    val ttl: Long,
    val status: MessageStatus,
    @ColumnInfo(defaultValue = "0") val expiresAt: Long = createdAt + ttl * 1000,
    @ColumnInfo(defaultValue = "0") val hopCount: Int = 0,
    @ColumnInfo(defaultValue = "8") val hopLimit: Int = 8,
    /** Explicit first-hop custodian, not a route. Null preserves direct chat. */
    val carrierId: String? = null,
    /** Missing wire fields and migrated rows are conservative WAIT copies. */
    @ColumnInfo(defaultValue = "1") val copiesRemaining: Int = 1,
    /** Durable in-flight escrow; never sent in MESSAGE packets. ACK commits this split. */
    @Transient @ColumnInfo(defaultValue = "0") val reservedCopies: Int = 0,
    @ColumnInfo(defaultValue = "'TEXT'") val contentType: MessageContentType = MessageContentType.TEXT,
    val mediaId: String? = null,
    /** Session recovery for an unacknowledged branch; never serialized on the wire. */
    @Transient val reservedPeerId: String? = null,
) {
    // Compatibility accessors keep existing chat UI unchanged. SQLite column names
    // are retained too; the wire format uses the canonical DTN field names above.
    @get:Ignore val senderId: String get() = sourceId
    @get:Ignore val receiverId: String get() = destinationId
    @get:Ignore val timestamp: Long get() = createdAt
}
