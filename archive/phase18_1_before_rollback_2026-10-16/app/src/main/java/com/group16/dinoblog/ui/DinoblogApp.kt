package com.group16.dinoblog.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings as SettingsIcon
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.group16.dinoblog.util.Permissions
import com.group16.dinoblog.nearby.BlePermissions
import com.group16.dinoblog.viewmodel.AppViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DinoblogApp(viewModel: AppViewModel) {
    val context = LocalContext.current
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val nearby by viewModel.nearby.collectAsStateWithLifecycle()
    val bluetooth by viewModel.bluetooth.collectAsStateWithLifecycle()
    val handoff by viewModel.handoff.collectAsStateWithLifecycle()
    val nearbyConnecting by viewModel.nearbyConnecting.collectAsStateWithLifecycle()
    val selectedNearbyToken by viewModel.selectedNearbyToken.collectAsStateWithLifecycle()
    val selectedNearbyResult by viewModel.selectedNearbyResult.collectAsStateWithLifecycle()
    val selectedWifiAddress by viewModel.selectedWifiAddress.collectAsStateWithLifecycle()
    val selectedWifiResult by viewModel.selectedWifiResult.collectAsStateWithLifecycle()
    val network by viewModel.network.collectAsStateWithLifecycle()
    val socket by viewModel.socket.collectAsStateWithLifecycle()
    val inventory by viewModel.inventory.collectAsStateWithLifecycle()
    val predictability by viewModel.predictability.collectAsStateWithLifecycle()
    val predictabilities by viewModel.predictabilities.collectAsStateWithLifecycle()
    val forwarding by viewModel.forwarding.collectAsStateWithLifecycle()
    val receipts by viewModel.receipts.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val recallStates by viewModel.recallStates.collectAsStateWithLifecycle()
    val revokes by viewModel.revokes.collectAsStateWithLifecycle()
    val revokeResults by viewModel.revokeResults.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val composerRelayPolicy by viewModel.composerRelayPolicy.collectAsStateWithLifecycle()
    val recipient by viewModel.recipient.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val peerIdentities by viewModel.peerIdentities.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val media by viewModel.media.collectAsStateWithLifecycle()
    val server by viewModel.server.collectAsStateWithLifecycle()
    val onlineMessages by viewModel.onlineMessages.collectAsStateWithLifecycle()
    val selectedMedia by viewModel.selectedMedia.collectAsStateWithLifecycle()
    val preparingMedia by viewModel.preparingMedia.collectAsStateWithLifecycle()
    val mediaProgress by viewModel.mediaProgress.collectAsStateWithLifecycle()
    val experiment by viewModel.experiment.collectAsStateWithLifecycle()
    val experimentDashboard by viewModel.experimentDashboard.collectAsStateWithLifecycle()
    val experimentSessions by viewModel.experimentSessions.collectAsStateWithLifecycle()
    var exportExperimentId by remember { mutableStateOf<String?>(null) }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = exportExperimentId
        if (uri != null && id != null) viewModel.exportExperiment(id, uri)
        exportExperimentId = null
    }
    var screen by rememberSaveable { mutableStateOf("home") }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var awaitingAdvertisePermission by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionDenied = !Permissions.granted(context)
        if (!permissionDenied) viewModel.startNetwork()
    }
    val scanPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshNearbyStatus()
        if (BlePermissions.hasScan(context)) viewModel.openNearby()
    }
    val advertisePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshNearbyStatus()
        if (BlePermissions.hasAdvertise(context)) viewModel.setNearbyDiscoverableEnabled(true)
        awaitingAdvertisePermission = false
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::saveProfileImage)
    }
    val chatPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.selectMedia(it, com.group16.dinoblog.model.MessageContentType.IMAGE) }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.selectMedia(it, com.group16.dinoblog.model.MessageContentType.AUDIO) }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.selectMedia(it, com.group16.dinoblog.model.MessageContentType.VIDEO) }
    }
    val requestPermissions: () -> Unit = {
        if (Permissions.granted(context)) { permissionDenied = false; viewModel.startNetwork() }
        else permissions.launch(Permissions.required())
    }
    val requestNearbyScan: () -> Unit = {
        if (BlePermissions.hasScan(context)) viewModel.openNearby()
        else scanPermissions.launch(BlePermissions.scan())
    }
    val setNearbyDiscoverable: (Boolean) -> Unit = { enabled ->
        if (!enabled) viewModel.setNearbyDiscoverableEnabled(false)
        else if (BlePermissions.hasAdvertise(context)) viewModel.setNearbyDiscoverableEnabled(true)
        else {
            awaitingAdvertisePermission = true
            advertisePermissions.launch(BlePermissions.advertise())
        }
    }
    val nearbyScreenActive = screen == "nearby"
    DisposableEffect(nearbyScreenActive) {
        onDispose { if (nearbyScreenActive) viewModel.closeNearby() }
    }
    LaunchedEffect(screen, profile.nearbyPromptShown, awaitingAdvertisePermission) {
        if (screen == "nearby" && profile.nearbyPromptShown && !awaitingAdvertisePermission) requestNearbyScan()
    }
    LaunchedEffect(profile.onboardingCompleted) { if (profile.onboardingCompleted) requestPermissions() }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); viewModel.clearNotice() } }

    if (!profile.onboardingCompleted) {
        OnboardingScreen(firstRun = true, onComplete = viewModel::completeOnboarding, onClose = {})
        return
    }
    if (screen == "nearby" && !profile.nearbyPromptShown) {
        AlertDialog(onDismissRequest = { viewModel.markNearbyPromptShown() },
            title = { Text("Nearby DinoBlog Users") },
            text = { Text("Bluetooth can find nearby DinoBlog users. Allow others to discover this phone while DinoBlog is open? Your DinoBlog name may be visible before connection when 'Show my DinoBlog name nearby' is on. Your permanent ID is not advertised.") },
            confirmButton = { TextButton(onClick = {
                setNearbyDiscoverable(true)
                viewModel.markNearbyPromptShown()
            }) { Text("Allow discoverability") } },
            dismissButton = { TextButton(onClick = { viewModel.markNearbyPromptShown() }) { Text("Scan only") } })
    }
    if (bluetooth.incomingRequest) {
        AlertDialog(onDismissRequest = viewModel::declineBluetoothIncoming,
            title = { Text("Nearby connection request") },
            text = { Text("A nearby DinoBlog device wants to connect. Its identity will be verified after you accept.") },
            confirmButton = { TextButton(onClick = viewModel::acceptBluetoothIncoming) { Text("Accept") } },
            dismissButton = { TextButton(onClick = viewModel::declineBluetoothIncoming) { Text("Decline") } })
    }
    if (handoff.incomingRequest) {
        AlertDialog(onDismissRequest = viewModel::declineHandoffIncoming,
            title = { Text("Nearby connection request") },
            text = { Text("An anonymous nearby DinoBlog device wants to connect. Its identity will be verified after connection.") },
            confirmButton = { TextButton(onClick = viewModel::acceptHandoffIncoming) { Text("Accept") } },
            dismissButton = { TextButton(onClick = viewModel::declineHandoffIncoming) { Text("Decline") } })
    }
    if (screen == "help") {
        OnboardingScreen(firstRun = false, onComplete = { _, _ -> true }, onClose = { screen = "home" })
        return
    }
    val navigate: (String) -> Unit = { destination ->
        screen = destination
        scope.launch { drawerState.close() }
    }
    ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
        ModalDrawerSheet {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProfileAvatar(profile, Modifier.size(64.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
                        Text("DinoBlog", style = MaterialTheme.typography.bodySmall)
                    }
                }
                NavigationDrawerItem(label = { Text("Chats / Home") }, selected = screen == "home", onClick = { navigate("home") }, icon = { Icon(Icons.Outlined.Home, null) })
                NavigationDrawerItem(label = { Text("Chats") }, selected = screen == "chat", onClick = { navigate("chat") }, icon = { Icon(Icons.Outlined.ChatBubbleOutline, null) })
                NavigationDrawerItem(label = { Text("Nearby DinoBlog Users") }, selected = screen == "nearby", onClick = { navigate("nearby") }, icon = { Icon(Icons.Outlined.WifiFind, null) })
                NavigationDrawerItem(label = { Text("Settings") }, selected = screen == "settings", onClick = { navigate("settings") }, icon = { Icon(Icons.Outlined.SettingsIcon, null) })
                NavigationDrawerItem(label = { Text("How DinoBlog works") }, selected = false, onClick = { navigate("help") }, icon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, null) })
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Developer", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp))
                NavigationDrawerItem(label = { Text("Network Debug") }, selected = screen == "debug", onClick = { navigate("debug") }, icon = { Icon(Icons.Outlined.BugReport, null) })
                NavigationDrawerItem(label = { Text("Experiment / Evaluation") }, selected = screen == "experiment", onClick = { navigate("experiment") }, icon = { Icon(Icons.Outlined.BugReport, null) })
            }
        }
    }) {
        Scaffold(topBar = { TopAppBar(title = { Text(when (screen) {
            "nearby" -> "Nearby DinoBlog Users"; "chat" -> "Chats"; "settings" -> "Settings"; "debug" -> "Network Debug";
            "experiment" -> "Experiment / Evaluation"; else -> "DinoBlog"
        }) }, navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) {
            Icon(Icons.Outlined.Menu, contentDescription = "Open navigation menu")
        } }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    "home" -> HomeScreen(profile.displayName, socket, peers,
                        onDiscover = { screen = "nearby" }, onChat = { screen = "chat" },
                        onPeer = { viewModel.selectRecipient(it); screen = "chat" })
                    "nearby" -> PeerDiscoveryScreen(network, socket,
                        peers.find { it.deviceId == socket.peerId }?.localAlias?.takeIf { it.isNotBlank() }
                            ?: socket.peerName ?: "DinoBlog User",
                        viewModel::discover, viewModel::connect,
                        viewModel::disconnect, requestPermissions, viewModel::startTcp,
                        { socket.peerId?.let(viewModel::selectRecipient); screen = "chat" },
                        { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                        { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                        nearby, viewModel::refreshNearby, requestNearbyScan,
                        { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                        bluetooth, handoff, nearbyConnecting, selectedNearbyToken, selectedNearbyResult,
                        selectedWifiAddress, selectedWifiResult, profile.nearbyConnectionMethod,
                        viewModel::connectNearby)
                    "chat" -> ChatScreen(viewModel.localId, recipient, peers, socket, messages, media, onlineMessages,
                        server.status == "ONLINE", mediaProgress, viewModel::mediaPath,
                        selectedMedia, preparingMedia, draft, sending,
                        composerRelayPolicy,
                        viewModel::setComposerRelayPolicy,
                        viewModel::selectRecipient, viewModel::setDraft, viewModel::sendMessage,
                        { chatPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        { audioPicker.launch("audio/*") },
                        { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                        viewModel::cancelMedia, viewModel::sendMedia,
                        viewModel::savePeerAlias, viewModel::removePeerAlias,
                        peerIdentities.find { it.deviceId == recipient }?.trustState,
                        recallStates, viewModel::cancelDelivery)
                    "settings" -> SettingsScreen(profile, viewModel::saveDisplayName,
                        { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        viewModel::removeProfileImage, viewModel::setRelayEnabled,
                        viewModel::setDefaultRelayPolicy, setNearbyDiscoverable,
                        nearby.supported && (!nearby.bluetoothEnabled || nearby.advertiserAvailable || !nearby.advertisePermission),
                        viewModel::setShowNearbyDisplayName, viewModel::setNearbyConnectionMethod, { screen = "help" },
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty())
                    "debug" -> NetworkDebugScreen(viewModel.localId, profile.relayEnabled, network, socket, logs, messages, media, inventory,
                        forwarding, receipts, predictability, predictabilities, server, onlineMessages,
                        viewModel::setServerUrl, viewModel::syncOnline, viewModel::clearLogs,
                        viewModel::selectRecipient, { screen = "chat" }, viewModel::retryPending,
                        experimentDashboard, viewModel.securityFingerprint, viewModel.lastSecurityError,
                        viewModel.lastMessageSignatureVerified, viewModel.lastReceiptSignatureVerified, nearby,
                        profile.nearbyConnectionMethod, bluetooth, handoff, viewModel.wifiHandoffAddress,
                        recallStates, revokes, revokeResults)
                    "experiment" -> ExperimentScreen(experiment, experimentDashboard, experimentSessions,
                        viewModel::startExperiment, viewModel::stopExperiment, viewModel::resetExperiment,
                        { session ->
                            exportExperimentId = session.experimentId
                            val safeLabel = session.label.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(40)
                            exportCsv.launch("dinoblog_phase14_${safeLabel}_${session.startedAt}.zip")
                        }, viewModel::deleteExperiment)
                }
            }
        }
    }
}
