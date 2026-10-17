package com.getvideo.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.getvideo.app.player.PlayerHolder
import com.getvideo.app.data.repo.PlaylistRepository
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
                FilledIconButton(onClick = { scope.launch { PlayerHolder.toggle(context) } }) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "暂停" else "继续")
                }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.List, "播放列表：$listName") }
            }

        }
    }
    if (showTimer) TimerDialog(onClose = { showTimer = false })
    if (showMode) PlaybackModeDialog(onClose = { showMode = false })
    if (showQueue) AlertDialog(onDismissRequest = { showQueue = false }, title = { Column {
        Text(listName)
        Text("共 ${queue.size} 个文件", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (timer != null) Row(verticalAlignment = Alignment.CenterVertically) {
            AreaButton(onClick = { showTimer = true }, tone = ActionTone.TIMER) { Text("定时停止 $timer") }
            AreaButton(onClick = { PlayerHolder.cancelTimer() }, tone = ActionTone.TIMER) { Text("取消定时") }
        }
    } },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(queue) { i, item ->
                val isCurrent = i == index
                Row(Modifier.fillMaxWidth().regionFrame(isCurrent).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", modifier = Modifier.widthIn(min = 24.dp).padding(end = 8.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.track.title, modifier = Modifier.weight(1f), maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick = { scope.launch {
                        if (isCurrent) PlayerHolder.toggle(context) else PlayerHolder.jump(context, i)
                    } }) {
                        Icon(if (isCurrent && playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isCurrent && playing) "暂停第${i + 1}个视频" else "播放第${i + 1}个视频",
                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    AreaButton(onClick = { scope.launch { PlayerHolder.remove(context, i) } }, tone = ActionTone.DESTRUCTIVE) { Text("移除") }
                }
            }
        } }, confirmButton = { AreaButton(onClick = { showQueue = false }) { Text("关闭") } })
}
