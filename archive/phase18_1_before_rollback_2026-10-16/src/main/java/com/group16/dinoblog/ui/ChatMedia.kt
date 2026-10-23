package com.group16.dinoblog.ui

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.group16.dinoblog.model.MediaEntity
import com.group16.dinoblog.model.MessageContentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private fun durationLabel(ms: Long?): String {
    val seconds = (ms ?: 0L) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
fun ChatMedia(media: MediaEntity, path: String) {
    when (media.mediaType) {
        MessageContentType.IMAGE -> ChatImage(path, "Chat image")
        MessageContentType.AUDIO -> AudioBubble(path, media.durationMs)
        MessageContentType.VIDEO -> VideoBubble(path, media.durationMs)
        MessageContentType.TEXT -> Unit
    }
}

@Composable
private fun AudioBubble(path: String, durationMs: Long?) {
    val active by AudioPlayback.active.collectAsState()
    var progress by remember(path) { mutableIntStateOf(0) }
    DisposableEffect(path) { onDispose { AudioPlayback.release(path) } }
    LaunchedEffect(active, path) {
        while (active == path) {
            progress = AudioPlayback.position(path)
            delay(250)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Audio · ${durationLabel(durationMs)}")
        TextButton(onClick = { AudioPlayback.toggle(path) }) { Text(if (active == path) "Pause" else "Play") }
        LinearProgressIndicator(progress = { (progress.toFloat() / (durationMs ?: 1L).coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth())
    }
}

/** One decoder for the entire chat. A bubble releases it when it leaves composition. */
private object AudioPlayback {
    private var player: MediaPlayer? = null
    private val mutableActive = MutableStateFlow<String?>(null)
    val active = mutableActive.asStateFlow()

    fun toggle(path: String) {
        if (mutableActive.value == path) { release(path); return }
        player?.release(); player = null; mutableActive.value = null
        runCatching {
            MediaPlayer().apply {
                setDataSource(path); prepare()
                setOnCompletionListener { release(path) }
                start()
            }
        }.onSuccess { player = it; mutableActive.value = path }
            .onFailure { player?.release(); player = null }
    }
    fun position(path: String) = if (mutableActive.value == path) runCatching { player?.currentPosition ?: 0 }.getOrDefault(0) else 0
    fun release(path: String) {
        if (mutableActive.value != path) return
        player?.release(); player = null; mutableActive.value = null
    }
}

@Composable
private fun VideoBubble(path: String, durationMs: Long?) {
    var open by remember(path) { mutableStateOf(false) }
    val thumbnail = remember(path) { runCatching {
        val retriever = MediaMetadataRetriever()
        try { retriever.setDataSource(path); retriever.getFrameAtTime(0,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
        finally { retriever.release() }
    }.getOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (thumbnail != null) Image(thumbnail.asImageBitmap(), "Video preview",
            Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Crop)
        Text("Video · ${durationLabel(durationMs)}")
        TextButton(onClick = { open = true }) { Text("Play video") }
    }
    if (open) Dialog(onDismissRequest = { open = false }) {
        AndroidView(factory = { context -> VideoView(context).apply {
            setVideoPath(path)
            setOnPreparedListener { it.isLooping = false; start() }
        } }, modifier = Modifier.fillMaxWidth().height(320.dp),
            onRelease = { it.stopPlayback() })
    }
}
