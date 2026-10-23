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
fun PeerDiscoveryScreen(network: NetworkState, socket: SocketState, connectedName: String, onDiscover: () -> Unit,
    onConnect: (String) -> Unit, onDisconnect: () -> Unit, onPermissions: () -> Unit,
    onStartTcp: () -> Unit, onChat: () -> Unit, onWifiSettings: () -> Unit, onLocationSettings: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Nearby devices", style = MaterialTheme.typography.headlineMedium)
            Text("Open DinoBlog on both phones, enable Wi-Fi and Location, then scan. Connect from one phone and accept on the other.")
        }
        item { InfoCard(if (socket.connected) "Connected" else if (network.groupFormed) "Connecting" else if (network.wifiEnabled) "Ready to scan" else "Turn on Wi-Fi") {
            if (network.discovering) LinearProgressIndicator()
            if (socket.connected) Text("Connected to $connectedName")
            network.error?.let { Text("Could not connect. Check Wi-Fi and Location, then try again.", color = MaterialTheme.colorScheme.error) }
            Button(onClick = onDiscover, enabled = network.supported && network.wifiEnabled && !network.groupFormed) { Text("Scan for peers") }
            if (network.groupFormed) {
                if (!socket.connected) {
                    Button(onClick = onStartTcp) { Text("Retry connection") }
                }
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            }
            if (socket.connected) Button(onClick = onChat) { Text("Open chat") }
            TextButton(onClick = onPermissions) { Text("Refresh / permissions") }
            Row {
                TextButton(onClick = onWifiSettings) { Text("Wi-Fi settings") }
                TextButton(onClick = onLocationSettings) { Text("Location settings") }
            }
        } }
        if (network.peers.isEmpty()) item { Text("No peers found. Move the phones closer and scan again.") }
        items(network.peers, key = { it.address }) { peer ->
            InfoCard("Nearby DinoBlog device") {
                Text(if (peer.status == "Invited") "Invitation sent" else "Ready to connect")
                Button(onClick = { onConnect(peer.address) }, enabled = !network.groupFormed && peer.status != "Invited") { Text("Connect") }
            }
        }
        item { Text("Nearby results may include devices without DinoBlog.", style = MaterialTheme.typography.bodySmall) }
    }
}
