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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.group16.dinoblog.model.DisplayNames
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.MediaEntity
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.viewmodel.AppViewModel
import com.group16.dinoblog.repository.PreparedImage

@Composable
fun ChatScreen(localId: String, recipient: String, peers: List<Peer>, socket: SocketState,
    messages: List<Message>, media: List<MediaEntity>, mediaProgress: Map<String, Int>, mediaPath: (String) -> String,
    selectedImage: PreparedImage?, preparingImage: Boolean, draft: String, sending: Boolean,
    allowRelay: Boolean, onAllowRelay: (Boolean) -> Unit,
    onRecipient: (String) -> Unit, onDraft: (String) -> Unit, onSend: () -> Unit,
    onPickImage: () -> Unit, onCancelImage: () -> Unit, onSendImage: () -> Unit,
    onAlias: (String, String) -> Unit, onRemoveAlias: (String) -> Unit) {
    val listState = rememberLazyListState()
    var peerMenu by remember { mutableStateOf(false) }
    var editingAlias by rememberSaveable { mutableStateOf(false) }
    val selected = peers.find { it.deviceId == recipient }
    val title = selected?.displayName ?: if (recipient == socket.peerId) socket.peerName ?: DisplayNames.FALLBACK else DisplayNames.FALLBACK
    val choices = peers + (socket.peerId?.takeIf { id -> peers.none { it.deviceId == id } }?.let {
        listOf(Peer(it, socket.peerName ?: DisplayNames.FALLBACK, System.currentTimeMillis()))
    } ?: emptyList())
    val conversation = remember(messages, recipient, localId) {
        messages.filter { (it.senderId == localId && it.receiverId == recipient) ||
            (it.senderId == recipient && it.receiverId == localId) }
    }
    LaunchedEffect(recipient, conversation.lastOrNull()?.messageId) {
        if (conversation.isNotEmpty()) listState.animateScrollToItem(conversation.lastIndex)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(if (recipient.isBlank()) "Chats" else title, style = MaterialTheme.typography.titleLarge)
                if (recipient.isNotBlank()) Text(if (socket.peerId == recipient) "Nearby now" else "Messages can be delivered later", style = MaterialTheme.typography.labelMedium)
            }
            Column {
                TextButton(onClick = { peerMenu = true }, enabled = !sending) { Text("Choose person") }
                DropdownMenu(expanded = peerMenu, onDismissRequest = { peerMenu = false }) {
                    choices.distinctBy { it.deviceId }.forEach { peer ->
                        DropdownMenuItem(text = { Text(peer.displayName) }, onClick = { onRecipient(peer.deviceId); peerMenu = false })
                    }
                }
            }
        }
        if (recipient.isNotBlank()) TextButton(onClick = { editingAlias = true }) { Text("Edit name") }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (recipient.isBlank()) item { Text("Choose someone to start a chat.") }
            else if (conversation.isEmpty()) item { Text("Start a conversation. Messages are saved on this phone while the other person is away.") }
            items(conversation, key = { it.messageId }) { message ->
                val outgoing = message.senderId == localId
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
                    Card(Modifier.widthIn(max = 320.dp).fillMaxWidth(0.88f),
                        colors = CardDefaults.cardColors(containerColor = if (outgoing)
                            MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (outgoing) "You" else title, style = MaterialTheme.typography.labelSmall)
                            if (message.contentType == MessageContentType.IMAGE) {
                                val image = media.find { it.messageId == message.messageId && it.isComplete }
                                if (image != null) ChatImage(mediaPath(image.mediaId), "Chat image")
                                else Text("Image unavailable", style = MaterialTheme.typography.bodyLarge)
                            } else SelectionContainer { Text(message.payload, style = MaterialTheme.typography.bodyLarge) }
                            val progress = mediaProgress[message.messageId]
                            val progressLabel = if (message.contentType == MessageContentType.IMAGE && outgoing && progress != null) {
                                if (progress < 100) "Sending $progress%" else "Waiting for image confirmation"
                            } else statusLabel(message.status)
                            Text("${timeLabel(message.timestamp)} · $progressLabel${if (outgoing && message.hopLimit == 1 && message.copiesRemaining == 1) " · Direct only" else ""}",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            items(media.filter { !it.isComplete && it.destinationId == localId && it.sourceId == recipient },
                key = { "receiving-${it.mediaId}" }) { partial ->
                Card(Modifier.widthIn(max = 320.dp)) {
                    Text("Receiving image ${partial.completedChunks * 100 / partial.totalChunks}%",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (selectedImage != null) Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatImage(selectedImage.filePath, "Selected image preview")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancelImage, enabled = !sending) { Text("Cancel") }
                    TextButton(onClick = onSendImage, enabled = !sending && recipient.isNotBlank()) { Text("Send image") }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Allow relay through other devices", style = MaterialTheme.typography.bodyMedium)
                Text(if (allowRelay) "Can reach this person via nearby devices" else "Wait until this person connects directly",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = allowRelay, onCheckedChange = onAllowRelay, enabled = !sending)
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledIconButton(onClick = onPickImage, enabled = recipient.isNotBlank() && !sending && !preparingImage,
                modifier = Modifier.padding(bottom = 22.dp)) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Attach image")
            }
            OutlinedTextField(value = draft, onValueChange = onDraft, modifier = Modifier.weight(1f),
                label = { Text("Message") }, supportingText = { Text("${draft.length}/${AppViewModel.MAX_MESSAGE_CHARS}") },
                minLines = 1, maxLines = 4, enabled = !sending && recipient.isNotBlank())
            FilledIconButton(onClick = onSend, enabled = recipient.isNotBlank() && draft.isNotBlank() && !sending,
                modifier = Modifier.padding(bottom = 22.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
            }
        }
    }
    if (editingAlias) {
        var alias by rememberSaveable(recipient) { mutableStateOf(selected?.localAlias.orEmpty()) }
        var error by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { editingAlias = false }, title = { Text("Edit name") },
            text = { Column {
                Text("Original name: ${selected?.name ?: socket.peerName ?: DisplayNames.FALLBACK}")
                OutlinedTextField(value = alias, onValueChange = { alias = it; error = false },
                    label = { Text("Local nickname") }, singleLine = true, isError = error,
                    supportingText = { Text(if (error) "Use up to 32 characters" else "Only visible on this phone") })
                if (selected?.localAlias != null) TextButton(onClick = { onRemoveAlias(recipient); editingAlias = false }) { Text("Remove nickname") }
            } },
            confirmButton = { TextButton(onClick = {
                if (DisplayNames.normalize(alias) != null) { onAlias(recipient, alias); editingAlias = false }
                else error = true
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editingAlias = false }) { Text("Cancel") } })
    }
}

private fun statusLabel(status: MessageStatus) = when (status) {
    MessageStatus.PENDING -> "Waiting"
    MessageStatus.CARRIED, MessageStatus.FORWARDED -> "On its way"
    MessageStatus.DELIVERED, MessageStatus.RECEIVED -> "Delivered"
    MessageStatus.EXPIRED -> "Expired"
}
