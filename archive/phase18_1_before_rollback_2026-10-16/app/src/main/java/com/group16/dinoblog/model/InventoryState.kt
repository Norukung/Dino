package com.group16.dinoblog.model

/** Session-local planning snapshot. It never changes a Room message's lifecycle. */
data class InventoryState(
    val peerId: String? = null,
    val sessionId: String? = null,
    val localMessageIds: List<String> = emptyList(),
    val localDeliveredMessageIds: List<String> = emptyList(),
    val localRevokeMessageIds: List<String> = emptyList(),
    val remoteRevokeMessageIds: List<String> = emptyList(),
    val missingRevokesOnPeer: List<String> = emptyList(),
    val missingRevokesLocally: List<String> = emptyList(),
    val localRevokeResultMessageIds: List<String> = emptyList(),
    val remoteRevokeResultMessageIds: List<String> = emptyList(),
    val missingRevokeResultsOnPeer: List<String> = emptyList(),
    val missingRevokeResultsLocally: List<String> = emptyList(),
    val remoteDeliveredMessageIds: List<String> = emptyList(),
    val missingReceiptsOnPeer: List<String> = emptyList(),
    val missingReceiptsLocally: List<String> = emptyList(),
    val localReady: Boolean = false,
    val builtAt: Long? = null,
    val remoteMessageIds: List<String> = emptyList(),
    val remoteReceived: Boolean = false,
    val missingOnPeer: List<String> = emptyList(),
    val missingLocally: List<String> = emptyList(),
    val sent: Boolean = false,
    val error: String? = null,
)
