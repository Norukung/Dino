const fs = require('fs');
function edit(path, mutate) {
  const original = fs.readFileSync(path, 'utf8');
  let value = original.replace(/\r\n/g, '\n');
  const x = {
    one(before, after = '') {
      const n = value.split(before).length - 1;
      if (n !== 1) throw Error(`${path}: ${n} occurrences of ${before.slice(0, 70)}`);
      value = value.replace(before, after);
    },
    all(before, after, expected) {
      const n = value.split(before).length - 1;
      if (n !== expected) throw Error(`${path}: ${n} occurrences, expected ${expected}, of ${before.slice(0, 70)}`);
      value = value.split(before).join(after);
    },
    re(pattern, after = '') {
      const n = [...value.matchAll(new RegExp(pattern.source, pattern.flags.includes('g') ? pattern.flags : pattern.flags + 'g'))].length;
      if (n !== 1) throw Error(`${path}: ${n} matches of ${pattern}`);
      value = value.replace(pattern, after);
    },
  };
  mutate(x);
  fs.writeFileSync(path, original.includes('\r\n') ? value.replace(/\n/g, '\r\n') : value);
}
const a = 'app/src/main/java/com/group16/dinoblog/';
if (process.env.RESUME_DISCOVERY !== '1') {
edit(a + 'nearby/BleDiscoveryProtocol.kt', x => {
  x.one('import java.nio.ByteBuffer\nimport java.nio.charset.CharacterCodingException\nimport java.nio.charset.CodingErrorAction\nimport java.nio.charset.StandardCharsets\nimport java.text.BreakIterator\n');
  x.one('import com.group16.dinoblog.model.DisplayNames\n');
  x.one('    /** Separate service-data key so Android\'s ScanRecord retains both token and name. */\n    val NAME_SERVICE_UUID: UUID = UUID.fromString("9e8c5d36-47b1-4f69-9f20-b44fd8cd4d6b")\n');
  x.one('    const val SCAN_RESPONSE_OVERHEAD_BYTES = 18 // AD length, type, 128-bit UUID\n    const val MAX_NEARBY_NAME_BYTES = MAX_LEGACY_ADVERTISEMENT_BYTES - SCAN_RESPONSE_OVERHEAD_BYTES\n');
  x.one('    fun decode(data: ByteArray?, nameData: ByteArray? = null): DiscoveryAdvertisement? {',
    '    fun decode(data: ByteArray?): DiscoveryAdvertisement? {');
  x.one('        return DiscoveryAdvertisement(data.copyOfRange(1, 9).toHex(), VERSION, flags,\n            decodeName(nameData), nameData != null)',
    '        return DiscoveryAdvertisement(data.copyOfRange(1, 9).toHex(), VERSION, flags)');
  x.re(/    \/\*\* A single NUL byte signals anonymous presence[\s\S]*?(?=    fun shortCode\()/);
  x.one('data class DiscoveryAdvertisement(val token: String, val version: Int, val flags: Int,\n    val nearbyDisplayName: String? = null, val nameUpdatePresent: Boolean = false)',
    'data class DiscoveryAdvertisement(val token: String, val version: Int, val flags: Int)');
  x.one('    val nearbyDisplayName: String? = null,\n');
  x.one('            old?.firstSeenAt ?: now, now, ad.version, ad.flags,\n            if (ad.nameUpdatePresent) ad.nearbyDisplayName else old?.nearbyDisplayName)',
    '            old?.firstSeenAt ?: now, now, ad.version, ad.flags)');
  x.re(/    \/\*\* A separately delivered scan response[\s\S]*?(?=    fun expire\()/);
});
edit(a + 'nearby/NearbyDiscoveryManager.kt', x => {
  x.one('    val nameBroadcastEnabled: Boolean = false,\n    val currentNearbyName: String? = null,\n    val nearbyNameBytes: Int = 0,\n    val primaryAdvertisementBytes: Int = BleDiscoveryProtocol.MAX_LEGACY_ADVERTISEMENT_BYTES,\n    val scanResponseBytes: Int = 0,\n');
  x.one('class NearbyDiscoveryManager(private val context: Context, private val logger: NetworkLogger,\n    private val displayName: () -> String = { "DinoBlog User" },\n    private val showName: () -> Boolean = { true }) {',
    'class NearbyDiscoveryManager(private val context: Context, private val logger: NetworkLogger) {');
  x.one('    private val nameServiceUuid = ParcelUuid(BleDiscoveryProtocol.NAME_SERVICE_UUID)\n');
  x.one('    private val pendingNames = mutableMapOf<BluetoothDevice, ByteArray>()\n');
  x.re(/    \/\*\* Profile changes update scan response[\s\S]*?(?=    fun startVisible\()/);
  x.one('        pendingNames.clear()\n');
  x.one('        val nameFilter = ScanFilter.Builder().setServiceData(nameServiceUuid, byteArrayOf(0), byteArrayOf(0)).build()\n');
  x.one('startScan(listOf(filter, nameFilter), settings, callback)', 'startScan(listOf(filter), settings, callback)');
  x.re(/        val record = result.scanRecord\n[\s\S]*?        val ad = BleDiscoveryProtocol.decode\(primary, nameData \?: pendingNames.remove\(result.device\)\)/,
    '        val ad = BleDiscoveryProtocol.decode(result.scanRecord?.getServiceData(serviceUuid))');
  x.one(' rssi=${result.rssi} named=${ad.nearbyDisplayName != null}', ' rssi=${result.rssi}');
  x.re(/        val namePayload = BleDiscoveryProtocol.encodeName[\s\S]*?(?=        val settings = AdvertiseSettings.Builder\()/);
  x.one('startAdvertising(settings, data, response, callback)', 'startAdvertising(settings, data, callback)');
  x.one('                nameBroadcastEnabled = showName(),\n                currentNearbyName = BleDiscoveryProtocol.decodeName(namePayload),\n                nearbyNameBytes = if (showName()) namePayload.size else 0,\n                scanResponseBytes = BleDiscoveryProtocol.SCAN_RESPONSE_OVERHEAD_BYTES + namePayload.size,\n');
  x.one('        mutableState.value = mutableState.value.copy(advertising = false, currentToken = null, tokenCreatedAt = null,\n            currentNearbyName = null, nearbyNameBytes = 0, scanResponseBytes = 0)',
    '        mutableState.value = mutableState.value.copy(advertising = false, currentToken = null, tokenCreatedAt = null)');
});
edit(a + 'repository/ProfileRepository.kt', x => {
  x.one('    val nearbyPromptShown: Boolean, val nearbyConnectionMethod: NearbyConnectionMethod = NearbyConnectionMethod.AUTO,\n    val showNearbyDisplayName: Boolean = true)',
    '    val nearbyPromptShown: Boolean, val nearbyConnectionMethod: NearbyConnectionMethod = NearbyConnectionMethod.AUTO)');
  x.one('        preferences.getBoolean("show_nearby_display_name", true),\n');
  x.re(/    fun setShowNearbyDisplayName\(enabled: Boolean\): Boolean \{[\s\S]*?    \}\n\n/);
});
edit(a + 'DinoblogApplication.kt', x => x.one('    val nearby by lazy { NearbyDiscoveryManager(application, logger,\n        { profile.displayName }, { profile.profile.value.showNearbyDisplayName }) }',
  '    val nearby by lazy { NearbyDiscoveryManager(application, logger) }'));
edit(a + 'viewmodel/AppViewModel.kt', x => {
  x.one('import com.group16.dinoblog.model.WifiDirectDiscoveryResult\n');
  x.one('    private val mutableSelectedWifiResult = MutableStateFlow<WifiDirectDiscoveryResult?>(null)\n    val selectedWifiResult = mutableSelectedWifiResult.asStateFlow()\n');
  x.re(/        viewModelScope.launch \{\n            network.collect \{ wifi ->[\s\S]*?        \}\n(?=    \}\n\n    fun selectRecipient)/);
  x.re(/    fun setShowNearbyDisplayName\(enabled: Boolean\) \{[\s\S]*?    \}\n(?=    fun setNearbyDiscoverableEnabled)/);
  x.one('        mutableSelectedWifiResult.value = app.wifi.state.value.peers.firstOrNull { it.addressInternal == address }\n');
  x.one('        mutableSelectedWifiResult.value = null\n');
  x.one('val peer = app.wifi.state.value.peers.firstOrNull { it.addressInternal == address }',
    'val peer = app.wifi.state.value.peers.firstOrNull { it.deviceAddress == address }');
});
edit(a + 'model/NetworkState.kt', x => {
  x.one('package com.group16.dinoblog.model\n\n',
    'package com.group16.dinoblog.model\n\nimport android.net.wifi.p2p.WifiP2pDevice\n\n');
  x.one('val peers: List<WifiDirectDiscoveryResult> = emptyList()',
    'val peers: List<WifiP2pDevice> = emptyList()');
});
edit(a + 'network/WifiDirectManager.kt', x => {
  x.one('import com.group16.dinoblog.model.WifiDirectDeviceNames\nimport com.group16.dinoblog.model.WifiDirectDiscoveryResult\nimport com.group16.dinoblog.model.WifiDirectPeerStatus\n');
  x.re(/            val peers = list\.deviceList\.mapNotNull \{ device ->[\s\S]*?            mutableState\.update \{ it\.copy\(peers = peers\) \}/,
    '            val peers = list.deviceList.toList()\n            mutableState.update { it.copy(peers = peers) }');
  x.re(/    private fun peerStatus\(status: Int\): WifiDirectPeerStatus = when \(status\) \{[\s\S]*?    \}\n(?=\})/);
});
}
edit(a + 'ui/PeerDiscoveryScreen.kt', x => {
  x.one('import com.group16.dinoblog.model.WifiDirectDiscoveryResult\nimport com.group16.dinoblog.model.WifiDirectPeerStatus\nimport com.group16.dinoblog.model.NearbyWifiCorrelation\n',
    'import android.net.wifi.p2p.WifiP2pDevice\n');
  x.one('    selectedWifiAddress: String?, selectedWifiResult: WifiDirectDiscoveryResult?,\n',
    '    selectedWifiAddress: String?,\n');
  x.re(/    \/\/ Only an explicit Phase 17 handoff address[\s\S]*?(?=    LazyColumn\()/,
    '    val visibleWifiResults = network.peers\n');
  x.one('            Text("Nearby names are unverified hints. DinoBlog verifies identity after connecting.")',
    '            Text("DinoBlog verifies identity after connecting.")');
  x.one('            val thisHandoffWifi = handoffWifi?.takeIf { selectedNearbyToken == result.discoveryToken }\n');
  x.re(/            InfoCard\(if \(verified\) connectedName else "\$\{result\.nearbyDisplayName \?: "DinoBlog device"\}[^\n]*\n/,
    '            InfoCard(if (verified) connectedName else "DinoBlog device ${result.discoveryToken.take(8)}") {\n');
  x.re(/                thisHandoffWifi\?\.let \{[\s\S]*?                \}\n(?=                Text\(if \(verified\))/);
  x.one('            Text("Android device names are unverified. Connect to verify DinoBlog identity.")\n');
  x.one('            if (socket.connected && selectedNearbyToken == null && selectedWifiAddress == null)',
    '            if (socket.connected && selectedNearbyToken == null)');
  x.re(/        items\(visibleWifiResults, key = \{ "wifi:\$\{it\.addressInternal\}" \}\) \{ peer ->[\s\S]*?        \}\n(?=        item \{ Text\("Nearby results)/,
    `        items(visibleWifiResults, key = { "wifi:\${it.deviceAddress}" }) { peer ->
            val selected = selectedWifiAddress == peer.deviceAddress && selectedNearbyToken == null
            val verified = selected && socket.connected && socket.peerAuthState == "AUTHENTICATED" &&
                socket.transportType == "WIFI_DIRECT"
            InfoCard(if (verified) connectedName else "Wi-Fi Direct peer") {
                Text("Address: \${peer.deviceAddress}")
                Text(when {
                    verified -> "Connected via Wi-Fi Direct"
                    selected && socket.awaitingHello -> "Verifying identityâ€¦"
                    selected && network.groupFormed -> "Verifying identityâ€¦"
                    selected && network.wifiStatus.startsWith("Connecting") -> "Connectingâ€¦"
                    peer.status == WifiP2pDevice.AVAILABLE -> "Available"
                    peer.status == WifiP2pDevice.CONNECTED -> "Connected"
                    else -> "Unavailable"
                })
                Text(if (verified) "Verified" else "Not verified",
                    style = MaterialTheme.typography.bodySmall)
                if (!verified) Button(onClick = { onConnect(peer.deviceAddress) },
                    enabled = !network.groupFormed && !socket.connected && !nearbyConnecting &&
                        connectionMethod != NearbyConnectionMethod.BLUETOOTH &&
                        peer.status == WifiP2pDevice.AVAILABLE) { Text("Connect") }
            }
        }
`);
});
edit(a + 'ui/ProfileScreens.kt', x => {
  x.one('    nearbyAdvertiserSupported: Boolean, onShowNearbyName: (Boolean) -> Unit,',
    '    nearbyAdvertiserSupported: Boolean,');
  x.re(/            Row\(Modifier\.fillMaxWidth\(\), horizontalArrangement = Arrangement\.SpaceBetween,\n                verticalAlignment = Alignment\.CenterVertically\) \{\n                Text\("Show my DinoBlog name nearby"[\s\S]*?(?=            if \(!nearbyAdvertiserSupported)/);
});
edit(a + 'ui/DinoblogApp.kt', x => {
  x.one('    val selectedWifiResult by viewModel.selectedWifiResult.collectAsStateWithLifecycle()\n');
  x.one("Your DinoBlog name may be visible before connection when 'Show my DinoBlog name nearby' is on. ", '');
  x.one('                        selectedWifiAddress, selectedWifiResult, profile.nearbyConnectionMethod,',
    '                        selectedWifiAddress, profile.nearbyConnectionMethod,');
  x.one('                        viewModel::setShowNearbyDisplayName, viewModel::setNearbyConnectionMethod,',
    '                        viewModel::setNearbyConnectionMethod,');
});
edit(a + 'ui/NetworkDebugScreen.kt', x => {
  x.re(/            Detail\("Nearby name broadcast"[\s\S]*?(?=            Detail\("Token age")/);
  x.re(/            Detail\("Wi-Fi peers", network\.peers\.joinToString\("\\n"\) \{[^\n]*\}\.[^\n]*\n/,
    '            Detail("Wi-Fi peers", network.peers.joinToString("\\n") { it.deviceAddress }.ifEmpty { "None" })\n');
});
edit('app/src/test/java/com/group16/dinoblog/nearby/BleDiscoveryProtocolTest.kt', x =>
  x.re(/    @Test fun scanResponseFitsLegacyLimitAndNeverContainsIdentity\(\) \{[\s\S]*?(?=    @Test fun payloadIsExactlyTenBytesAndVersioned)/));
edit('app/src/androidTest/java/com/group16/dinoblog/Phase16NearbyPreferenceTest.kt', x =>
  x.re(/    @Test fun nearbyNameVisibilityDefaultsOnAndPersistsIndependentlyOfDiscoverability\(\) \{[\s\S]*?(?=    @Test fun nearbyTransportPreferencePersists)/));
fs.unlinkSync(a + 'model/WifiDirectDiscoveryResult.kt');
fs.unlinkSync('app/src/test/java/com/group16/dinoblog/WifiDirectDiscoveryResultTest.kt');
edit('README.md', x => {
  x.re(/Phase 17\.2 displays Android Wi-Fi Direct peer names[\s\S]*?(?=Current version:)/);
  x.one('## Phase 18 and nearby discovery\n\n', '## Phase 18\n\n');
});
fs.unlinkSync('PHASE17_1_NAMED_DISCOVERY.md');
fs.unlinkSync('PHASE17_2_WIFI_NAME.md');
