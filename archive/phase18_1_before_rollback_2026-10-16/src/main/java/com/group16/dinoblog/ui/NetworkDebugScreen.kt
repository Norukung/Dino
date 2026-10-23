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
import com.group16.dinoblog.model.MessageRecall
import com.group16.dinoblog.model.Revoke
import com.group16.dinoblog.model.RevokeResult
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
import com.group16.dinoblog.online.ServerState
import com.group16.dinoblog.model.OnlineMessageState
import com.group16.dinoblog.experiment.ExperimentDashboard
import com.group16.dinoblog.nearby.NearbyDiscoveryState
import com.group16.dinoblog.nearby.BleDiscoveryProtocol
import com.group16.dinoblog.nearby.BluetoothConnectionState
import com.group16.dinoblog.nearby.BluetoothPeerTransport
import com.group16.dinoblog.nearby.BleHandoffState
import com.group16.dinoblog.nearby.BleHandoffProtocol
import com.group16.dinoblog.repository.NearbyConnectionMethod
import com.group16.dinoblog.nearby.BlePermissions
import android.content.pm.ApplicationInfo
import androidx.compose.ui.platform.LocalContext

@Composable
fun NetworkDebugScreen(localId: String, localRelayEnabled: Boolean, network: NetworkState, socket: SocketState,
    logs: List<PacketLog>, messages: List<Message>, media: List<MediaEntity>, inventory: InventoryState, forwarding: ForwardingState,
    receipts: List<DeliveryReceipt>, routing: PredictabilityState, predictabilities: List<DeliveryPredictability>,
    server: ServerState, onlineMessages: List<OnlineMessageState>, onServerUrl: (String) -> Unit, onSyncOnline: () -> Unit,
    onClear: () -> Unit, onRecipient: (String) -> Unit, onChat: () -> Unit, onRetry: () -> Unit,
    experiment: ExperimentDashboard = ExperimentDashboard(),
    securityFingerprint: String = "Unavailable", lastSecurityError: String? = null,
    lastMessageSignatureVerified: Boolean? = null, lastReceiptSignatureVerified: Boolean? = null,
    nearby: NearbyDiscoveryState = NearbyDiscoveryState(),
    nearbyMethod: NearbyConnectionMethod = NearbyConnectionMethod.AUTO,
    bluetooth: BluetoothConnectionState = BluetoothConnectionState(),
    handoff: BleHandoffState = BleHandoffState(), wifiHandoffAddress: String? = null,
    recalls: List<MessageRecall> = emptyList(), revokes: List<Revoke> = emptyList(),
    revokeResults: List<RevokeResult> = emptyList()) {
    var manualRecipient by remember { mutableStateOf("") }
    val isDebuggable = LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    var editableServerUrl by remember(server.serverUrl) { mutableStateOf(server.serverUrl) }
    val currentInventory = socket.connected && socket.sessionId != null &&
        inventory.sessionId == socket.sessionId && inventory.peerId == socket.peerId
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Network debug", style = MaterialTheme.typography.headlineMedium) }
        item { InfoCard("Message recall") {
            Detail("Cancel requested", recalls.count { it.state == "REQUESTED" }.toString())
            Detail("Cancelled", recalls.count { it.state == "LOCAL_CANCELLED" || it.state == "CONFIRMED_CANCELLED" }.toString())
            Detail("Already delivered", recalls.count { it.state == "ALREADY_DELIVERED" }.toString())
            Detail("Recent recall states", recalls.sortedByDescending { it.updatedAt }.take(5)
                .joinToString("\n") { "${it.messageId}: ${it.state}" }.ifEmpty { "None" })
            Detail("Known / pending revokes", "${revokes.count { it.verificationState == "VERIFIED" }} / ${revokes.count { it.verificationState != "VERIFIED" }}")
            Detail("Recent revoke IDs and trust", revokes.sortedByDescending { it.createdAt }.take(3)
                .joinToString("\n") { "${it.originalMessageId}: ${it.revokeId} ${it.verificationState} until ${it.retentionUntil}" }.ifEmpty { "None" })
            Detail("Last signed result", revokeResults.maxByOrNull { it.processedAt }
                ?.let { "${it.originalMessageId}: ${it.result} ${it.verificationState}" } ?: "None")
            Detail("Invalid / pending / propagated", "${experiment.summary?.revokeInvalidSignatureCount ?: 0} / ${experiment.summary?.revokePendingVerificationCount ?: 0} / ${experiment.summary?.revokePropagatedCount ?: 0}")
            Detail("Relay bytes reclaimed", (experiment.summary?.relayBytesReclaimedByRevoke ?: 0).toString())
        } }
        item { InfoCard("BLE Nearby Discovery") {
            Detail("Protocol / service", "${BleDiscoveryProtocol.VERSION} / ${BleDiscoveryProtocol.SERVICE_UUID}")
            Detail("BLE supported", nearby.supported.toString())
            Detail("Bluetooth enabled", nearby.bluetoothEnabled.toString())
            Detail("Scanner / advertiser", "${nearby.scannerAvailable} / ${nearby.advertiserAvailable}")
            Detail("Scan / advertise permission", "${nearby.scanPermission} / ${nearby.advertisePermission}")
            Detail("Scanning / advertising", "${nearby.scanning} / ${nearby.advertising}")
            Detail("Current temporary token", nearby.currentToken ?: "None")
            Detail("Nearby name broadcast", nearby.nameBroadcastEnabled.toString())
            Detail("Current BLE nearby name", nearby.currentNearbyName ?: "None")
            Detail("Nearby name UTF-8 bytes", nearby.nearbyNameBytes.toString())
            Detail("BLE primary / scan response bytes", "${nearby.primaryAdvertisementBytes} / ${nearby.scanResponseBytes}")
            Detail("Token age", nearby.tokenCreatedAt?.let { "${(System.currentTimeMillis() - it) / 1_000}s" } ?: "None")
            Detail("Nearby results", nearby.results.size.toString())
            Detail("Observed RSSI", nearby.results.joinToString("\n") { "${it.discoveryToken.take(8)}: ${it.rssi} / ${"%.1f".format(it.smoothedRssi)} dBm" }.ifEmpty { "None" })
            Detail("Last scan / advertise error", "${nearby.lastScanError ?: "None"} / ${nearby.lastAdvertiseError ?: "None"}")
        } }
        item { InfoCard("Nearby connection transport") {
            Detail("Preference / current", "$nearbyMethod / ${socket.transportType ?: "NONE"}")
            Detail("Handoff service", BleHandoffProtocol.SERVICE_UUID.toString())
            Detail("Handoff session / state", "${handoff.sessionId ?: "None"} / ${handoff.status}")
            Detail("GATT server / request", "${handoff.serverListening} / ${handoff.requesting}")
            Detail("Wi-Fi handoff endpoint", wifiHandoffAddress ?: "Unavailable")
            Detail("Bluetooth connect permission", BlePermissions.hasConnect(LocalContext.current).toString())
            Detail("RFCOMM service UUID", BluetoothPeerTransport.SERVICE_UUID.toString())
            Detail("RFCOMM listener / client", "${bluetooth.listening} / ${bluetooth.connecting}")
            Detail("Bluetooth socket authenticated", (socket.connected && socket.transportType == "BLUETOOTH").toString())
            Detail("Last Bluetooth failure", bluetooth.lastFailure ?: "None")
            Detail("Last handoff failure", handoff.lastFailure ?: "None")
        } }
        item { InfoCard("Experiment") {
            Detail("State", if (experiment.session?.endedAt == null && experiment.session != null) "RUNNING" else "STOPPED")
            Detail("Experiment ID", experiment.session?.experimentId ?: "None")
            Detail("Routing mode", experiment.session?.routingMode ?: "SMART_SPRAY")
            Detail("Created / Delivered", "${experiment.summary?.messagesCreated ?: 0} / ${experiment.summary?.messagesDelivered ?: 0}")
            Detail("DTN / Online transmissions", "${experiment.summary?.dtnTransmissions ?: 0} / ${experiment.summary?.onlineTransmissions ?: 0}")
            Detail("Wi-Fi / Bluetooth connections", "${experiment.summary?.wifiPeerConnections ?: 0} / ${experiment.summary?.bluetoothPeerConnections ?: 0}")
            Detail("Wi-Fi / Bluetooth bytes sent", "${experiment.summary?.wifiBytesSent ?: 0} / ${experiment.summary?.bluetoothBytesSent ?: 0}")
            Detail("Wi-Fi / Bluetooth bytes received", "${experiment.summary?.wifiBytesReceived ?: 0} / ${experiment.summary?.bluetoothBytesReceived ?: 0}")
            Detail("Auto fallback count", (experiment.summary?.autoFallbackCount ?: 0).toString())
            Detail("Buffer / Peak", "${experiment.session?.currentBufferBytes ?: 0} / ${experiment.session?.peakBufferBytes ?: 0} B")
            Detail("Last event", experiment.lastEvent ?: "None")
        } }
        item { InfoCard("Internet mailbox") {
            Detail("Server state", server.status)
            Detail("Server URL", server.serverUrl.ifBlank { "Not configured" })
            Detail("Authenticated device", server.authenticatedDevice ?: "None")
            Detail("Last sync cursor", server.cursor.toString())
            Detail("Pending online uploads", messages.count { message -> message.sourceId == localId &&
                message.status !in setOf(com.group16.dinoblog.model.MessageStatus.DELIVERED,
                    com.group16.dinoblog.model.MessageStatus.EXPIRED) &&
                onlineMessages.none { it.messageId == message.messageId && it.state == "SERVER_ACCEPTED" } }.toString())
            Detail("Last server sync", server.lastSyncAt?.toString() ?: "Never")
            Detail("WebSocket state", server.webSocketState)
            Detail("Last server error", server.lastError ?: "None")
            Detail("Transport selected", server.lastTransport)
            if (isDebuggable) {
                OutlinedTextField(value = editableServerUrl, onValueChange = { editableServerUrl = it },
                    label = { Text("Development server URL") }, singleLine = true)
                TextButton(onClick = { onServerUrl(editableServerUrl) }) { Text("Save server URL") }
            }
            TextButton(onClick = onSyncOnline) { Text("Sync server now") }
        } }
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
            Detail("Security protocol", "1 / ECDSA P-256")
            Detail("Local key fingerprint", securityFingerprint)
            if (securityFingerprint == "IDENTITY KEY UNAVAILABLE")
                Text("Identity key unavailable. Sending is blocked. To reset this installation explicitly, use Android Settings > Apps > DinoBlog > Storage > Clear storage. This deletes local messages and creates a new Device ID.",
                    color = MaterialTheme.colorScheme.error)
            Detail("Peer authentication", socket.peerAuthState)
            Detail("Peer trust", socket.peerTrustState ?: "Unknown")
            Detail("Peer key fingerprint", socket.peerFingerprint ?: "None")
            Detail("HELLO proof verified", socket.helloProofVerified.toString())
            Detail("HELLO nonce state", socket.helloNonceState)
            Detail("Message signature verified", lastMessageSignatureVerified?.toString() ?: "Not checked")
            Detail("Receipt signature verified", lastReceiptSignatureVerified?.toString() ?: "Not checked")
            Detail("Last security error", lastSecurityError ?: "None")
            Detail("Wi-Fi Direct", "${network.wifiStatus}; discovering: ${network.discovering}")
            Detail("Role", if (!network.groupFormed) "No group" else if (network.isGroupOwner) "Group owner / TCP server" else "Group client / TCP client")
            Detail("TCP status", socket.status)
            Detail("Connection", if (socket.connected) "CONNECTED" else if (socket.awaitingHello) "TCP CONNECTED / WAITING FOR HELLO" else "DISCONNECTED")
            Detail("Connected Peer Device ID", socket.peerIdentityLabel)
            Detail("Local relay enabled", localRelayEnabled.toString())
            Detail("Connected peer relay enabled", socket.peerRelayEnabled?.toString() ?: "Unknown")
            Detail("Group owner IP", network.groupOwnerIp ?: "—")
            Detail("Local / remote IP", "${socket.localIp ?: "—"} / ${socket.remoteIp ?: "—"}")
            Detail("TCP port", "8988")
            Detail("Wi-Fi peer count", network.peers.size.toString())
            Detail("Wi-Fi peers", network.peers.joinToString("\n") { "${it.deviceName} · ${it.status.label}" }.ifEmpty { "None" })
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
            Detail("SMART_SPRAY candidates", if (active) forwarding.relayIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("WAIT IDs", forwarding.waitIds.joinToString("\n").ifEmpty { "Empty" })
            Detail("Initial spray budget", DtnConfig.INITIAL_SPRAY_COPIES.toString())
            val usage = messages.count { it.sourceId != localId && it.destinationId != localId &&
                it.status == com.group16.dinoblog.model.MessageStatus.CARRIED && it.expiresAt > System.currentTimeMillis() &&
                receipts.none { r -> r.originalMessageId == it.messageId } }
            Detail("Active relay buffer", "$usage / ${DtnConfig.MAX_RELAY_BUFFER_MESSAGES}")
            Detail("Relay acceptance", if (localRelayEnabled) "ENABLED" else "DISABLED")
            Detail("Peer rejected IDs", forwarding.rejectedIds.joinToString("\n").ifEmpty { "Empty" })
            Detail("Last rejection", forwarding.lastRejectReason ?: "None")
            Detail("Last spray given / kept", "${forwarding.lastCopiesGiven ?: "None"} / ${forwarding.lastCopiesKept ?: "None"}")
            Detail("Peer known IDs (this session)", if (active) forwarding.peerKnownIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("Awaiting ACK", if (active) forwarding.awaitingAckIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("Last forwarded message", if (active) forwarding.lastSentId ?: "None" else "None")
            Detail("Last forwarding type", if (active) forwarding.lastSentKind?.name ?: "None" else "None")
            Detail("Last ACK", if (active) forwarding.lastAckId ?: "None" else "None")
            Text("DIRECT delivers even with one copy. SMART_SPRAY requires a current peer summary and a destination score better by the margin. WAIT preserves the budget. Scores never prove delivery.", style = MaterialTheme.typography.bodySmall)
        } }
        item { InfoCard("DTN image media") {
            val relayBytes = media.filter { it.sourceId != localId && it.destinationId != localId }
                .sumOf { it.fileSize }
            Detail("Relay media allocation", "$relayBytes / ${DtnConfig.MAX_RELAY_MEDIA_BYTES} bytes")
            Detail("Complete / partial", "${media.count { it.isComplete }} / ${media.count { !it.isComplete }}")
            Detail("Chunk size / image DTN limit", "${DtnConfig.IMAGE_CHUNK_BYTES} / ${DtnConfig.MAX_IMAGE_BYTES} bytes")
            Detail("Audio DTN / online limit", "${com.group16.dinoblog.model.MediaPolicy.AUDIO_DTN_MAX} / ${com.group16.dinoblog.model.MediaPolicy.AUDIO_ONLINE_MAX} bytes")
            Detail("Video DTN / online limit", "${com.group16.dinoblog.model.MediaPolicy.VIDEO_DTN_MAX} / ${com.group16.dinoblog.model.MediaPolicy.VIDEO_ONLINE_MAX} bytes")
        } }
        items(media, key = { "media-${it.mediaId}" }) { row ->
            InfoCard(if (row.isComplete) "Verified image" else "Partial image") {
                Detail("Message / media ID", "${row.messageId} / ${row.mediaId}")
                Detail("MIME / size", "${row.mimeType} / ${row.fileSize} bytes")
                Detail("SHA-256", row.sha256)
                Detail("Chunks", "${row.completedChunks} / ${row.totalChunks} at ${row.chunkSize} bytes")
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
                if (message.contentType != com.group16.dinoblog.model.MessageContentType.TEXT) {
                    val item = media.find { it.messageId == message.messageId }
                    Detail("Content", "${message.contentType} / ${message.mediaId}")
                    if (item != null) {
                        Detail("Delivery mode", com.group16.dinoblog.model.MediaPolicy.deliveryMode(item.mediaType, item.fileSize))
                        Detail("MIME / bytes", "${item.mimeType} / ${item.fileSize}")
                        Detail("Duration / dimensions", "${item.durationMs ?: "-"} ms / ${item.width ?: "-"} × ${item.height ?: "-"}")
                        Detail("SHA-256", item.sha256)
                        Detail("Media state", if (item.isComplete) "Complete" else "Partial ${item.completedChunks}/${item.totalChunks}")
                    }
                } else Text(message.payload)
            }
        }
    }
}
