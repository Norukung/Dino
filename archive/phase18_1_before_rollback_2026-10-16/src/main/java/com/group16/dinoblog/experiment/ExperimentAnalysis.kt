package com.group16.dinoblog.experiment

import com.group16.dinoblog.model.ExperimentEvent
import com.group16.dinoblog.model.ExperimentMessageResult
import com.group16.dinoblog.model.ExperimentSession
import kotlin.math.ceil

data class ExperimentSummary(
    val messagesCreated: Int, val messagesDelivered: Int, val messagesExpired: Int,
    val deliveryRatio: Double?, val averageDelayMs: Double?, val medianDelayMs: Long?,
    val minimumDelayMs: Long?, val maximumDelayMs: Long?, val p95DelayMs: Long?,
    val dtnTransmissions: Int, val onlineTransmissions: Int, val relayTransfers: Int,
    val directDeliveries: Int, val duplicateAvoided: Int, val replicas: Int,
    val routingOverheadRatio: Double?, val averageHopCount: Double?, val medianHopCount: Double?,
    val maximumHopCount: Int?, val failedTransfers: Int, val rejectedTransfers: Int,
    val resumeCount: Int, val hashFailures: Int, val bufferFullRejects: Int,
    val partialTransfers: Int, val bytesRetransmitted: Long,
    val cleanupDeliveredBytes: Long, val cleanupExpiredBytes: Long,
    val averageReceiptDelayMs: Double?, val averageRelayCarryMs: Double?,
    val averageOnlineUploadMs: Double?, val averageMailboxWaitMs: Double?,
    val wifiPeerConnections: Int = 0, val bluetoothPeerConnections: Int = 0,
    val wifiConnectionFailures: Int = 0, val bluetoothConnectionFailures: Int = 0,
    val autoFallbackCount: Int = 0,
    val wifiBytesSent: Long = 0, val wifiBytesReceived: Long = 0,
    val bluetoothBytesSent: Long = 0, val bluetoothBytesReceived: Long = 0,
    val averageConnectionSetupDurationMs: Double? = null,
    val averageAuthenticatedSetupDurationMs: Double? = null,
    val recallRequestedCount: Int = 0, val localCancelCount: Int = 0,
    val revokePropagatedCount: Int = 0, val revokeConfirmedCount: Int = 0,
    val revokeAlreadyDeliveredCount: Int = 0, val revokeInvalidSignatureCount: Int = 0,
    val revokePendingVerificationCount: Int = 0,
    val relayBytesReclaimedByRevoke: Long = 0,
    val averageRevokePropagationDelayMs: Double? = null,
)

/** Source-created messages form the ratio denominator. A receipt is final-delivery evidence. */
object ExperimentAnalysis {
    fun summarize(session: ExperimentSession, messages: List<ExperimentMessageResult>,
        events: List<ExperimentEvent>): ExperimentSummary {
        val created = messages.filter { it.createdLocally }
        val delivered = created.filter { it.deliveredAt != null }
        val delays = delivered.mapNotNull { row -> row.deliveredAt?.let { it - row.createdAt } }
            .filter { it >= 0 }.sorted()
        val receiptDelays = delivered.mapNotNull { row ->
            if (row.receiptReceivedAt != null && row.deliveredAt != null)
                (row.receiptReceivedAt - row.deliveredAt).takeIf { it >= 0 } else null
        }
        val hops = messages.filter { it.deliveredAt != null }.mapNotNull { it.finalHopCount }.sorted()
        val successfulDtn = events.count { it.eventType == "TRANSFER_COMPLETED" && it.transport == "DTN" }
        val deliveredAtDestination = events.count { it.eventType == "MESSAGE_DELIVERED" && it.transport == "DTN" }
        val carryStarted = mutableMapOf<String, Long>()
        val carry = mutableListOf<Long>()
        for (event in events) {
            val messageId = event.messageId ?: continue
            when (event.eventType) {
                "MESSAGE_CARRIED" -> if (messageId !in carryStarted) carryStarted[messageId] = event.eventTime
                "TRANSFER_COMPLETED", "BUFFER_RECLAIM" -> if (event.eventType == "BUFFER_RECLAIM" || event.result == "RELAY") {
                    carryStarted.remove(messageId)?.let { started ->
                        (event.eventTime - started).takeIf { it >= 0 }?.let(carry::add)
                    }
                }
            }
        }
        return ExperimentSummary(created.size, delivered.size,
            created.count { it.finalStatus == "EXPIRED" },
            created.size.takeIf { it > 0 }?.let { delivered.size.toDouble() / it },
            delays.takeIf { it.isNotEmpty() }?.average(), median(delays),
            delays.firstOrNull(), delays.lastOrNull(),
            if (delays.size >= 20) percentile(delays, 0.95) else null,
            successfulDtn, events.count { it.eventType == "ONLINE_SERVER_ACCEPTED" },
            events.count { it.eventType == "TRANSFER_COMPLETED" && it.result == "RELAY" },
            events.count { it.eventType == "MESSAGE_DELIVERED" && it.result == "DIRECT_DTN" },
            events.count { it.eventType == "DUPLICATE_AVOIDED" },
            events.count { it.eventType == "TRANSFER_COMPLETED" && it.result == "RELAY" },
            deliveredAtDestination.takeIf { it > 0 }?.let { (successfulDtn - it).toDouble() / it },
            hops.takeIf { it.isNotEmpty() }?.average(),
            hops.takeIf { it.isNotEmpty() }?.let {
                if (it.size % 2 == 1) it[it.size / 2].toDouble()
                else (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0
            },
            hops.lastOrNull(),
            events.count { it.eventType == "TRANSFER_FAILED" },
            events.count { it.eventType == "TRANSFER_REJECTED" },
            events.count { it.eventType == "MEDIA_RESUME" },
            events.count { it.eventType == "HASH_FAILURE" },
            events.count { it.eventType == "TRANSFER_REJECTED" && it.reason == "BUFFER_FULL" },
            events.count { it.eventType == "TRANSFER_FAILED" && it.reason == "INTERRUPTED" },
            events.filter { it.eventType == "MEDIA_BYTES_RETRANSMITTED" }.sumOf { it.bytes ?: 0 },
            events.filter { it.eventType == "BUFFER_RECLAIM" && it.reason == "DELIVERED" }.sumOf { it.bytes ?: 0 },
            events.filter { it.eventType == "BUFFER_RECLAIM" && it.reason == "EXPIRED" }.sumOf { it.bytes ?: 0 },
            receiptDelays.takeIf { it.isNotEmpty() }?.average(),
            carry.takeIf { it.isNotEmpty() }?.average(),
            events.mapNotNull { if (it.eventType == "ONLINE_SERVER_ACCEPTED") it.durationMs else null }
                .takeIf { it.isNotEmpty() }?.average(),
            events.mapNotNull { if (it.eventType == "ONLINE_MAILBOX_WAIT") it.durationMs else null }
                .takeIf { it.isNotEmpty() }?.average()).copy(
            wifiPeerConnections = events.count { it.eventType == "PEER_CONNECTED" && it.transport == "WIFI_DIRECT" },
            bluetoothPeerConnections = events.count { it.eventType == "PEER_CONNECTED" && it.transport == "BLUETOOTH" },
            wifiConnectionFailures = events.count { it.eventType == "PEER_CONNECTION_FAILED" && it.transport == "WIFI_DIRECT" },
            bluetoothConnectionFailures = events.count { it.eventType == "PEER_CONNECTION_FAILED" && it.transport == "BLUETOOTH" },
            autoFallbackCount = events.count { it.eventType == "AUTO_FALLBACK" },
            wifiBytesSent = events.filter { it.eventType == "PEER_BYTES_SENT" && it.transport == "WIFI_DIRECT" }.sumOf { it.bytes ?: 0 },
            wifiBytesReceived = events.filter { it.eventType == "PEER_BYTES_RECEIVED" && it.transport == "WIFI_DIRECT" }.sumOf { it.bytes ?: 0 },
            bluetoothBytesSent = events.filter { it.eventType == "PEER_BYTES_SENT" && it.transport == "BLUETOOTH" }.sumOf { it.bytes ?: 0 },
            bluetoothBytesReceived = events.filter { it.eventType == "PEER_BYTES_RECEIVED" && it.transport == "BLUETOOTH" }.sumOf { it.bytes ?: 0 },
            averageConnectionSetupDurationMs = events.mapNotNull { if (it.eventType == "PEER_CONNECTION_SETUP") it.durationMs else null }
                .takeIf { it.isNotEmpty() }?.average(),
            averageAuthenticatedSetupDurationMs = events.mapNotNull { if (it.eventType == "PEER_AUTHENTICATED_SETUP") it.durationMs else null }
                .takeIf { it.isNotEmpty() }?.average(),
            recallRequestedCount = events.count { it.eventType == "RECALL_REQUESTED" },
            localCancelCount = events.count { it.eventType == "LOCAL_CANCEL" },
            revokePropagatedCount = events.count { it.eventType == "REVOKE_PROPAGATED" },
            revokeConfirmedCount = events.count { it.eventType == "REVOKE_CONFIRMED" },
            revokeAlreadyDeliveredCount = events.count { it.eventType == "REVOKE_ALREADY_DELIVERED" },
            revokeInvalidSignatureCount = events.count { it.eventType == "REVOKE_INVALID_SIGNATURE" },
            revokePendingVerificationCount = events.count { it.eventType == "REVOKE_PENDING_VERIFICATION" },
            relayBytesReclaimedByRevoke = events.filter { it.eventType == "REVOKE_RELAY_RECLAIM" }.sumOf { it.bytes ?: 0 },
            averageRevokePropagationDelayMs = events.mapNotNull {
                if (it.eventType == "REVOKE_PROPAGATION_DELAY") it.durationMs else null
            }.takeIf { it.isNotEmpty() }?.average())
    }

    private fun median(sorted: List<Long>): Long? = if (sorted.isEmpty()) null else
        if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2

    private fun percentile(sorted: List<Long>, p: Double): Long? = if (sorted.isEmpty()) null else
        sorted[(ceil(sorted.size * p).toInt() - 1).coerceIn(sorted.indices)]
}
