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
import com.group16.dinoblog.model.WifiDirectDiscoveryResult
import com.group16.dinoblog.model.WifiDirectPeerStatus
import com.group16.dinoblog.model.NearbyWifiCorrelation
import com.group16.dinoblog.nearby.NearbyDiscoveryState
import com.group16.dinoblog.nearby.NearbyDiscoveryResult
import com.group16.dinoblog.nearby.SignalLevel
import com.group16.dinoblog.nearby.BluetoothConnectionState
import com.group16.dinoblog.nearby.BleHandoffState
import com.group16.dinoblog.repository.NearbyConnectionMethod

@Composable
fun PeerDiscoveryScreen(network: NetworkState, socket: SocketState, connectedName: String, onDiscover: () -> Unit,
    onConnect: (String) -> Unit, onDisconnect: () -> Unit, onPermissions: () -> Unit,
    onStartTcp: () -> Unit, onChat: () -> Unit, onWifiSettings: () -> Unit, onLocationSettings: () -> Unit,
    nearby: NearbyDiscoveryState, onNearbyRefresh: () -> Unit, onNearbyPermission: () -> Unit,
    onBluetoothSettings: () -> Unit, bluetooth: BluetoothConnectionState, handoff: BleHandoffState,
    nearbyConnecting: Boolean, selectedNearbyToken: String?, selectedNearbyResult: NearbyDiscoveryResult?,
    selectedWifiAddress: String?, selectedWifiResult: WifiDirectDiscoveryResult?,
    connectionMethod: NearbyConnectionMethod,
    onNearbyConnect: (String) -> Unit) {
    val visibleResults = nearby.results + listOfNotNull(selectedNearbyResult?.takeIf { selected ->
        selectedNearbyToken == selected.discoveryToken && (nearbyConnecting || network.groupFormed || socket.connected) &&
            nearby.results.none { it.discoveryToken == selected.discoveryToken }
    })
    // Only an explicit Phase 17 handoff address can correlate a BLE row with Wi-Fi.
    val handoffWifi = NearbyWifiCorrelation.forHandoff(selectedNearbyToken, selectedWifiAddress,
        network.peers, selectedWifiResult)
    val visibleWifiResults = network.peers.filterNot { handoffWifi?.addressInternal == it.addressInternal } +
        listOfNotNull(selectedWifiResult?.takeIf { selected ->
            selectedNearbyToken == null && selectedWifiAddress == selected.addressInternal &&
                (network.groupFormed || socket.connected ||
                    (network.error == null && network.wifiStatus.startsWith("Connecting"))) &&
                network.peers.none { it.addressInternal == selected.addressInternal }
        })
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Nearby DinoBlog Users", style = MaterialTheme.typography.headlineMedium)
            Text("Nearby names are unverified hints. DinoBlog verifies identity after connecting.")
        }
        item { InfoCard("Bluetooth nearby discovery") {
            when {
                !nearby.supported -> Text("Bluetooth Low Energy is not available on this device.")
                !nearby.scanPermission -> {
                    Text("Nearby device permission is required to scan.")
                    Button(onClick = onNearbyPermission) { Text("Allow nearby scanning") }
                }
                !nearby.bluetoothEnabled -> {
                    Text("Turn on Bluetooth to discover nearby DinoBlog users.")
                    Button(onClick = onBluetoothSettings) { Text("Bluetooth settings") }
                }
                !nearby.scannerAvailable -> Text("Bluetooth scanning is unavailable right now.")
                else -> {
                    if (nearby.scanning) LinearProgressIndicator()
                    Text(if (nearby.scanning) "Scanning for DinoBlog devices…" else "Scan finished. Refresh to scan again.")
                    Button(onClick = onNearbyRefresh) { Text("Refresh") }
                    nearby.lastScanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        } }
        if (visibleResults.isEmpty()) item { Text("No DinoBlog BLE advertisements found yet.") }
        items(visibleResults, key = { it.discoveryToken }) { result ->
            val verified = socket.connected && socket.peerAuthState == "AUTHENTICATED" &&
                selectedNearbyToken == result.discoveryToken
            val thisHandoffWifi = handoffWifi?.takeIf { selectedNearbyToken == result.discoveryToken }
            InfoCard(if (verified) connectedName else "${result.nearbyDisplayName ?: "DinoBlog device"} • ${result.shortCode}") {
                Text(when (result.signalLevel) {
                    SignalLevel.STRONG -> "Strong BLE signal"
                    SignalLevel.MEDIUM -> "Medium BLE signal"
                    SignalLevel.WEAK -> "Weak BLE signal"
                })
                thisHandoffWifi?.let {
                    Text("Device: ${it.deviceName}")
                    if (!verified) Text("Wi-Fi Direct ${it.status.label.lowercase()}")
                }
                Text(if (verified) "Verified" else "Nearby • Not verified",
                    style = MaterialTheme.typography.bodySmall)
                if (verified) Text("Connected via ${if (socket.transportType == "BLUETOOTH") "Bluetooth" else "Wi-Fi Direct"}")
                else {
                    if (nearbyConnecting && selectedNearbyToken == result.discoveryToken)
                        Text(if (socket.awaitingHello) "Verifying identity…" else "Connecting…")
                    Button(onClick = { onNearbyConnect(result.discoveryToken) },
                        enabled = !socket.connected && !nearbyConnecting) { Text("Connect") }
                }
            }
        }
        if (nearbyConnecting) item { Text("Connecting…") }
        handoff.lastFailure?.let { failure -> item { Text(failure, color = MaterialTheme.colorScheme.error) } }
        bluetooth.lastFailure?.let { failure -> item { Text(failure, color = MaterialTheme.colorScheme.error) } }
        item {
            Text("Nearby via Wi-Fi Direct", style = MaterialTheme.typography.titleMedium)
            Text("Android device names are unverified. Connect to verify DinoBlog identity.")
        }
        item { InfoCard("Wi-Fi Direct controls") {
            if (network.discovering) LinearProgressIndicator()
            if (!network.wifiPermissionGranted) Text("Nearby Wi-Fi permission is required to find Wi-Fi Direct devices.")
            if (socket.connected && selectedNearbyToken == null && selectedWifiAddress == null)
                Text("Connected to $connectedName via ${if (socket.transportType == "BLUETOOTH") "Bluetooth" else "Wi-Fi Direct"}")
            else if (socket.awaitingHello) Text("Verifying DinoBlog device…")
            network.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onDiscover, enabled = network.supported && network.wifiEnabled &&
                network.wifiPermissionGranted && !network.groupFormed) { Text("Scan for peers") }
            if (network.groupFormed && socket.transportType != "BLUETOOTH") {
                if (!socket.connected) {
                    Button(onClick = onStartTcp) { Text("Retry connection") }
                }
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            }
            if (socket.connected && socket.transportType == "BLUETOOTH")
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            if (socket.connected) Button(onClick = onChat) { Text("Open chat") }
            TextButton(onClick = onPermissions) { Text("Refresh / permissions") }
            Row {
                TextButton(onClick = onWifiSettings) { Text("Wi-Fi settings") }
                TextButton(onClick = onLocationSettings) { Text("Location settings") }
            }
        } }
        if (visibleWifiResults.isEmpty()) item { Text("No Wi-Fi Direct devices found. Refresh to scan again.") }
        items(visibleWifiResults, key = { "wifi:${it.addressInternal}" }) { peer ->
            val selected = selectedWifiAddress == peer.addressInternal && selectedNearbyToken == null
            val verified = selected && socket.connected && socket.peerAuthState == "AUTHENTICATED" &&
                socket.transportType == "WIFI_DIRECT"
            InfoCard(if (verified) connectedName else peer.deviceName) {
                if (verified) Text("Device: ${peer.deviceName}")
                Text("Wi-Fi Direct device")
                Text(when {
                    verified -> "Connected via Wi-Fi Direct"
                    selected && socket.awaitingHello -> "Connected • Verifying identity…"
                    selected && network.groupFormed -> "Connected • Verifying identity…"
                    selected && network.wifiStatus.startsWith("Connecting") -> "Connecting…"
                    else -> peer.status.label
                })
                Text(if (verified) "Verified" else "Not verified",
                    style = MaterialTheme.typography.bodySmall)
                if (!verified) Button(onClick = { onConnect(peer.addressInternal) },
                    enabled = !network.groupFormed && !socket.connected && !nearbyConnecting &&
                        connectionMethod != NearbyConnectionMethod.BLUETOOTH &&
                        peer.status == WifiDirectPeerStatus.AVAILABLE) { Text("Connect") }
            }
        }
        item { Text("Nearby results may include devices without DinoBlog.", style = MaterialTheme.typography.bodySmall) }
    }
}
