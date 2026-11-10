package com.group16.dinoblog.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.group16.dinoblog.model.DisplayNames
import com.group16.dinoblog.model.Message
import com.group16.dinoblog.model.MessageContentType
import com.group16.dinoblog.model.MediaEntity
import com.group16.dinoblog.model.MessageStatus
import com.group16.dinoblog.model.Peer
import com.group16.dinoblog.model.SocketState
import com.group16.dinoblog.viewmodel.AppViewModel
import com.group16.dinoblog.repository.PreparedMedia

@Composable
fun ChatScreen(localId: String, recipient: String, peers: List<Peer>, socket: SocketState,
    messages: List<Message>, media: List<MediaEntity>, mediaProgress: Map<String, Int>, mediaPath: (String) -> String,
    verifyMedia: suspend (String) -> Boolean,
    selectedMedia: PreparedMedia?, preparingMedia: Boolean, draft: String, sending: Boolean,
    allowRelay: Boolean, onAllowRelay: (Boolean) -> Unit,
    onRecipient: (String) -> Unit, onDraft: (String) -> Unit, onSend: () -> Unit,
    onPickImage: () -> Unit, onPickVideo: () -> Unit, onPickAudio: () -> Unit,
    onCancelMedia: () -> Unit, onSendMedia: () -> Unit,
    onAlias: (String, String) -> Unit, onRemoveAlias: (String) -> Unit) {
    val listState = rememberLazyListState()
    var peerMenu by remember { mutableStateOf(false) }
    var attachmentMenu by remember { mutableStateOf(false) }
    var editingAlias by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val player = remember(context) { ExoPlayer.Builder(context).build() }
    var playingMediaId by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var videoDialog by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { playingMediaId = null; videoDialog = false }
            }
            override fun onPlayerError(error: PlaybackException) {
                playbackError = "This ${if (videoDialog) "video" else "audio"} cannot be played on this device."
                videoDialog = false
                playingMediaId = null
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    fun play(row: MediaEntity) {
        scope.launch {
            if (!verifyMedia(row.mediaId)) { playbackError = "Media file is unavailable or unverified"; return@launch }
            if (playingMediaId == row.mediaId) {
                if (player.isPlaying) player.pause() else player.play()
            } else {
                playbackError = null
                player.setMediaItem(MediaItem.Builder()
                    .setUri(Uri.fromFile(File(mediaPath(row.mediaId))))
                    .setMimeType(row.mimeType)
                    .build())
                player.prepare()
                player.play()
                playingMediaId = row.mediaId
            }
            if (row.contentType == MessageContentType.VIDEO) videoDialog = true
        }
    }
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
                            if (message.contentType == MessageContentType.TEXT) {
                                SelectionContainer { Text(message.payload, style = MaterialTheme.typography.bodyLarge) }
                            } else {
                                val row = media.find { it.messageId == message.messageId && it.isComplete }
                                when {
                                    row == null -> Text("Media unavailable", style = MaterialTheme.typography.bodyLarge)
                                    message.contentType == MessageContentType.IMAGE -> ChatImage(mediaPath(row.mediaId), "Chat image")
                                    else -> {
                                        Text("${message.contentType.name.lowercase().replaceFirstChar { it.uppercase() }} · ${row.originalFileName}")
                                        Text("${durationLabel(row.durationMs)} · ${sizeLabel(row.fileSize)}",
                                            style = MaterialTheme.typography.bodySmall)
                                        Button(onClick = { play(row) }) {
                                            Text(if (playingMediaId == row.mediaId && isPlaying) "Pause" else "Play")
                                        }
                                    }
                                }
                            }
                            val progress = mediaProgress[message.messageId]
                            val progressLabel = if (message.contentType != MessageContentType.TEXT && outgoing && progress != null) {
                                if (progress < 100) "Sending $progress%" else "Waiting for media confirmation"
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
                    Text("Receiving ${partial.contentType.name.lowercase()} ${partial.completedChunks * 100 / partial.totalChunks}%",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (selectedMedia != null) Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedMedia.manifest.contentType == MessageContentType.IMAGE)
                    ChatImage(selectedMedia.filePath, "Selected image preview")
                else {
                    Text("${selectedMedia.manifest.contentType.name.lowercase().replaceFirstChar { it.uppercase() }}: ${selectedMedia.manifest.originalFileName}")
                    Text("${durationLabel(selectedMedia.manifest.durationMs)} · ${sizeLabel(selectedMedia.manifest.fileSize)}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancelMedia, enabled = !sending) { Text("Cancel") }
                    TextButton(onClick = onSendMedia, enabled = !sending && recipient.isNotBlank()) { Text("Send") }
                }
            }
        }
        playbackError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
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
            Box(Modifier.padding(bottom = 22.dp)) {
                FilledIconButton(onClick = { attachmentMenu = true }, enabled = recipient.isNotBlank() && !sending && !preparingMedia) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Attach media")
                }
                DropdownMenu(expanded = attachmentMenu, onDismissRequest = { attachmentMenu = false }) {
                    DropdownMenuItem(text = { Text("Photo") }, onClick = { attachmentMenu = false; onPickImage() })
                    DropdownMenuItem(text = { Text("Video") }, onClick = { attachmentMenu = false; onPickVideo() })
                    DropdownMenuItem(text = { Text("Audio") }, onClick = { attachmentMenu = false; onPickAudio() })
                }
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
    if (videoDialog) AlertDialog(onDismissRequest = { player.pause(); videoDialog = false },
        title = { Text("Video") },
        text = { AndroidView(factory = { PlayerView(it).apply { this.player = player } },
            modifier = Modifier.fillMaxWidth().height(240.dp)) },
        confirmButton = { TextButton(onClick = { player.pause(); videoDialog = false }) { Text("Close") } })
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

private fun durationLabel(milliseconds: Long?): String {
    if (milliseconds == null) return "Duration unavailable"
    val seconds = milliseconds / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private fun sizeLabel(bytes: Long): String = "%.1f MiB".format(bytes / 1_048_576.0)
