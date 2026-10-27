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
        subtitle = if (timer != null) "定时停止：$timer" else "点条目切换，右侧按钮移除",
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
        LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(queue) { i, item ->
                val isCurrent = i == index
                QueueRowV2(
                    index = i,
                    title = item.track.title,
                    uploader = item.track.uploader,
                    durationSec = item.track.durationSec,
                    current = isCurrent,
                    playing = isCurrent && playing,
                    onPlay = { scope.launch { if (isCurrent) PlayerHolder.toggle(context) else PlayerHolder.jump(context, i) } },
                    onRemove = { scope.launch { PlayerHolder.remove(context, i) } },
                )
            }
        }
    }
}

/** 队列行：序号 + 标题 + 圆形播放入口 + 移除，与音频版保持同一套视觉。 */
@Composable
private fun QueueRowV2(
    index: Int,
    title: String,
    uploader: String?,
    durationSec: Double?,
    current: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (current) colors.primary.copy(alpha = 0.09f) else uiCardColor(),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (current) colors.primary.copy(alpha = 0.45f) else uiHairline(),
        ),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .background(if (current) colors.primary else colors.surfaceVariant.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${index + 1}", fontSize = 11.sp,
                    color = if (current) colors.onPrimary else colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, fontSize = 14.sp, lineHeight = 19.sp,
                    fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                    color = if (current) colors.primary else colors.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(
                    uploader?.takeIf { it.isNotBlank() },
                    durationSec?.takeIf { it > 0 }?.let { formatDuration(it) },
                ).joinToString("  ·  ")
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(sub, fontSize = 11.sp, color = uiMuted(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background(if (current) colors.primary else colors.surfaceVariant.copy(alpha = 0.55f))
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "暂停第${index + 1}个视频" else "播放第${index + 1}个视频",
                    Modifier.size(18.dp),
                    tint = if (current) colors.onPrimary else colors.primary,
                )
            }
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Close, "从列表移除：$title", Modifier.size(17.dp), tint = uiMuted())
            }
        }
    }
}
