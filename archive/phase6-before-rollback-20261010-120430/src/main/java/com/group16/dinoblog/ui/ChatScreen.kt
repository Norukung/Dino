package com.group16.dinoblog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.model.NetworkState
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.viewmodel.AppViewModel
import com.group16.dinoblog.network.WireProtocol

@Composable
fun ChatScreen(localId: String, recipient: String, peers: List<Peer>, network: NetworkState,
    socket: SocketState, messages: List<Message>, draft: String, ttlInput: String, sending: Boolean,
    onRecipient: (String) -> Unit, onDraft: (String) -> Unit, onTtl: (String) -> Unit,
    onSend: (Boolean, Int) -> Unit, onStartTcp: () -> Unit, onRetry: () -> Unit) {
    val listState = rememberLazyListState()
    var peerMenu by remember { mutableStateOf(false) }
    var showTtl by rememberSaveable { mutableStateOf(false) }
    var viaCarrier by rememberSaveable { mutableStateOf(false) }
    var hopLimitInput by rememberSaveable { mutableStateOf("8") }
    val hopLimit = if (viaCarrier) hopLimitInput.toIntOrNull() else 8
    val validTransfer = hopLimit != null && hopLimit in 1..255 &&
        (!viaCarrier || (socket.connected && socket.peerId != recipient))
    val validRecipient = WireProtocol.isUuid(recipient) && recipient != localId
    val ttl = ttlInput.toLongOrNull()
    val validTtl = ttl != null && ttl in 1..WireProtocol.MAX_TTL_SECONDS
    val conversation = remember(messages, recipient, localId) {
        messages.filter { (it.senderId == localId && it.receiverId == recipient) ||
            (it.senderId == recipient && it.receiverId == localId) }
    }
    LaunchedEffect(recipient, conversation.lastOrNull()?.messageId) {
        if (conversation.isNotEmpty()) listState.animateScrollToItem(conversation.lastIndex)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Store & carry messages", style = MaterialTheme.typography.titleLarge)
                Box {
                    TextButton(onClick = { peerMenu = true }, enabled = !sending) { Text("Choose peer") }
                    DropdownMenu(expanded = peerMenu, onDismissRequest = { peerMenu = false }) {
                        socket.peerId?.let { id ->
                            DropdownMenuItem(text = { Text("Connected: ${socket.peerName}") }, onClick = { onRecipient(id); peerMenu = false })
                        }
                        peers.filter { it.deviceId != socket.peerId }.forEach { peer ->
                            DropdownMenuItem(text = { Text("${peer.name} · ${peer.deviceId.take(8)}") }, onClick = { onRecipient(peer.deviceId); peerMenu = false })
                        }
                        if (peers.isEmpty() && socket.peerId == null) {
                            DropdownMenuItem(text = { Text("Connect a phone or paste its ID below") }, onClick = { peerMenu = false })
                        }
                    }
                }
            }
            OutlinedTextField(value = recipient, onValueChange = onRecipient, modifier = Modifier.fillMaxWidth(),
                label = { Text("Final destination Device ID") }, singleLine = true, enabled = !sending,
                isError = recipient.isNotEmpty() && !validRecipient)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = viaCarrier, onCheckedChange = { viaCarrier = it }, enabled = !sending)
                Text("Use connected carrier / set hop limit", style = MaterialTheme.typography.labelMedium)
            }
            if (viaCarrier) {
                Text("Carrier: ${socket.peerId ?: "not connected"}\nCarriers keep a copy and can pass it to other carriers on later connections.",
                    style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(value = hopLimitInput,
                    onValueChange = { hopLimitInput = it.filter(Char::isDigit).take(3) },
                    label = { Text("Hop limit (1-255)") }, singleLine = true, enabled = !sending,
                    isError = hopLimit == null || hopLimit !in 1..255,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Text(if (viaCarrier) "FORWARDED confirms storage on the carrier; final delivery is still pending"
                else if (validRecipient && socket.peerId == recipient) "Connected · delivery confirmed by ACK"
                else if (validRecipient) "Recipient offline · messages will queue as PENDING"
                else "Choose a peer or paste its Device ID to begin",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Queued messages automatically travel through available carriers toward their final destination.",
                style = MaterialTheme.typography.labelSmall)
            if (network.groupFormed && !socket.connected) {
                TextButton(onClick = onStartTcp) { Text("TCP reconnecting · restart now") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (conversation.isEmpty()) item { Text("Start a conversation. Messages are saved on this phone, including while your peer is offline.") }
            items(conversation, key = { it.messageId }) { message ->
                val outgoing = message.senderId == localId
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
                    Card(Modifier.widthIn(max = 320.dp).fillMaxWidth(0.88f),
                        colors = CardDefaults.cardColors(containerColor = if (outgoing)
                            MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (outgoing) "You" else "Peer", style = MaterialTheme.typography.labelSmall)
                            SelectionContainer { Text(message.payload, style = MaterialTheme.typography.bodyLarge) }
                            if (message.carrierId != null) Text("Via ${message.carrierId.take(8)} | hops ${message.hopCount}/${message.hopLimit}",
                                style = MaterialTheme.typography.labelSmall)
                            Text("${timeLabel(message.timestamp)} · ${message.status.name}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (message.status == MessageStatus.EXPIRED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            Text("TTL ${message.ttl}s · ${message.messageId.take(8)}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { showTtl = !showTtl }) { Text("TTL: ${ttlInput}s · edit") }
            TextButton(onClick = onRetry) { Text("Retry now") }
        }
        if (showTtl) OutlinedTextField(value = ttlInput, onValueChange = onTtl,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text("TTL seconds (1–604800)") }, singleLine = true, enabled = !sending,
            isError = !validTtl, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft, onValueChange = onDraft, modifier = Modifier.weight(1f),
                label = { Text("Message") }, placeholder = { Text("Type a message…") },
                supportingText = { Text("${draft.length}/${AppViewModel.MAX_MESSAGE_CHARS}") },
                minLines = 1, maxLines = 4, enabled = !sending)
            FilledIconButton(onClick = { onSend(viaCarrier, hopLimit ?: 8) }, enabled = validRecipient && validTtl && validTransfer && draft.isNotBlank() && !sending,
                modifier = Modifier.padding(bottom = 22.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = if (sending) "Sending" else "Send message")
            }
        }
    }
}
