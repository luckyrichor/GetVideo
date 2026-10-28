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
 * 拖动排序：长按任意一行开始拖（行首不放把手，2026-10-28 按用户要求去掉两道横线）。
 *
 * 两条硬约定，2026-10-28 第二次返工后固定下来：
 *  1. 被拖行的中心只跟手指走，**不叠加自动滚动量**。上一版把 `scrollBy` 的消费量加到中心上，
 *     手指没动中心却一直漂，漂出视口后只能退化成「取最近边缘行」，
 *     于是第一条往下拖会一路窜到最后一条，往上拖则在首尾之间来回跳。
 *  2. 每次只和紧邻的上一行/下一行比较，越线一次也**只挪一格**。
 *     跨多行换位会让布局在下一帧整体重排、中心落到别的行上，形成来回抖。
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
    val edge = with(density) { 56.dp.toPx() }
    val maxSpeed = with(density) { 12.dp.toPx() }
    var draft by remember { mutableStateOf(queue) }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    // 被拖行中心的屏幕坐标（相对列表视口），只随手指和初始抓取位置变化。
    var center by remember { mutableFloatStateOf(0f) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val latestQueue by rememberUpdatedState(queue)
    val latestReorder by rememberUpdatedState(onReorder)

    // 拖到一半队列被外部改动（切歌、删除）才放弃这次拖动，否则保留本地草稿，
    // 不然拖动中任何一次无关的队列刷新都会把手指下的行弹回原位。
    LaunchedEffect(queue) { if (draggedId == null) draft = queue }

    /** 判断是否需要和相邻行换位；每次最多挪一格。 */
    fun step() {
        val id = draggedId ?: return
        val layout = state.layoutInfo
        val top = layout.viewportStartOffset.toFloat()
        val bottom = layout.viewportEndOffset.toFloat()
        if (bottom <= top) return
        // 手指拖到面板外时，落点夹在可视区内，避免越界后判定失真。
        val y = center.coerceIn(top + 1f, bottom - 1f)
        val from = draft.indexOfFirst { it.track.id == id }
        if (from < 0) return
        val infos = layout.visibleItemsInfo
        val to = reorderStep(draft.map { it.track.id }, from, y) { key ->
            infos.firstOrNull { it.key == key }?.let { it.offset.toFloat() to it.size.toFloat() }
        } ?: return
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
        error = null
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    fun dragBy(dy: Float) {
        if (draggedId == null) return
        center += dy
        step()
    }
    fun endDrag() {
        val moved = draggedId != null && draft.map { it.track.id } != latestQueue.map { it.track.id }
        draggedId = null
        if (moved) saveOrder(draft)
    }
    fun cancelDrag() { draggedId = null; draft = latestQueue }

    LaunchedEffect(draggedId) {
        if (draggedId == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            if (draggedId == null) break
            val layout = state.layoutInfo
            val top = layout.viewportStartOffset.toFloat()
            val bottom = layout.viewportEndOffset.toFloat()
            val speed = when {
                center < top + edge -> -(((top + edge - center) / edge).coerceIn(0f, 1f)) * maxSpeed
                center > bottom - edge -> (((center - bottom + edge) / edge).coerceIn(0f, 1f)) * maxSpeed
                else -> 0f
            }
            // 只让列表滚动，不动被拖行的中心 —— 中心始终贴着手指，重排节奏才可控。
            if (speed != 0f) state.scrollBy(speed)
            // 每帧都重判一次：贴边滚动时即使没有新的拖动事件，也要继续一格一格换位。
            step()
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
                    // 长按落在两行之间的 8dp 缝里也要能拖起来，命中不到就取最近的一行。
                    val infos = state.layoutInfo.visibleItemsInfo
                    val hit = infos.firstOrNull { offset.y >= it.offset && offset.y < it.offset + it.size }
                        ?: infos.minByOrNull { kotlin.math.abs(it.offset + it.size / 2f - offset.y) }
                    hit?.let { item -> beginDrag(item.key as Long) }
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
                    if (playing) "暂停第${index + 1}个音频" else "播放第${index + 1}个音频",
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

/**
 * 拖动时的换位判定（纯函数，单测覆盖）。
 *
 * 只和**紧邻**的上一行/下一行比，越过对方中线才换，且一次只挪一格：
 *  - 跨多行换位会让布局在下一帧整体重排，中心落到别的行上，来回抖；
 *  - 不跟「最近的一行」比，就不会在中心漂出视口时被甩到列表首尾。
 *
 * @param order 当前草稿顺序（各行 track id）
 * @param from 被拖行在 [order] 里的下标
 * @param centerY 被拖行中心的屏幕坐标（已夹在可视区内）
 * @param bounds 某一行可见时的 (顶边, 高度)；不可见返回 null
 * @return 换位后的新下标；不需要换位返回 null
 */
internal fun reorderStep(
    order: List<Long>,
    from: Int,
    centerY: Float,
    bounds: (Long) -> Pair<Float, Float>?,
): Int? {
    if (from !in order.indices) return null
    if (from < order.lastIndex) {
        bounds(order[from + 1])?.let { (top, height) ->
            if (centerY > top + height / 2f) return from + 1
        }
    }
    if (from > 0) {
        bounds(order[from - 1])?.let { (top, height) ->
            if (centerY < top + height / 2f) return from - 1
        }
    }
    return null
}
