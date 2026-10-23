package com.group16.dinoblog.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
@Entity(tableName = "delivery_receipts", indices = [Index(value = ["originalMessageId"], unique = true)])
data class DeliveryReceipt(
    @PrimaryKey val receiptId: String,
    val originalMessageId: String,
    val originalSourceId: String,
    val originalDestinationId: String,
    val deliveredAt: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val hopCount: Int = 0,
    val hopLimit: Int = 10,
) {
    fun matches(message: Message) = originalMessageId == message.messageId &&
        originalSourceId == message.sourceId && originalDestinationId == message.destinationId

    fun sameReceipt(other: DeliveryReceipt) = copy(hopCount = other.hopCount) == other

    companion object {
        const val DEFAULT_LIFETIME_MILLIS = 7 * 24 * 60 * 60 * 1000L
        const val MAX_LIFETIME_MILLIS = 30 * 24 * 60 * 60 * 1000L
        fun idFor(messageId: String): String = UUID.nameUUIDFromBytes(
            "dinoblog:delivery-receipt:v1:$messageId".toByteArray(Charsets.UTF_8)).toString()
        fun create(message: Message, at: Long, lifetime: Long = DEFAULT_LIFETIME_MILLIS, hopLimit: Int = 10) =
            DeliveryReceipt(idFor(message.messageId), message.messageId, message.sourceId,
                message.destinationId, at, at, Math.addExact(at, lifetime), hopLimit = hopLimit)
    }
}
