package com.group16.dinoblog.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Transport bookkeeping, separate from the DTN bundle and final delivery status. */
@Entity(tableName = "online_message_state")
data class OnlineMessageState(
    @PrimaryKey val messageId: String,
    val state: String,
    val serverAcceptedAt: Long? = null,
)
