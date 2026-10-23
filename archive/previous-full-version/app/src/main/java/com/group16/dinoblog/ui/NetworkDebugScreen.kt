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
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.NetworkState
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.util.PacketLog

@Composable
fun NetworkDebugScreen(localId: String, network: NetworkState, socket: SocketState,
    logs: List<PacketLog>, messages: List<Message>, onClear: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Network debug", style = MaterialTheme.typography.headlineMedium) }
        item {
            InfoCard("Live connection") {
                Detail("Local Device ID", localId)
                Detail("Wi-Fi Direct", "${network.wifiStatus}\nEnabled: ${network.wifiEnabled}; discovering: ${network.discovering}")
                Detail("Role", if (!network.groupFormed) "No group" else if (network.isGroupOwner) "Group owner / TCP server" else "Group client / TCP client")
                Detail("TCP status", socket.status)
                Detail("Connected Device ID", socket.peerId ?: "—")
                Detail("Group owner IP", network.groupOwnerIp ?: "—")
                Detail("Local / remote IP", "${socket.localIp ?: "—"} / ${socket.remoteIp ?: "—"}")
                Detail("TCP port", "8988")
                Detail("Discovered peers", network.peers.joinToString("\n") { "${it.name} · ${it.address} · ${it.status}" }.ifEmpty { "None" })
                network.error?.let { Detail("Last Wi-Fi error", it) }
            }
        }
        item {
            Text("Packet log · ${logs.size}/250", style = MaterialTheme.typography.titleLarge)
            Text("Newest first. Logs are in memory; messages persist in Room.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClear) { Text("Clear logs") }
        }
        if (logs.isEmpty()) item { Text("Connect a peer to see HELLO, MESSAGE, ACK and heartbeat packets.") }
        items(logs) { log ->
            InfoCard("${timeLabel(log.time)}  ${log.direction}  ${log.detail}") {
                log.messageId?.let { Detail("Message ID", it) }
                log.ttl?.let { Text("TTL: ${it}s") }
                log.status?.let { Text("Status: $it") }
            }
        }
        item { Text("Stored messages · ${messages.size}", style = MaterialTheme.typography.titleLarge) }
        items(messages.reversed(), key = { it.messageId }) { message ->
            InfoCard(message.status.name) {
                Detail("Message ID", message.messageId)
                Detail("Sender → receiver", "${message.senderId}\n→ ${message.receiverId}")
                Detail("TTL / timestamp", "${message.ttl}s / ${message.timestamp} (Unix ms)")
                Text(message.payload)
            }
        }
    }
}
