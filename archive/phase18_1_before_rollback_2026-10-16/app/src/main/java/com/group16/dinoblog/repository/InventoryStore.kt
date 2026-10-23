package com.group16.dinoblog.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Read-only boundary used by inventory planning; no insert or send-message API. */
fun interface InventoryStore {
    suspend fun forwardableMessageIds(now: Long): List<String>
    val inventoryChanges: Flow<Unit> get() = emptyFlow()
    // Stored-copy knowledge includes exhausted carriers and final received messages.
    // These IDs are not necessarily offers to forward.
    suspend fun receivedMessageIds(now: Long): List<String> = emptyList()
}
