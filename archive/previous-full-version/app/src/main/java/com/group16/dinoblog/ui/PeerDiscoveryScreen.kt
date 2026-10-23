package com.group16.dinoblog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.group16.dinoblog.model.NetworkState
import com.group16.dinoblog.model.SocketState

@Composable
fun PeerDiscoveryScreen(
    network: NetworkState, socket: SocketState, onDiscover: () -> Unit, onConnect: (String) -> Unit,
    onDisconnect: () -> Unit, onPermissions: () -> Unit, onChat: () -> Unit,
    onWifiSettings: () -> Unit, onLocationSettings: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Nearby devices", style = MaterialTheme.typography.headlineMedium)
            Text("Open Dinoblog on both phones, enable Wi-Fi and Location, then scan on both. Tap Connect on one phone and accept on the other.")
        }
        item {
            InfoCard(if (network.supported) network.wifiStatus else "Wi-Fi Direct unavailable") {
                if (network.discovering) LinearProgressIndicator()
                Text("TCP: ${socket.status}")
                network.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDiscover, enabled = network.supported && network.wifiEnabled && !network.groupFormed) { Text("Scan for peers") }
                    if (network.groupFormed) OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                }
                TextButton(onClick = onPermissions) { Text("Refresh / permissions") }
                Row {
                    TextButton(onClick = onWifiSettings) { Text("Wi-Fi settings") }
                    TextButton(onClick = onLocationSettings) { Text("Location settings") }
                }
                if (socket.peerId != null) Button(onClick = onChat) { Text("Chat with ${socket.peerName}") }
            }
        }
        if (network.peers.isEmpty()) item { Text("No peers found yet. Move the phones closer and scan again.") }
        items(network.peers, key = { it.address }) { peer ->
            InfoCard(peer.name) {
                Detail("Wi-Fi Direct address (not Device ID)", peer.address)
                Text(peer.status)
                Button(onClick = { onConnect(peer.address) }, enabled = !network.groupFormed && peer.status != "Invited") { Text("Connect") }
            }
        }
        item { Text("Discovery may show devices without Dinoblog. Chat becomes available after both apps complete the Device ID handshake.", style = MaterialTheme.typography.bodySmall) }
    }
}
