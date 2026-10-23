package com.group16.dinoblog.model

/** All remote advice and metrics belong to exactly one HELLO/TCP session. */
data class PredictabilityState(
    val peerId: String? = null,
    val sessionId: String? = null,
    val encounterRecorded: Boolean = false,
    val summarySent: Boolean = false,
    val summaryReceived: Boolean = false,
    val remoteEntries: List<PredictabilityEntry> = emptyList(),
    val receivedAt: Long? = null,
    val lastEncounterScore: Double? = null,
    val lastTransitiveAt: Long? = null,
    val decisions: List<SmartRoutingDecision> = emptyList(),
    val metrics: RoutingMetrics = RoutingMetrics(),
)
data class SmartRoutingDecision(val messageId: String, val destinationId: String,
    val localScore: Double, val peerScore: Double, val decision: String, val reason: String)
data class RoutingMetrics(
    val directAttempts: Int = 0, val smartAttempts: Int = 0, val smartAccepted: Int = 0,
    val poorScore: Int = 0, val insufficientMargin: Int = 0, val waits: Int = 0,
    val delivered: Int = 0, val messageTransmissions: Int = 0, val receiptTransmissions: Int = 0,
)
