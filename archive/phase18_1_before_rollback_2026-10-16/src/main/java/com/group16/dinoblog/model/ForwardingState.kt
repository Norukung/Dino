package com.group16.dinoblog.model

enum class ForwardingKind { DIRECT, SPRAY, SMART_SPRAY, WAIT, EPIDEMIC }

/** Debug-only session state. Durable custody and delivery remain in Room. */
data class ForwardingState(
    val peerId: String? = null,
    val sessionId: String? = null,
    val candidateIds: List<String> = emptyList(),
    val directIds: List<String> = emptyList(),
    val waitIds: List<String> = emptyList(),
    val rejectedIds: List<String> = emptyList(),
    val lastCopiesGiven: Int? = null,
    val lastCopiesKept: Int? = null,
    val lastRejectReason: String? = null,
    val relayIds: List<String> = emptyList(),
    val peerKnownIds: List<String> = emptyList(),
    val awaitingAckIds: List<String> = emptyList(),
    val lastSentId: String? = null,
    val lastSentKind: ForwardingKind? = null,
    val peerKnownReceiptIds: List<String> = emptyList(),
    val inFlightReceiptIds: List<String> = emptyList(),
    val lastSentReceiptId: String? = null,
    val lastReceivedReceiptId: String? = null,
    val lastAckId: String? = null,
)
