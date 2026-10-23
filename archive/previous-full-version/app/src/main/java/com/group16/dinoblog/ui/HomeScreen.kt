package com.group16.dinoblog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.NetworkState
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.model.SocketState

@Composable
fun HomeScreen(
    localId: String, localName: String, network: NetworkState, socket: SocketState,
    messages: List<Message>, peers: List<Peer>, onDiscover: () -> Unit, onChat: (String) -> Unit,
    onRefresh: () -> Unit, onDisconnect: () -> Unit, onSettings: () -> Unit, permissionDenied: Boolean,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("A little closer.\nA little more connected.", style = MaterialTheme.typography.headlineMedium)
            Text("Send messages directly between two nearby Android phones. No Internet needed.", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            InfoCard("This device") {
                Detail("Name", localName)
                Detail("Persistent Device ID", localId)
                TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Device ID", localId))) } }) { Text("Copy Device ID") }
            }
        }
        item {
            InfoCard("Connection") {
                Detail("Wi-Fi Direct", network.wifiStatus)
                Detail("TCP socket", socket.status)
                socket.peerId?.let { Detail("Connected to", "${socket.peerName}\n$it") }
                network.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = onDiscover, modifier = Modifier.fillMaxWidth()) { Text("Find nearby devices") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefresh) { Text("Refresh / permissions") }
                    if (network.groupFormed) TextButton(onClick = onDisconnect) { Text("Disconnect") }
                }
                if (permissionDenied) {
                    Text("Permission is needed to discover and connect. If Android no longer prompts, enable it in app settings.")
                    TextButton(onClick = onSettings) { Text("Open app settings") }
                }
            }
        }
        item {
            InfoCard("Local message store") {
                Text("${messages.count { it.status == MessageStatus.PENDING }} pending  ·  ${messages.count { it.status == MessageStatus.DELIVERED }} delivered  ·  ${messages.count { it.status == MessageStatus.RECEIVED }} received")
                Text("Keep both apps open during the demo. Unsent messages stay on this phone and retry when the recipient reconnects.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("Your conversations", style = MaterialTheme.typography.titleLarge) }
        if (peers.isEmpty()) item { Text("Connect to a peer to exchange Device IDs and start a conversation.") }
        items(peers, key = { it.deviceId }) { peer ->
            InfoCard(peer.name) {
                Detail(if (socket.peerId == peer.deviceId) "Online" else "Offline · messages will queue", peer.deviceId)
                TextButton(onClick = { onChat(peer.deviceId) }) { Text("Open chat") }
            }
        }
    }
}
