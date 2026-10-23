package com.group16.dinoblog.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import com.group16.dinoblog.model.NetworkState
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.Peer
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(localId: String, localName: String, network: NetworkState, socket: SocketState,
    peers: List<Peer>, messages: List<Message>, onPeer: (String) -> Unit,
    onDiscover: () -> Unit, onChat: () -> Unit, onRefresh: () -> Unit,
    permissionDenied: Boolean, onSettings: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Hello, nearby.", style = MaterialTheme.typography.headlineLarge)
            Text("Connect two nearby Android phones and chat over Wi-Fi Direct.")
        }
        item { InfoCard("This device") {
            Detail("Name", localName)
            Detail("Device ID", localId)
            TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Device ID", localId))) } }) { Text("Copy Device ID") }
        } }
        item { InfoCard("Connection") {
            Detail("Wi-Fi Direct", network.wifiStatus)
            Detail("TCP", socket.status)
            socket.peerId?.let { Detail("Connected peer", "${socket.peerName}\n$it") }
            network.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onDiscover, modifier = Modifier.fillMaxWidth()) { Text("Find nearby devices") }
            OutlinedButton(onClick = onChat, modifier = Modifier.fillMaxWidth()) { Text("Open chat") }
            TextButton(onClick = onRefresh) { Text("Refresh / permissions") }
            if (permissionDenied) {
                Text("Allow nearby-device/location permission. If Android no longer prompts, open app settings.")
                TextButton(onClick = onSettings) { Text("Open app settings") }
            }
        } }
        item { InfoCard("Saved messages") {
            Text("${messages.count { it.status == MessageStatus.CARRIED }} carried / ${messages.count { it.status == MessageStatus.FORWARDED }} forwarded (not final delivery)")
            Text("${messages.count { it.status == MessageStatus.PENDING }} pending · ${messages.count { it.status == MessageStatus.DELIVERED }} delivered · ${messages.count { it.status == MessageStatus.RECEIVED }} received")
            Text("History is saved on this phone. Pending messages retry when their recipient reconnects. Keep both apps visible to exchange messages.")
        } }
        item { Text("Conversations", style = MaterialTheme.typography.titleLarge) }
        if (peers.isEmpty()) item { Text("Connect another phone to exchange Device IDs, or enter its ID in Chat.") }
        items(peers, key = { it.deviceId }) { peer ->
            InfoCard(peer.name) {
                Detail(if (socket.peerId == peer.deviceId) "Connected" else "Offline · messages can queue", peer.deviceId)
                TextButton(onClick = { onPeer(peer.deviceId) }) { Text("Open chat") }
            }
        }
    }
}
