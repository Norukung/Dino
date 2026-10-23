package com.group16.dinoblog.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** Persistent delivery tombstone. No payload, routing hops or new logical UUID. */
@Serializable
@Entity(tableName = "delivery_receipts")
data class DeliveryReceipt(
    @PrimaryKey val originalMessageId: String,
    val sourceId: String,
    val destinationId: String,
    val deliveredAt: Long,
    val originalExpiresAt: Long,
    val createdByDeviceId: String,
) {
    fun matches(message: Message) = originalMessageId == message.messageId &&
        sourceId == message.sourceId && destinationId == message.destinationId &&
        originalExpiresAt == message.expiresAt

    companion object {
        fun create(message: Message, localId: String, at: Long): DeliveryReceipt {
            require(localId == message.destinationId) { "Only final destination may originate receipt" }
            return DeliveryReceipt(message.messageId, message.sourceId, message.destinationId,
                at, message.expiresAt, localId)
        }
    }
}
