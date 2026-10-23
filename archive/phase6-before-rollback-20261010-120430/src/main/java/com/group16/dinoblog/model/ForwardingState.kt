package com.group16.dinoblog.model

enum class ForwardingKind { DIRECT, RELAY }

/** Debug-only session state. Durable custody and delivery remain in Room. */
data class ForwardingState(
    val peerId: String? = null,
    val sessionId: String? = null,
    val candidateIds: List<String> = emptyList(),
    val directIds: List<String> = emptyList(),
    val relayIds: List<String> = emptyList(),
    val peerKnownIds: List<String> = emptyList(),
    val awaitingAckIds: List<String> = emptyList(),
    val lastSentId: String? = null,
    val lastSentKind: ForwardingKind? = null,
    val lastAckId: String? = null,
    val receiptPeerKnownIds: List<String> = emptyList(),
    val receiptAwaitingAckIds: List<String> = emptyList(),
    val lastReceiptSentId: String? = null,
    val lastReceiptSentKind: ForwardingKind? = null,
    val lastReceiptReceivedId: String? = null,
)
