package com.group16.dinoblog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.group16.dinoblog.network.WireProtocol
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.group16.dinoblog.model.NetworkState
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MediaEntity
import com.group16.dinoblog.dtn.DtnConfig
import com.group16.dinoblog.dtn.DeliveryEngine
import com.group16.dinoblog.model.DeliveryPredictability
import com.group16.dinoblog.model.PredictabilityState
import com.group16.dinoblog.dtn.PredictabilityPolicy
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.model.InventoryState
import com.group16.dinoblog.model.ForwardingState
import com.group16.dinoblog.util.PacketLog

@Composable
fun NetworkDebugScreen(localId: String, localRelayEnabled: Boolean, network: NetworkState, socket: SocketState,
    logs: List<PacketLog>, messages: List<Message>, media: List<MediaEntity>, inventory: InventoryState, forwarding: ForwardingState,
    receipts: List<DeliveryReceipt>, routing: PredictabilityState, predictabilities: List<DeliveryPredictability>,
    onClear: () -> Unit, onRecipient: (String) -> Unit, onChat: () -> Unit, onRetry: () -> Unit) {
    var manualRecipient by remember { mutableStateOf("") }
    val currentInventory = socket.connected && socket.sessionId != null &&
        inventory.sessionId == socket.sessionId && inventory.peerId == socket.peerId
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Network debug", style = MaterialTheme.typography.headlineMedium) }
        item { InfoCard("Developer actions") {
            OutlinedTextField(value = manualRecipient, onValueChange = { manualRecipient = it.trim() },
                label = { Text("Destination Device ID") }, singleLine = true)
            TextButton(onClick = { onRecipient(manualRecipient); onChat() }, enabled = WireProtocol.isUuid(manualRecipient) && manualRecipient != localId) {
                Text("Open chat with Device ID")
            }
            TextButton(onClick = onRetry) { Text("Retry pending messages") }
        } }
        item { InfoCard("Direct chat connection") {
            Detail("Local Device ID", localId)
            Detail("Wi-Fi Direct", "${network.wifiStatus}; discovering: ${network.discovering}")
            Detail("Role", if (!network.groupFormed) "No group" else if (network.isGroupOwner) "Group owner / TCP server" else "Group client / TCP client")
            Detail("TCP status", socket.status)
            Detail("Connection", if (socket.connected) "CONNECTED" else if (socket.awaitingHello) "TCP CONNECTED / WAITING FOR HELLO" else "DISCONNECTED")
            Detail("Connected Peer Device ID", socket.peerIdentityLabel)
            Detail("DinoBlog Display Name", socket.peerName ?: "Unknown")
            Detail("Full Device Name", network.peers.find { it.address == network.connectedDeviceAddress }?.name ?: "Device name unavailable")
            Detail("Transport", "Wi-Fi Direct")
            Detail("Local relay enabled", localRelayEnabled.toString())
            Detail("Connected peer relay enabled", socket.peerRelayEnabled?.toString() ?: "Unknown")
            Detail("Group owner IP", network.groupOwnerIp ?: "—")
            Detail("Local / remote IP", "${socket.localIp ?: "—"} / ${socket.remoteIp ?: "—"}")
            Detail("TCP port", "8988")
            Detail("Peers", network.peers.joinToString("\n") { "${it.name} · ${it.address} · ${it.status}" }.ifEmpty { "None" })
            network.error?.let { Detail("Wi-Fi error", it) }
        } }
        item { InfoCard("DTN inventory") {
            Detail("Local DTN Inventory (snapshot)", if (inventory.localReady)
                inventory.localMessageIds.joinToString("\n").ifEmpty { "Empty" } else "Waiting for HELLO / Room...")
            Detail("Remote Inventory", when {
                !socket.connected -> "None"
                !currentInventory || !inventory.remoteReceived -> "Waiting..."
                else -> inventory.remoteMessageIds.joinToString("\n").ifEmpty { "Empty" }
            })
            val compared = currentInventory && inventory.remoteReceived && inventory.localReady
            Detail("Missing on Peer", if (compared) inventory.missingOnPeer.joinToString("\n").ifEmpty { "Empty" }
                else if (socket.connected) "Waiting..." else "None")
            Detail("Missing Locally (inventory)", if (compared) inventory.missingLocally.joinToString("\n").ifEmpty { "Empty" }
                else if (socket.connected) "Waiting..." else "None")
            Detail("Known delivered IDs", receipts.joinToString("\n") { it.originalMessageId }.ifEmpty { "Empty" })
            Detail("Known delivery count", receipts.size.toString())
            Detail("Remote delivered IDs", inventory.remoteDeliveredMessageIds.joinToString("\n").ifEmpty { "Empty" })
            Detail("Missing receipts on peer", inventory.missingReceiptsOnPeer.joinToString("\n").ifEmpty { "Empty" })
            Detail("Awaiting receipt metadata", inventory.missingReceiptsLocally.joinToString("\n").ifEmpty { "Empty" })
            Detail("Receipt in flight", forwarding.inFlightReceiptIds.joinToString("\n").ifEmpty { "Empty" })
            Detail("Last sent / received receipt", "${forwarding.lastSentReceiptId ?: "None"} / ${forwarding.lastReceivedReceiptId ?: "None"}")
            inventory.builtAt?.let { Detail("Local snapshot built", timeLabel(it)) }
            if (currentInventory) inventory.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }
        item { InfoCard("Delivery predictability") {
            Detail("Encounter recorded / score", "${routing.encounterRecorded} / ${routing.lastEncounterScore ?: "None"}")
            Detail("Routing summary sent / received", "${routing.summarySent} / ${routing.summaryReceived}")
            Detail("Forwarding margin", DtnConfig.FORWARDING_MARGIN.toString())
            Detail("Local scores (up to 64 of ${predictabilities.size})", predictabilities.take(64).joinToString("\n") {
                "${it.destinationDeviceId}: ${PredictabilityPolicy.age(it.predictability, it.lastUpdatedAt, System.currentTimeMillis())}" }.ifEmpty { "Empty" })
            Detail("Remote routing summary", routing.remoteEntries.joinToString("\n") {
                "${it.deviceId}: ${PredictabilityPolicy.age(it.predictability, routing.receivedAt ?: System.currentTimeMillis(), System.currentTimeMillis())}" }.ifEmpty { "None / awaiting summary" })
            routing.lastTransitiveAt?.let { Detail("Last transitive update", timeLabel(it)) }
            val m = routing.metrics
            Detail("Session DIRECT / SMART attempts / accepted", "${m.directAttempts} / ${m.smartAttempts} / ${m.smartAccepted}")
            Detail("Poor score / small margin / WAIT decisions", "${m.poorScore} / ${m.insufficientMargin} / ${m.waits}")
            Detail("Delivered / MESSAGE writes / receipt writes", "${m.delivered} / ${m.messageTransmissions} / ${m.receiptTransmissions}")
        } }
        item { InfoCard("Smart Binary Spray-and-Wait - direct first") {
            val active = socket.connected && socket.sessionId != null && forwarding.sessionId == socket.sessionId && forwarding.peerId == socket.peerId
            Detail("Forward candidates", if (active) forwarding.candidateIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("DIRECT candidates", if (active) forwarding.directIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("Relay candidates (SPRAY / SMART_SPRAY)", if (active) forwarding.relayIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("WAIT IDs", forwarding.waitIds.joinToString("\n").ifEmpty { "Empty" })
            Detail("Initial spray budget", DtnConfig.INITIAL_SPRAY_COPIES.toString())
            val usage = messages.count { it.sourceId != localId && it.destinationId != localId &&
                it.status == com.group16.dinoblog.model.MessageStatus.CARRIED && it.expiresAt > System.currentTimeMillis() &&
                receipts.none { r -> r.originalMessageId == it.messageId } }
            Detail("Bundles carried for other people", "$usage / ${DtnConfig.MAX_RELAY_BUFFER_MESSAGES}")
            Detail("Relay acceptance", if (localRelayEnabled) "ENABLED" else "DISABLED")
            Detail("Peer rejected IDs", forwarding.rejectedIds.joinToString("\n").ifEmpty { "Empty" })
            Detail("Last rejection", forwarding.lastRejectReason ?: "None")
            Detail("Last spray given / kept", "${forwarding.lastCopiesGiven ?: "None"} / ${forwarding.lastCopiesKept ?: "None"}")
            Detail("Peer known IDs (this session)", if (active) forwarding.peerKnownIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("Awaiting ACK", if (active) forwarding.awaitingAckIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("Last forwarded message", if (active) forwarding.lastSentId ?: "None" else "None")
            Detail("Last forwarding type", if (active) forwarding.lastSentKind?.name ?: "None" else "None")
            Detail("Last ACK", if (active) forwarding.lastAckId ?: "None" else "None")
            Text("DIRECT delivers even with one copy. The source can deposit its first bounded relay copy with a willing peer; later SMART_SPRAY uses the current destination score. WAIT preserves the budget. Scores never prove delivery.", style = MaterialTheme.typography.bodySmall)
        } }
        item { InfoCard("DTN media") {
            val relayBytes = media.filter { it.sourceId != localId && it.destinationId != localId }
                .sumOf { it.fileSize }
            Detail("Relay media allocation", "$relayBytes / ${DtnConfig.MAX_RELAY_MEDIA_BYTES} bytes")
            Detail("Complete / partial", "${media.count { it.isComplete }} / ${media.count { !it.isComplete }}")
            Detail("Chunk size", "${DtnConfig.IMAGE_CHUNK_BYTES} bytes")
            Detail("Image / audio / video limits", "${DtnConfig.MAX_IMAGE_BYTES} / ${DtnConfig.MAX_AUDIO_BYTES} / ${DtnConfig.MAX_VIDEO_BYTES} bytes")
            Detail("Last media event", logs.lastOrNull { it.detail.contains("MEDIA") }?.let { "${it.direction}: ${it.detail}" } ?: "None")
            Detail("Last media rejection", logs.lastOrNull { it.detail.contains("MEDIA") && (it.detail.contains("REJECT") || it.detail.contains("FAILED")) }?.detail ?: "None")
        } }
        items(media, key = { "media-${it.mediaId}" }) { row ->
            InfoCard(if (row.isComplete) "Verified ${row.contentType}" else "Partial ${row.contentType}") {
                Detail("Message / media ID", "${row.messageId} / ${row.mediaId}")
                Detail("Content type", row.contentType.name)
                Detail("MIME / size", "${row.mimeType} / ${row.fileSize} bytes")
                Detail("SHA-256", row.sha256)
                Detail("Chunks", "${row.completedChunks} / ${row.totalChunks} at ${row.chunkSize} bytes")
                Detail("Hash verified", row.isComplete.toString())
                Detail("Transfer peer / direction", "${socket.peerName ?: "Unknown"} / ${if (row.sourceId == localId) "Outgoing" else "Incoming"}")
                Detail("Resume chunks remaining", (row.totalChunks - row.completedChunks).toString())
                Detail("Progress", "${row.completedChunks * 100 / row.totalChunks}%")
            }
        }
        item {
            Text("Session log · ${logs.size}/250", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClear) { Text("Clear logs") }
        }
        items(logs) { log ->
            InfoCard("${timeLabel(log.time)} · ${log.direction}") {
                Text(log.detail)
                log.messageId?.let { Detail("Message ID", it) }
                log.ttl?.let { Text("TTL: ${it}s") }
                log.status?.let { Text("Status: $it") }
            }
        }
        item { Text("Stored messages · ${messages.size}", style = MaterialTheme.typography.titleLarge) }
        items(messages.reversed(), key = { it.messageId }) { message ->
            InfoCard(message.status.name) {
                Detail("Message ID", message.messageId)
                Detail("Suppressed by delivery receipt", (receipts.any { it.originalMessageId == message.messageId } ||
                    message.messageId in inventory.remoteDeliveredMessageIds).toString())
                Detail("sourceId", message.sourceId)
                Detail("destinationId", message.destinationId)
                Detail("Carrier", message.carrierId ?: "Direct")
                Detail("copiesRemaining", message.copiesRemaining.toString())
                val mode = socket.peerId?.let { DeliveryEngine.classify(message, localId, it, System.currentTimeMillis())?.name ?: "SKIP" }
                    ?: if (message.copiesRemaining == 1) "WAIT" else "SPRAY"
                val scored = routing.decisions.find { it.messageId == message.messageId }
                Detail("Local / peer destination scores used", "${scored?.localScore ?: "Unknown"} / ${scored?.peerScore ?: "Unknown"}")
                Detail("Smart routing decision", "${scored?.decision ?: "Not evaluated"}: ${scored?.reason ?: "Awaiting current session"}")
                Detail("Routing mode", if (receipts.any { it.originalMessageId == message.messageId }) "SUPPRESSED" else mode)
                Detail("hopCount / hopLimit", "${message.hopCount} / ${message.hopLimit}")
                Detail("createdAt / expiresAt", "${message.createdAt} / ${message.expiresAt} (Unix ms)")
                Detail("Sender → receiver", "${message.senderId}\n→ ${message.receiverId}")
                Detail("TTL / timestamp", "${message.ttl}s / ${message.timestamp} (Unix ms)")
                if (message.contentType != com.group16.dinoblog.model.MessageContentType.TEXT)
                    Detail("Content", "${message.contentType} / ${message.mediaId}") else Text(message.payload)
            }
        }
    }
}
