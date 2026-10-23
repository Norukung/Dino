package com.group16.dinoblog.model

/** Session-local planning snapshot. It never changes a Room message's lifecycle. */
data class InventoryState(
    val peerId: String? = null,
    val sessionId: String? = null,
    val localMessageIds: List<String> = emptyList(),
    val localReady: Boolean = false,
    val builtAt: Long? = null,
    val remoteMessageIds: List<String> = emptyList(),
    val remoteReceived: Boolean = false,
    val missingOnPeer: List<String> = emptyList(),
    val missingLocally: List<String> = emptyList(),
    val sent: Boolean = false,
    val error: String? = null,
    val localReceiptIds: List<String> = emptyList(),
    val remoteReceiptIds: List<String> = emptyList(),
    val receiptMissingOnPeer: List<String> = emptyList(),
    val receiptMissingLocally: List<String> = emptyList(),
    val peerSupportsReceipts: Boolean = false,
)
