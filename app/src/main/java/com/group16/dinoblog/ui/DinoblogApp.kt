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
import com.group16.dinoblog.viewmodel.AppViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DinoblogApp(viewModel: AppViewModel) {
    val context = LocalContext.current
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val network by viewModel.network.collectAsStateWithLifecycle()
    val socket by viewModel.socket.collectAsStateWithLifecycle()
    val inventory by viewModel.inventory.collectAsStateWithLifecycle()
    val predictability by viewModel.predictability.collectAsStateWithLifecycle()
    val predictabilities by viewModel.predictabilities.collectAsStateWithLifecycle()
    val forwarding by viewModel.forwarding.collectAsStateWithLifecycle()
    val receipts by viewModel.receipts.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val allowRelay by viewModel.allowRelay.collectAsStateWithLifecycle()
    val recipient by viewModel.recipient.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    val media by viewModel.media.collectAsStateWithLifecycle()
    val selectedImage by viewModel.selectedImage.collectAsStateWithLifecycle()
    val preparingImage by viewModel.preparingImage.collectAsStateWithLifecycle()
    val mediaProgress by viewModel.mediaProgress.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("home") }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionDenied = !Permissions.granted(context)
        if (!permissionDenied) viewModel.startNetwork()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::saveProfileImage)
    }
    val chatPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::selectImage)
    }
    val requestPermissions: () -> Unit = {
        if (Permissions.granted(context)) { permissionDenied = false; viewModel.startNetwork() }
        else permissions.launch(Permissions.required())
    }
    LaunchedEffect(profile.onboardingCompleted) { if (profile.onboardingCompleted) requestPermissions() }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); viewModel.clearNotice() } }

    if (!profile.onboardingCompleted) {
        OnboardingScreen(firstRun = true, onComplete = viewModel::completeOnboarding, onClose = {})
        return
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
                NavigationDrawerItem(label = { Text("Nearby devices") }, selected = screen == "nearby", onClick = { navigate("nearby") }, icon = { Icon(Icons.Outlined.WifiFind, null) })
                NavigationDrawerItem(label = { Text("Settings") }, selected = screen == "settings", onClick = { navigate("settings") }, icon = { Icon(Icons.Outlined.SettingsIcon, null) })
                NavigationDrawerItem(label = { Text("How DinoBlog works") }, selected = false, onClick = { navigate("help") }, icon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, null) })
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Developer", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp))
                NavigationDrawerItem(label = { Text("Network Debug") }, selected = screen == "debug", onClick = { navigate("debug") }, icon = { Icon(Icons.Outlined.BugReport, null) })
            }
        }
    }) {
        Scaffold(topBar = { TopAppBar(title = { Text(when (screen) {
            "nearby" -> "Nearby devices"; "chat" -> "Chats"; "settings" -> "Settings"; "debug" -> "Network Debug"; else -> "DinoBlog"
        }) }, navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) {
            Icon(Icons.Outlined.Menu, contentDescription = "Open navigation menu")
        } }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    "home" -> HomeScreen(profile.displayName, socket, peers,
                        onDiscover = { screen = "nearby" }, onChat = { screen = "chat" },
                        onPeer = { viewModel.selectRecipient(it); screen = "chat" })
                    "nearby" -> PeerDiscoveryScreen(network, socket,
                        peers.find { it.deviceId == socket.peerId }?.displayName ?: socket.peerName ?: "DinoBlog User",
                        viewModel::discover, viewModel::connect,
                        viewModel::disconnect, requestPermissions, viewModel::startTcp,
                        { socket.peerId?.let(viewModel::selectRecipient); screen = "chat" },
                        { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                        { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
                    "chat" -> ChatScreen(viewModel.localId, recipient, peers, socket, messages, media, mediaProgress, viewModel::mediaPath,
                        selectedImage, preparingImage, draft, sending,
                        allowRelay, viewModel::setAllowRelay,
                        viewModel::selectRecipient, viewModel::setDraft, viewModel::sendMessage,
                        { chatPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        viewModel::cancelImage, viewModel::sendImage,
                        viewModel::savePeerAlias, viewModel::removePeerAlias)
                    "settings" -> SettingsScreen(profile, viewModel::saveDisplayName,
                        { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        viewModel::removeProfileImage, viewModel::setRelayEnabled, { screen = "help" },
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty())
                    "debug" -> NetworkDebugScreen(viewModel.localId, profile.relayEnabled, network, socket, logs, messages, media, inventory,
                        forwarding, receipts, predictability, predictabilities, viewModel::clearLogs,
                        viewModel::selectRecipient, { screen = "chat" }, viewModel::retryPending)
                }
            }
        }
    }
}
