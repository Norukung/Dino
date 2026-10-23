package com.group16.dinoblog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.group16.dinoblog.model.DisplayNames
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.MediaEntity
import com.group16.dinoblog.model.MediaPolicy
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.RelayPolicy
import com.group16.dinoblog.model.MessageRecall
import com.group16.dinoblog.model.RecallState
import com.group16.dinoblog.model.OnlineMessageState
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.viewmodel.AppViewModel
import com.group16.dinoblog.repository.PreparedImage

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(localId: String, recipient: String, peers: List<Peer>, socket: SocketState,
    messages: List<Message>, media: List<MediaEntity>, onlineMessages: List<OnlineMessageState>, serverOnline: Boolean,
    mediaProgress: Map<String, Int>, mediaPath: (String) -> String,
    selectedMedia: PreparedImage?, preparingMedia: Boolean, draft: String, sending: Boolean,
    relayPolicy: RelayPolicy, onRelayPolicy: (RelayPolicy) -> Unit,
    onRecipient: (String) -> Unit, onDraft: (String) -> Unit, onSend: () -> Unit,
    onPickImage: () -> Unit, onPickAudio: () -> Unit, onPickVideo: () -> Unit,
    onCancelMedia: () -> Unit, onSendMedia: () -> Unit,
    onAlias: (String, String) -> Unit, onRemoveAlias: (String) -> Unit,
    peerSecurity: String? = null, recalls: List<MessageRecall> = emptyList(),
    onCancelDelivery: (String) -> Unit = {}) {
    val listState = rememberLazyListState()
    var peerMenu by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var deliveryMenu by remember { mutableStateOf(false) }
    var editingAlias by rememberSaveable { mutableStateOf(false) }
    var confirmCancelId by rememberSaveable { mutableStateOf<String?>(null) }
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
                if (recipient.isNotBlank()) Text(if (serverOnline) "Online" else if (socket.peerId == recipient) "Nearby now" else "Offline / nearby delivery", style = MaterialTheme.typography.labelMedium)
                if (recipient.isNotBlank() && peerSecurity != null && peerSecurity != "KEY_MISMATCH")
                    Text(if (peerSecurity == "SERVER_VERIFIED") "Identity verified" else "Trusted on first contact",
                        style = MaterialTheme.typography.labelSmall)
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
        if (recipient.isNotBlank() && peerSecurity == "KEY_MISMATCH")
            Text("Security identity changed. Messages are blocked until this identity is verified.",
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
        if (recipient.isNotBlank()) TextButton(onClick = { editingAlias = true }) { Text("Edit name") }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (recipient.isBlank()) item { Text("Choose someone to start a chat.") }
            else if (conversation.isEmpty()) item { Text("Start a conversation. Messages are saved on this phone while the other person is away.") }
            items(conversation, key = { it.messageId }) { message ->
                val outgoing = message.senderId == localId
                val recall = recalls.find { it.messageId == message.messageId }?.state?.let(RecallState::valueOf)
                    ?: RecallState.NONE
                val canCancel = outgoing && recall == RecallState.NONE &&
                    message.status !in setOf(MessageStatus.DELIVERED, MessageStatus.EXPIRED)
                var showMessageMenu by remember(message.messageId) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
                    Card(Modifier.widthIn(max = 320.dp).fillMaxWidth(0.88f)
                        .combinedClickable(onClick = {}, onLongClick = { if (outgoing) showMessageMenu = true }),
                        colors = CardDefaults.cardColors(containerColor = if (outgoing)
                            MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (outgoing) "You" else title, style = MaterialTheme.typography.labelSmall)
                            if (recall == RecallState.LOCAL_CANCELLED || recall == RecallState.CONFIRMED_CANCELLED) {
                                Text("You cancelled this message", style = MaterialTheme.typography.bodyLarge)
                            } else if (message.contentType != MessageContentType.TEXT) {
                                val item = media.find { it.messageId == message.messageId && it.isComplete }
                                if (item != null) ChatMedia(item, mediaPath(item.mediaId))
                                else Text("Media unavailable", style = MaterialTheme.typography.bodyLarge)
                            } else SelectionContainer { Text(message.payload, style = MaterialTheme.typography.bodyLarge) }
                            val progress = mediaProgress[message.messageId]
                            val onlineOnly = media.find { it.messageId == message.messageId }?.let {
                                MediaPolicy.deliveryMode(it.mediaType, it.fileSize) == "ONLINE_ONLY"
                            } == true
                            val progressLabel = when (recall) {
                                RecallState.LOCAL_CANCELLED, RecallState.CONFIRMED_CANCELLED -> "Cancelled"
                                RecallState.REQUESTED -> "Cancel requested"
                                RecallState.ALREADY_DELIVERED -> "Already delivered"
                                else -> if (message.status == MessageStatus.DELIVERED) "Delivered"
                            else if (outgoing &&
                                onlineMessages.any { it.messageId == message.messageId && it.state == "SERVER_ACCEPTED" }) "Sent"
                            else if (message.contentType != MessageContentType.TEXT && outgoing && progress != null) {
                                if (progress < 100) "Sending $progress%" else "Waiting for confirmation"
                            }
                            else if (outgoing && onlineOnly && !serverOnline && message.status != MessageStatus.DELIVERED) "Waiting for Internet"
                            else if (outgoing && message.relayPolicy == RelayPolicy.DIRECT_ONLY && !serverOnline &&
                                socket.peerId != recipient && message.status == MessageStatus.PENDING) "Waiting for recipient"
                            else statusLabel(message.status)
                            }
                            Text("${timeLabel(message.timestamp)} · $progressLabel", style = MaterialTheme.typography.labelSmall)
                            DropdownMenu(expanded = showMessageMenu, onDismissRequest = { showMessageMenu = false }) {
                                if (outgoing) DropdownMenuItem(
                                    text = { Text("Delivery: ${if (message.relayPolicy == RelayPolicy.DIRECT_ONLY) "Direct only" else "Nearby relay allowed"}") },
                                    onClick = {}, enabled = false)
                                if (canCancel) DropdownMenuItem(text = { Text("Cancel delivery") }, onClick = {
                                    showMessageMenu = false; confirmCancelId = message.messageId
                                })
                            }
                        }
                    }
                }
            }
            items(media.filter { !it.isComplete && it.destinationId == localId && it.sourceId == recipient },
                key = { "receiving-${it.mediaId}" }) { partial ->
                Card(Modifier.widthIn(max = 320.dp)) {
                    Text("Receiving ${partial.mediaType.name.lowercase()} ${partial.completedChunks * 100 / partial.totalChunks}%",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (selectedMedia != null) Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedMedia.manifest.mediaType == MessageContentType.IMAGE)
                    ChatImage(selectedMedia.filePath, "Selected image preview")
                else Text("${selectedMedia.manifest.mediaType.name.lowercase().replaceFirstChar { it.uppercase() }} · ${selectedMedia.manifest.fileSize / 1024} KiB" +
                    if (MediaPolicy.deliveryMode(selectedMedia.manifest.mediaType, selectedMedia.manifest.fileSize) == "ONLINE_ONLY")
                        "\nThis ${selectedMedia.manifest.mediaType.name.lowercase()} can be sent when an Internet connection is available." else "")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancelMedia, enabled = !sending) { Text("Cancel") }
                    TextButton(onClick = onSendMedia, enabled = !sending && recipient.isNotBlank()) { Text("Send") }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TextButton(onClick = { deliveryMenu = true }, enabled = !sending) {
                Text("Delivery: ${if (relayPolicy == RelayPolicy.DIRECT_ONLY) "Direct only" else "Allow nearby relay"} ▾")
            }
            DropdownMenu(expanded = deliveryMenu, onDismissRequest = { deliveryMenu = false }) {
                DropdownMenuItem(text = { Column {
                    Text("Allow nearby relay")
                    Text("Other DinoBlog devices may carry this message.", style = MaterialTheme.typography.bodySmall)
                } }, onClick = { onRelayPolicy(RelayPolicy.ALLOW_RELAY); deliveryMenu = false })
                DropdownMenuItem(text = { Column {
                    Text("Direct only")
                    Text("Recipient or DinoBlog server only.", style = MaterialTheme.typography.bodySmall)
                } }, onClick = { onRelayPolicy(RelayPolicy.DIRECT_ONLY); deliveryMenu = false })
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledIconButton(onClick = { attachMenu = true }, enabled = recipient.isNotBlank() && !sending && !preparingMedia,
                modifier = Modifier.padding(bottom = 22.dp)) {
                Icon(Icons.Outlined.AttachFile, contentDescription = "Attach media")
            }
            DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                DropdownMenuItem(text = { Text("Photo") }, onClick = { attachMenu = false; onPickImage() })
                DropdownMenuItem(text = { Text("Audio") }, onClick = { attachMenu = false; onPickAudio() })
                DropdownMenuItem(text = { Text("Video") }, onClick = { attachMenu = false; onPickVideo() })
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
    if (confirmCancelId != null) AlertDialog(onDismissRequest = { confirmCancelId = null },
        title = { Text("Cancel delivery?") },
        text = { Text("DinoBlog will try to stop this message if it has not already reached the recipient.") },
        confirmButton = { TextButton(onClick = {
            confirmCancelId?.let(onCancelDelivery); confirmCancelId = null
        }) { Text("Cancel delivery") } },
        dismissButton = { TextButton(onClick = { confirmCancelId = null }) { Text("Keep sending") } })
}

private fun statusLabel(status: MessageStatus) = when (status) {
    MessageStatus.PENDING -> "Waiting"
    MessageStatus.CARRIED, MessageStatus.FORWARDED -> "On its way"
    MessageStatus.DELIVERED, MessageStatus.RECEIVED -> "Delivered"
    MessageStatus.EXPIRED -> "Expired"
}
