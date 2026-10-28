package com.getvideo.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.getvideo.app.player.PlayerHolder
import com.getvideo.app.ui.formatDuration
import com.getvideo.app.ui.components.uiCardColor
import com.getvideo.app.ui.components.uiHairline
import com.getvideo.app.ui.components.uiMuted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 拖动排序：长按任意一行开始拖（不再单独放把手，2026-10-28 按用户要求去掉行首两道横线）。
 *
 * 落点判定以「被拖行中心越过目标行中线」为准，而不是碰到目标行就换位 ——
 * 否则第一个条目往下拖时，换位后立刻又命中自己原来的位置，会来回抖。
 * 中心拖出视口时取最近的边缘行，保证贴边自动滚动的同时继续重排。
 */
@Composable
internal fun ReorderableQueue(
    queue: List<PlayerHolder.QueueItem>, currentId: Long?, playing: Boolean,
    onReorder: suspend (List<Long>) -> Boolean,
    onPlay: (Long) -> Unit, onRemove: (Long) -> Unit,
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { 48.dp.toPx() }
    var draft by remember { mutableStateOf(queue) }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var center by remember { mutableFloatStateOf(0f) }
    var finger by remember { mutableFloatStateOf(0f) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val latestQueue by rememberUpdatedState(queue)
    val latestReorder by rememberUpdatedState(onReorder)

    LaunchedEffect(queue) { draggedId = null; draft = queue }

    fun moveAtCenter() {
        val id = draggedId ?: return
        val others = state.layoutInfo.visibleItemsInfo.filter { it.key != id }
        if (others.isEmpty()) return
        val from = draft.indexOfFirst { it.track.id == id }
        if (from < 0) return
        // 中心在视口内就取命中的行；拖出视口后取离中心最近的边缘行，保证滚动时仍在重排。
        val target = others.firstOrNull { center >= it.offset && center < it.offset + it.size }
            ?: others.minByOrNull { kotlin.math.abs(it.offset + it.size / 2f - center) }
            ?: return
        val to = draft.indexOfFirst { it.track.id == target.key }
        if (to < 0 || to == from) return
        // 越过目标行中线才换位，避免刚换过去又被判回来。
        val mid = target.offset + target.size / 2f
        val crossed = if (to > from) center > mid else center < mid
        if (!crossed) return
        draft = draft.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun saveOrder(items: List<PlayerHolder.QueueItem>) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                if (!latestReorder(items.map { it.track.id })) error = "列表已更新，请重新排序"
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { error = "排序未保存，请重试"
            } finally { saving = false; draft = latestQueue }
        }
    }
    fun beginDrag(key: Long) {
        if (saving) return
        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        draggedId = key
        center = info.offset + info.size / 2f
        finger = info.offset + info.size / 2f
        error = null
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    fun dragBy(dy: Float) {
        if (draggedId == null) return
        center += dy
        finger += dy
        moveAtCenter()
    }
    fun endDrag() {
        val moved = draggedId != null && draft.map { it.track.id } != latestQueue.map { it.track.id }
        draggedId = null
        if (moved) saveOrder(draft)
    }
    fun cancelDrag() { draggedId = null; draft = latestQueue }

    LaunchedEffect(draggedId) {
        while (draggedId != null) {
            withFrameNanos { }
            val layout = state.layoutInfo
            val top = layout.viewportStartOffset.toFloat()
            val bottom = layout.viewportEndOffset.toFloat()
            val speed = when {
                finger < top + edge -> -(((top + edge - finger) / edge).coerceIn(0f, 1f)) * 18f
                finger > bottom - edge -> (((finger - bottom + edge) / edge).coerceIn(0f, 1f)) * 18f
                else -> 0f
            }
            if (speed != 0f) {
                val consumed = state.scrollBy(speed)
                // 列表滚动了多少，被拖行在内容坐标里也要跟着走多少，
                // 否则手指没动、条目却被列表甩开，落点判定会失真。
                if (consumed != 0f) center += consumed
            }
            // 每帧都重新判定一次：贴边滚动时即使没有新的拖动事件也要继续换位。
            moveAtCenter()
        }
    }
    Column {
        if (saving) {
            Text(
                "正在保存顺序…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        error?.let {
            Text(
                it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }
        LazyColumn(state = state, modifier = Modifier.heightIn(max = 430.dp).pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    state.layoutInfo.visibleItemsInfo.firstOrNull {
                        offset.y >= it.offset && offset.y < it.offset + it.size
                    }?.let { item -> beginDrag(item.key as Long) }
                },
                onDrag = { change, amount ->
                    if (draggedId != null) {
                        change.consume(); dragBy(amount.y)
                    }
                },
                onDragEnd = { endDrag() },
                onDragCancel = { cancelDrag() },
            )
        }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(draft, key = { _, item -> item.track.id }) { i, item ->
                val id = item.track.id
                val active = draggedId == id
                val current = id == currentId
                val location = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
                QueueRow(
                    index = i,
                    title = item.track.title,
                    uploader = item.track.uploader,
                    durationSec = item.track.durationSec,
                    current = current,
                    playing = current && playing,
                    lifted = active,
                    enabled = !saving && draggedId == null,
                    onPlay = { onPlay(id) },
                    onRemove = { onRemove(id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (active) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (active && location != null) center - location.offset - location.size / 2f else 0f
                            scaleX = if (active) 1.02f else 1f
                            scaleY = if (active) 1.02f else 1f
                            shadowElevation = if (active) 16f else 0f
                        }
                        .semantics {
                            customActions = listOfNotNull(
                                if (i > 0) CustomAccessibilityAction("上移") {
                                    if (!saving && draggedId == null) saveOrder(draft.toMutableList().apply { add(i - 1, removeAt(i)) })
                                    true
                                } else null,
                                if (i < draft.lastIndex) CustomAccessibilityAction("下移") {
                                    if (!saving && draggedId == null) saveOrder(draft.toMutableList().apply { add(i + 1, removeAt(i)) })
                                    true
                                } else null,
                            )
                        },
                )
            }
        }
    }
}

/** 队列行：序号 + 标题/副标题 + 圆形播放入口 + 移除。拖动靠长按整行。 */
@Composable
private fun QueueRow(
    index: Int,
    title: String,
    uploader: String?,
    durationSec: Double?,
    current: Boolean,
    playing: Boolean,
    lifted: Boolean,
    enabled: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier,
        shape = shape,
        color = if (current) colors.primary.copy(alpha = 0.09f) else uiCardColor(),
        border = BorderStroke(1.dp, if (current) colors.primary.copy(alpha = 0.45f) else uiHairline()),
        tonalElevation = 0.dp,
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
                    "${index + 1}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (current) colors.onPrimary else colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                    color = if (current) colors.primary else colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
                    .clickable(enabled = enabled, onClick = onPlay),
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
                Modifier.size(32.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close, "从列表移除：$title", Modifier.size(17.dp),
                    tint = colors.onSurfaceVariant.copy(alpha = if (enabled) 0.7f else 0.3f),
                )
            }
        }
    }
}
