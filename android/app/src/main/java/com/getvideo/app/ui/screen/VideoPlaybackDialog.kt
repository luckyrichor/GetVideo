package com.getvideo.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.session.MediaController
import com.getvideo.app.player.PlayerHolder

@Composable
internal fun VideoPlaybackDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaController?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val queue by PlayerHolder.queue.collectAsState()
    val index by PlayerHolder.currentIndex.collectAsState()
    LaunchedEffect(context) { try { player = PlayerHolder.get(context) } catch (e: Exception) { error = e.message } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(queue.getOrNull(index)?.track?.title ?: "视频播放", modifier = Modifier.weight(1f), maxLines = 2)
                    TextButton(onClick = onClose) { Text("收起") }
                }
                player?.let { VideoSurface(it, Modifier.fillMaxWidth().weight(1f)) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("收起后保留播放状态，可从底部播放栏再次打开", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
        }
    }
}
