package com.group16.dinoblog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.group16.dinoblog.model.NetworkState
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.InventoryState
import com.group16.dinoblog.model.ForwardingState
import com.group16.dinoblog.model.DeliveryReceipt
import com.group16.dinoblog.util.PacketLog

@Composable
fun NetworkDebugScreen(localId: String, network: NetworkState, socket: SocketState,
    logs: List<PacketLog>, messages: List<Message>, inventory: InventoryState, forwarding: ForwardingState,
    receipts: List<DeliveryReceipt>, onClear: () -> Unit) {
    val currentInventory = socket.connected && socket.sessionId != null &&
        inventory.sessionId == socket.sessionId && inventory.peerId == socket.peerId
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Network debug", style = MaterialTheme.typography.headlineMedium) }
        item { InfoCard("Direct chat connection") {
            Detail("Local Device ID", localId)
            Detail("Wi-Fi Direct", "${network.wifiStatus}; discovering: ${network.discovering}")
            Detail("Role", if (!network.groupFormed) "No group" else if (network.isGroupOwner) "Group owner / TCP server" else "Group client / TCP client")
            Detail("TCP status", socket.status)
            Detail("Connection", if (socket.connected) "CONNECTED" else if (socket.awaitingHello) "TCP CONNECTED / WAITING FOR HELLO" else "DISCONNECTED")
            Detail("Connected Peer Device ID", socket.peerIdentityLabel)
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
            inventory.builtAt?.let { Detail("Local snapshot built", timeLabel(it)) }
            if (currentInventory) inventory.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Detail("Local receipt inventory", inventory.localReceiptIds.joinToString("\n").ifEmpty { "Empty" })
            Detail("Remote receipt inventory", if (currentInventory) inventory.remoteReceiptIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("Receipt missing on peer", if (currentInventory) inventory.receiptMissingOnPeer.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("Peer supports receipts", (currentInventory && inventory.peerSupportsReceipts).toString())
            Text("Messages with known delivery are excluded. Receipt IDs advertise persistent delivery knowledge.", style = MaterialTheme.typography.bodySmall)
        } }
        item { InfoCard("Epidemic routing - direct first") {
            val active = socket.connected && socket.sessionId != null && forwarding.sessionId == socket.sessionId && forwarding.peerId == socket.peerId
            Detail("Forward candidates", if (active) forwarding.candidateIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("DIRECT candidates", if (active) forwarding.directIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("RELAY candidates", if (active) forwarding.relayIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("Peer known IDs (this session)", if (active) forwarding.peerKnownIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("Awaiting ACK", if (active) forwarding.awaitingAckIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("Last forwarded message", if (active) forwarding.lastSentId ?: "None" else "None")
            Detail("Last forwarding type", if (active) forwarding.lastSentKind?.name ?: "None" else "None")
            Detail("Last ACK", if (active) forwarding.lastAckId ?: "None" else "None")
            Detail("Receipt peer-known IDs", if (active) forwarding.receiptPeerKnownIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("Receipt awaiting ACK", if (active) forwarding.receiptAwaitingAckIds.joinToString("\n").ifEmpty { "Empty" } else "None")
            Detail("Last receipt forwarded", if (active) "${forwarding.lastReceiptSentId ?: "None"} / ${forwarding.lastReceiptSentKind ?: "None"}" else "None")
            Detail("Last receipt received", if (active) forwarding.lastReceiptReceivedId ?: "None" else "None")
            Text("DIRECT delivers to this peer. RELAY copies to another carrier; its ACK is not final delivery. Sender keeps its own path depth.", style = MaterialTheme.typography.bodySmall)
        } }
        item { InfoCard("Final delivery known / suppressed messages") {
            Text(receipts.joinToString("\n") { "${it.originalMessageId} — final delivery known" }.ifEmpty { "None" })
        } }
        item { Text("Stored delivery receipts · ${receipts.size}", style = MaterialTheme.typography.titleLarge) }
        items(receipts, key = { "receipt:${it.receiptId}" }) { receipt ->
            InfoCard("Delivery receipt") {
                Detail("receiptId", receipt.receiptId)
                Detail("originalMessageId", receipt.originalMessageId)
                Detail("Original source / destination reached", "${receipt.originalSourceId}\n${receipt.originalDestinationId}")
                Detail("deliveredAt", timeLabel(receipt.deliveredAt))
                Detail("Receipt hop / limit", "${receipt.hopCount} / ${receipt.hopLimit}")
                Detail("createdAt / expiresAt", "${receipt.createdAt} / ${receipt.expiresAt}")
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
                Detail("sourceId", message.sourceId)
                Detail("destinationId", message.destinationId)
                Detail("Carrier", message.carrierId ?: "Direct")
                Detail("hopCount / hopLimit", "${message.hopCount} / ${message.hopLimit}")
                Detail("createdAt / expiresAt", "${message.createdAt} / ${message.expiresAt} (Unix ms)")
                Detail("Sender → receiver", "${message.senderId}\n→ ${message.receiverId}")
                Detail("TTL / timestamp", "${message.ttl}s / ${message.timestamp} (Unix ms)")
                Text(message.payload)
            }
        }
    }
}
