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
fun PeerDiscoveryScreen(network: NetworkState, socket: SocketState, onDiscover: () -> Unit,
    onConnect: (String) -> Unit, onDisconnect: () -> Unit, onPermissions: () -> Unit,
    onStartTcp: () -> Unit, onChat: () -> Unit, onWifiSettings: () -> Unit, onLocationSettings: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Nearby devices", style = MaterialTheme.typography.headlineMedium)
            Text("Open Dinoblog on both phones, enable Wi-Fi and Location, and scan on both. Connect from one phone and accept on the other.")
        }
        item { InfoCard(network.wifiStatus) {
            if (network.discovering) LinearProgressIndicator()
            Detail("TCP", socket.status)
            network.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onDiscover, enabled = network.supported && network.wifiEnabled && !network.groupFormed) { Text("Scan for peers") }
            if (network.groupFormed) {
                Text(if (network.isGroupOwner) "This phone is the TCP server." else "This phone is the TCP client.")
                if (!socket.connected) {
                    Button(onClick = onStartTcp) { Text("Start TCP") }
                    Text("TCP connects automatically and retries while this Wi-Fi Direct group exists. Start TCP can restart a failed attempt.", style = MaterialTheme.typography.bodySmall)
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
            InfoCard(peer.name) {
                Detail("Wi-Fi Direct address", peer.address)
                Text(peer.status)
                Button(onClick = { onConnect(peer.address) }, enabled = !network.groupFormed && peer.status != "Invited") { Text("Connect") }
            }
        }
        item { Text("Install Dinoblog 2.8 on all demo phones. Discovery may also show devices without Dinoblog.", style = MaterialTheme.typography.bodySmall) }
    }
}
