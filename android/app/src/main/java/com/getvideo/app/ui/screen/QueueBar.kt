package com.getvideo.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getvideo.app.player.PlayerHolder
import com.getvideo.app.data.repo.PlaylistRepository
import com.getvideo.app.ui.components.uiCardColor
import com.getvideo.app.ui.components.uiHairline
import com.getvideo.app.ui.components.uiMuted
import com.getvideo.app.ui.formatDuration
import kotlinx.coroutines.launch

@Composable
fun QueueBar() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val queue by PlayerHolder.queue.collectAsState()
    val sourceId by PlayerHolder.sourcePlaylistId.collectAsState()
    val playlists by remember(context) { PlaylistRepository(context).observeAll() }.collectAsState(initial = emptyList())
    val listName = playlists.firstOrNull { it.id == sourceId }?.name ?: playlists.firstOrNull { it.isDefault }?.name ?: "播放列表"
    val index by PlayerHolder.currentIndex.collectAsState()
    val playing by PlayerHolder.isPlaying.collectAsState()
    val mode by PlayerHolder.mode.collectAsState()
    val timer = timerStatus()
    var showTimer by remember { mutableStateOf(false) }
    var showMode by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showVideo by remember { mutableStateOf(false) }
    val currentId = queue.getOrNull(index)?.track?.id
    LaunchedEffect(currentId) { if (currentId != null) showVideo = true }
    if (queue.isNotEmpty()) {
        Column {
            if (showVideo) VideoPlaybackPanel { showVideo = false }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().height(88.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp).clickable { showVideo = true }) {
                    Text(queue.getOrNull(index)?.track?.title ?: queue.first().track.title,
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    key(queue.getOrNull(index)?.track?.id) {
                        PlaybackProgress(queue.getOrNull(index)?.track?.id ?: queue.first().track.id)
                    }
                }
                Column(Modifier.width(64.dp).height(56.dp).clickable { showMode = true }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(playbackModeIcon(mode), mode.label, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(mode.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                }
                IconButton(onClick = { scope.launch { PlayerHolder.next(context) } }) {
                    Icon(Icons.Default.SkipNext, "下一个")
                }
                FilledIconButton(onClick = { scope.launch { PlayerHolder.toggle(context) } }) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "暂停" else "继续")
                }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.List, "播放列表：$listName") }
            }

        }
    }
    if (showTimer) TimerDialog(onClose = { showTimer = false })
    if (showMode) PlaybackModeDialog(onClose = { showMode = false })
    if (showQueue) MusicSheet(
        title = listName,
        onDismiss = { showQueue = false },
        subtitle = if (timer != null) "定时停止：$timer" else "长按拖动调整播放顺序",
        badge = if (queue.size == 1) "1 个" else "${queue.size} 个",
        scroll = false,
        confirmText = "完成",
        onConfirm = { showQueue = false },
        header = if (timer != null) {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetPill("查看定时", Icons.Default.Timer, { showQueue = false; showTimer = true })
                    SheetPill("取消定时", Icons.Default.TimerOff, { PlayerHolder.cancelTimer() })
                }
            }
        } else null,
    ) {
        ReorderableQueue(
            queue = queue, currentId = queue.getOrNull(index)?.track?.id, playing = playing,
            onReorder = { ids -> PlayerHolder.reorder(context, sourceId, ids) },
            onPlay = { id -> scope.launch {
                val position = PlayerHolder.queue.value.indexOfFirst { it.track.id == id }
                if (position >= 0) {
                    if (position == PlayerHolder.currentIndex.value) PlayerHolder.toggle(context)
                    else PlayerHolder.jump(context, position)
                }
            } },
            onRemove = { id -> scope.launch {
                val position = PlayerHolder.queue.value.indexOfFirst { it.track.id == id }
                if (position >= 0) PlayerHolder.remove(context, position)
            } },
        )
    }
}
