package com.group16.dinoblog.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.group16.dinoblog.util.Permissions
import com.group16.dinoblog.viewmodel.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DinoblogApp(viewModel: AppViewModel) {
    val context = LocalContext.current
    val network by viewModel.network.collectAsStateWithLifecycle()
    val socket by viewModel.socket.collectAsStateWithLifecycle()
    val inventory by viewModel.inventory.collectAsStateWithLifecycle()
    val forwarding by viewModel.forwarding.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val receipts by viewModel.receipts.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val recipient by viewModel.recipient.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val ttlInput by viewModel.ttlInput.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableIntStateOf(0) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionDenied = !Permissions.granted(context)
        if (!permissionDenied) viewModel.startNetwork()
    }
    val requestPermissions: () -> Unit = {
        if (Permissions.granted(context)) { permissionDenied = false; viewModel.startNetwork() }
        else launcher.launch(Permissions.required())
    }
    LaunchedEffect(Unit) { requestPermissions() }
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); viewModel.clearNotice() }
    }
    val labels = listOf("Device", "Peers", "Chat", "Debug")
    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.WifiFind, Icons.Outlined.ChatBubbleOutline, Icons.Outlined.BugReport)
    Scaffold(
        topBar = { TopAppBar(title = {
            Column {
                Text("Dinoblog", style = MaterialTheme.typography.headlineSmall)
                Text("DELIVERY RECEIPTS · 2.8", style = MaterialTheme.typography.labelSmall)
            }
        }) },
        bottomBar = { NavigationBar {
            labels.forEachIndexed { index, label ->
                NavigationBarItem(selected = screen == index, onClick = { screen = index },
                    icon = { Icon(icons[index], contentDescription = label) }, label = { Text(label) })
            }
        } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                0 -> HomeScreen(viewModel.localId, viewModel.localName, network, socket, peers, messages,
                    onDiscover = { screen = 1 }, onChat = { screen = 2 }, onRefresh = requestPermissions,
                    onPeer = { viewModel.selectRecipient(it); screen = 2 },
                    permissionDenied = permissionDenied,
                    onSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) })
                1 -> PeerDiscoveryScreen(network, socket, viewModel::discover, viewModel::connect,
                    viewModel::disconnect, requestPermissions, viewModel::startTcp,
                    { socket.peerId?.let(viewModel::selectRecipient); screen = 2 },
                    { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                    { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
                2 -> ChatScreen(viewModel.localId, recipient, peers, network, socket, messages, draft, ttlInput, sending,
                    viewModel::selectRecipient, viewModel::setDraft, viewModel::setTtlInput,
                    viewModel::sendMessage, viewModel::startTcp, { viewModel.retryPending() })
                3 -> NetworkDebugScreen(viewModel.localId, network, socket, logs, messages, inventory, forwarding, receipts, viewModel::clearLogs)
            }
        }
    }
}
