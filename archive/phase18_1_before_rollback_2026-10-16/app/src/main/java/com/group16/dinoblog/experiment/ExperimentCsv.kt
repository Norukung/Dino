package com.group16.dinoblog.experiment

import com.group16.dinoblog.model.ExperimentEvent
import com.group16.dinoblog.model.ExperimentMessageResult
import com.group16.dinoblog.model.ExperimentSession
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Three UTF-8 CSV files in one Storage Access Framework document. No message payload is read. */
object ExperimentCsv {
    fun writeZip(output: OutputStream, session: ExperimentSession,
        messages: List<ExperimentMessageResult>, events: List<ExperimentEvent>) {
        val summary = ExperimentAnalysis.summarize(session, messages, events)
        ZipOutputStream(output).use { zip ->
            file(zip, "experiment_summary.csv", listOf(
                listOf("experiment_id", "label", "routing_mode", "transport_mode", "device_hash", "app_version", "build_type",
                    "started_at", "ended_at", "duration_ms", "messages_created", "messages_delivered", "messages_expired",
                    "delivery_ratio", "avg_delivery_delay_ms", "median_delivery_delay_ms", "min_delivery_delay_ms",
                    "max_delivery_delay_ms", "p95_delivery_delay_ms", "total_dtn_transmissions", "total_online_transmissions",
                    "relay_transfers", "direct_deliveries", "duplicate_avoided", "replica_count", "routing_overhead_ratio",
                    "avg_hop_count", "median_hop_count", "max_hop_count", "current_buffer_bytes", "peak_buffer_bytes",
                    "partial_media_bytes", "bytes_sent", "bytes_received", "media_bytes_sent", "media_bytes_received",
                    "dtn_control_bytes_sent", "dtn_payload_bytes_sent", "online_control_bytes_sent", "online_payload_bytes_sent",
                    "failed_transfers", "rejected_transfers", "buffer_full_rejects", "resume_count", "hash_failures",
                    "partial_transfers", "bytes_retransmitted",
                    "cleanup_delivered_bytes", "cleanup_expired_bytes", "receipt_avg_delay_ms", "avg_relay_carry_ms",
                    "avg_online_upload_ms", "avg_mailbox_wait_ms",
                    "wifi_peer_connections", "bluetooth_peer_connections", "wifi_connection_failures",
                    "bluetooth_connection_failures", "auto_fallback_count", "wifi_bytes_sent", "wifi_bytes_received",
                    "bluetooth_bytes_sent", "bluetooth_bytes_received", "avg_connection_setup_ms",
                    "avg_authenticated_setup_ms", "recall_requested", "local_cancel", "revoke_propagated",
                    "revoke_confirmed", "revoke_already_delivered", "revoke_invalid_signature",
                    "revoke_pending_verification", "relay_bytes_reclaimed_by_revoke",
                    "avg_revoke_propagation_delay_ms", "node_count",
                    "message_count_target", "notes", "complete"),
                listOf(session.experimentId, session.label, session.routingMode, session.transportMode, session.deviceHash,
                    session.appVersion, session.buildType, session.startedAt, session.endedAt,
                    session.endedAt?.minus(session.startedAt), summary.messagesCreated, summary.messagesDelivered,
                    summary.messagesExpired, summary.deliveryRatio, summary.averageDelayMs, summary.medianDelayMs,
                    summary.minimumDelayMs, summary.maximumDelayMs, summary.p95DelayMs, summary.dtnTransmissions,
                    summary.onlineTransmissions, summary.relayTransfers, summary.directDeliveries, summary.duplicateAvoided,
                    summary.replicas, summary.routingOverheadRatio, summary.averageHopCount, summary.medianHopCount,
                    summary.maximumHopCount, session.currentBufferBytes, session.peakBufferBytes, session.partialMediaBytes,
                    session.bytesSent, session.bytesReceived, session.mediaBytesSent, session.mediaBytesReceived,
                    session.dtnControlBytesSent, session.dtnPayloadBytesSent, session.onlineControlBytesSent,
                    session.onlinePayloadBytesSent, summary.failedTransfers, summary.rejectedTransfers,
                    summary.bufferFullRejects, summary.resumeCount, summary.hashFailures,
                    summary.partialTransfers, summary.bytesRetransmitted, summary.cleanupDeliveredBytes,
                    summary.cleanupExpiredBytes, summary.averageReceiptDelayMs, summary.averageRelayCarryMs,
                    summary.averageOnlineUploadMs, summary.averageMailboxWaitMs,
                    summary.wifiPeerConnections, summary.bluetoothPeerConnections,
                    summary.wifiConnectionFailures, summary.bluetoothConnectionFailures,
                    summary.autoFallbackCount, summary.wifiBytesSent, summary.wifiBytesReceived,
                    summary.bluetoothBytesSent, summary.bluetoothBytesReceived,
                    summary.averageConnectionSetupDurationMs, summary.averageAuthenticatedSetupDurationMs,
                    summary.recallRequestedCount, summary.localCancelCount, summary.revokePropagatedCount,
                    summary.revokeConfirmedCount, summary.revokeAlreadyDeliveredCount,
                    summary.revokeInvalidSignatureCount, summary.revokePendingVerificationCount,
                    summary.relayBytesReclaimedByRevoke, summary.averageRevokePropagationDelayMs,
                    session.nodeCount, session.messageCountTarget, session.notes, session.endedAt != null)
            ))
            file(zip, "message_results.csv", sequence {
                yield(listOf("experiment_id", "message_id", "source_hash", "destination_hash", "content_type", "file_size_bytes",
                    "created_at", "delivered_at", "delivery_delay_ms", "receipt_received_at", "receipt_delay_ms",
                    "final_hop_count", "delivery_transport", "routing_mode", "final_status", "created_locally"))
                for (m in messages) yield(listOf(m.experimentId, m.messageId, m.sourceHash, m.destinationHash,
                    m.contentType, m.fileSizeBytes, m.createdAt, m.deliveredAt,
                    m.deliveredAt?.minus(m.createdAt), m.receiptReceivedAt,
                    if (m.receiptReceivedAt != null && m.deliveredAt != null) m.receiptReceivedAt - m.deliveredAt else null,
                    m.finalHopCount, m.deliveryTransport, m.routingMode, m.finalStatus, m.createdLocally))
            }.toList())
            file(zip, "event_log.csv", sequence {
                yield(listOf("experiment_id", "event_time", "elapsed_realtime_ms", "event_type", "message_id", "media_id",
                    "local_device_hash", "peer_device_hash", "routing_mode", "decision", "bytes", "hop_count",
                    "copies_remaining", "buffer_bytes", "content_type", "transport", "duration_ms", "result", "reason"))
                for (e in events) yield(listOf(e.experimentId, e.eventTime, e.elapsedRealtimeMs, e.eventType,
                    e.messageId, e.mediaId, e.localDeviceHash, e.peerDeviceHash, e.routingMode, e.decision,
                    e.bytes, e.hopCount, e.copiesRemaining, e.bufferBytes, e.contentType, e.transport,
                    e.durationMs, e.result, e.reason))
            }.toList())
        }
    }

    private fun file(zip: ZipOutputStream, name: String, rows: List<List<Any?>>) {
        zip.putNextEntry(ZipEntry(name))
        // BOM helps Excel identify UTF-8 Thai labels; Google Sheets also accepts it.
        zip.write(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
        for (row in rows) zip.write((row.joinToString(",") { cell(it) } + "\r\n").toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    fun cell(value: Any?): String {
        val raw = value?.toString() ?: ""
        val safe = if (value is String && raw.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'" + raw else raw
        return if (safe.any { it == ',' || it == '"' || it == '\r' || it == '\n' })
            "\"${safe.replace("\"", "\"\"")}\"" else safe
    }
}
