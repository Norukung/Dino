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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import com.group16.dinoblog.util.Permissions
import com.group16.dinoblog.viewmodel.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DinoblogApp(viewModel: AppViewModel) {
    val context = LocalContext.current
    val network by viewModel.network.collectAsStateWithLifecycle()
    val socket by viewModel.socket.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val sending by viewModel.sending.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableIntStateOf(0) }
    var recipient by rememberSaveable { mutableStateOf("") }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionDenied = !Permissions.granted(context)
        if (!permissionDenied) viewModel.startNetwork()
    }
    val requestPermissions: () -> Unit = {
        if (Permissions.granted(context)) { permissionDenied = false; viewModel.startNetwork() }
        else permissionLauncher.launch(Permissions.required())
    }
    val openChat: (String) -> Unit = { recipient = it; screen = 2 }
    LaunchedEffect(Unit) { requestPermissions() }
    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }
    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.WifiFind, Icons.Outlined.ChatBubbleOutline, Icons.Outlined.BugReport)
    val labels = listOf("Device", "Peers", "Chat", "Debug")

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Dinoblog", style = MaterialTheme.typography.headlineSmall)
                    Text("GROUP 16  /  OFFLINE MESSAGING", style = MaterialTheme.typography.labelSmall)
                }
            })
        },
        bottomBar = {
            NavigationBar {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(selected = screen == index, onClick = { screen = index },
                        icon = { Icon(icons[index], contentDescription = label) }, label = { Text(label) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                0 -> HomeScreen(viewModel.localId, viewModel.localName, network, socket, messages, peers,
                    onDiscover = { screen = 1 }, onChat = openChat, onRefresh = requestPermissions,
                    onDisconnect = viewModel::disconnect,
                    onSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) },
                    permissionDenied = permissionDenied)
                1 -> PeerDiscoveryScreen(network, socket, viewModel::discover, viewModel::connect, viewModel::disconnect,
                    requestPermissions, { socket.peerId?.let(openChat) },
                    { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                    { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
                2 -> ChatScreen(viewModel.localId, recipient, peers, messages, socket, sending,
                    onRecipient = { recipient = it },
                    onSend = { text, ttl, saved -> viewModel.send(recipient, text, ttl, saved) },
                    onRetry = { viewModel.retry() })
                3 -> NetworkDebugScreen(viewModel.localId, network, socket, logs, messages, viewModel::clearLogs)
            }
        }
    }
}
