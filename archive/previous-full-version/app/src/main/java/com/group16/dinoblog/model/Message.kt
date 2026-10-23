package com.group16.dinoblog.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class MessageStatus { PENDING, DELIVERED, RECEIVED, EXPIRED }

@Serializable
@Entity(tableName = "messages", indices = [Index(value = ["receiverId", "status"]), Index(value = ["timestamp"])])
data class Message(
    @PrimaryKey val messageId: String,
    val senderId: String,
    val receiverId: String,
    val payload: String,
    val timestamp: Long,
    /** Lifetime in seconds from timestamp (Unix milliseconds), not a hop counter. */
    val ttl: Long,
    val status: MessageStatus,
)
