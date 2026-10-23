package com.group16.dinoblog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.model.SocketState

@Composable
fun HomeScreen(displayName: String, socket: SocketState, peers: List<Peer>,
    onPeer: (String) -> Unit, onDiscover: () -> Unit, onChat: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Hello, $displayName", style = MaterialTheme.typography.headlineLarge)
            Text("Chat with nearby people. Messages can wait for a future connection.")
        }
        item { InfoCard("Connection") {
            Text(socket.peerId?.let { id -> "Connected to ${peers.find { it.deviceId == id }?.displayName ?: socket.peerName ?: "DinoBlog User"}" }
                ?: "No one connected right now")
            Button(onClick = onDiscover, modifier = Modifier.fillMaxWidth()) { Text("Find nearby devices") }
            TextButton(onClick = onChat) { Text("Open chats") }
        } }
        item { Text("Conversations", style = MaterialTheme.typography.titleLarge) }
        if (peers.isEmpty()) item { Text("Connect another DinoBlog device to start a conversation.") }
        items(peers, key = { it.deviceId }) { peer ->
            InfoCard(peer.displayName) {
                Text(if (socket.peerId == peer.deviceId) "Nearby now" else "Messages can be delivered later")
                TextButton(onClick = { onPeer(peer.deviceId) }) { Text("Open chat") }
            }
        }
    }
}
