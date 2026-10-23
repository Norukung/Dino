package com.group16.dinoblog.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.network.WireProtocol

@Composable
fun ChatScreen(
    localId: String, recipient: String, peers: List<Peer>, messages: List<Message>, socket: SocketState,
    sending: Boolean, onRecipient: (String) -> Unit,
    onSend: (String, Long, () -> Unit) -> Unit, onRetry: () -> Unit,
) {
    var draft by rememberSaveable(recipient) { mutableStateOf("") }
    var ttlInput by rememberSaveable { mutableStateOf("86400") }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val conversation = messages.filter {
        (it.senderId == localId && it.receiverId == recipient) || (it.senderId == recipient && it.receiverId == localId)
    }
    val validRecipient = WireProtocol.isUuid(recipient) && recipient != localId
    val ttl = ttlInput.toLongOrNull()
    val validTtl = ttl != null && ttl in 1..WireProtocol.MAX_TTL_SECONDS
    LaunchedEffect(recipient, conversation.size) {
        if (conversation.isNotEmpty()) listState.animateScrollToItem(conversation.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Direct chat", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { menu = true }, enabled = !sending) { Text("Choose peer") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    socket.peerId?.let { id ->
                        DropdownMenuItem(text = { Text("Connected: ${socket.peerName}") }, onClick = { onRecipient(id); menu = false })
                    }
                    peers.filter { it.deviceId != socket.peerId }.forEach { peer ->
                        DropdownMenuItem(text = { Text("${peer.name} · ${peer.deviceId.take(8)}") }, onClick = { onRecipient(peer.deviceId); menu = false })
                    }
                    if (peers.isEmpty() && socket.peerId == null) DropdownMenuItem(text = { Text("Connect a peer or enter its ID below") }, onClick = { menu = false })
                }
            }
            OutlinedTextField(value = recipient, onValueChange = { onRecipient(it.trim()) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Recipient Device ID") }, singleLine = true, enabled = !sending,
                isError = recipient.isNotEmpty() && !validRecipient)
            Text(if (socket.peerId == recipient && validRecipient) "Connected · ACK confirms delivery" else "Offline · new messages will stay PENDING",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (conversation.isEmpty()) item { Text("Your conversation is stored on this phone. Choose a peer or paste its Device ID to queue a message.") }
            items(conversation, key = { it.messageId }) { message ->
                val outgoing = message.senderId == localId
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
                    Card(Modifier.widthIn(max = 320.dp), colors = CardDefaults.cardColors(
                        containerColor = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SelectionContainer { Text(message.payload) }
                            Text("${timeLabel(message.timestamp)} · ${message.status.name}", style = MaterialTheme.typography.labelSmall)
                            Text("TTL ${message.ttl}s · ${message.messageId.take(8)}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ttlInput, { ttlInput = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f),
                    label = { Text("TTL seconds (1–604800)") }, singleLine = true, isError = !validTtl,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                TextButton(onClick = onRetry) { Text("Retry now") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { if (it.length <= WireProtocol.MAX_PAYLOAD_CHARS) draft = it },
                    Modifier.weight(1f).padding(bottom = 8.dp), label = { Text("Message") }, maxLines = 4, enabled = !sending)
                IconButton(onClick = { onSend(draft, requireNotNull(ttl)) { draft = "" } },
                    enabled = validRecipient && validTtl && draft.isNotBlank() && !sending) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
                }
            }
        }
    }
}
