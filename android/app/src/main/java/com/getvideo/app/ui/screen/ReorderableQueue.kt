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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt

/**
 * 拖动排序：长按任意一行开始拖（行首不放把手，2026-10-28 按用户要求去掉两道横线）。
 *
 * 第三次返工（0.9.7）改了根本做法，前两版的思路是错的：
 *
 * 前两版都让「被拖行」留在 LazyColumn 里，靠 `translationY` 补偿它自己槽位的位移。
 * 换位那一帧，`draft` 已经变了但布局还没跑，补偿量是按旧槽位算的，于是行会先跳一下、
 * 下一帧再跳回来 —— 这就是「疯狂抖动」；同时补偿量算错时中心会漂出视口，
 * 只能退化成「取最近边缘行」，于是第一条往下拖会一路窜到第 26~30 条。
 *
 * 现在：
 *  1. **被拖行移出列表**，画在一个浮层里，位置只由 [dragCenter] 决定，不跟布局纠缠；
 *     列表里留一个同样高的占位块，其它行不会跳。
 *  2. 落位目标 = **手指当前所在的那一行**（只从可视区里选），不再比中线、不再一帧一格。
 *     目标永远被可视区夹住，所以无论怎么拖都不可能一帧冲到列表另一头。
 *  3. 贴边自动滚动只滚列表，不动手指位置；滚出来的行会自然落到手指下面。
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
    val edge = with(density) { 52.dp.toPx() }
    val maxSpeed = with(density) { 9.dp.toPx() }
    val inset = with(density) { 2.dp.toPx() }
    var draft by remember { mutableStateOf(queue) }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    // 被拖行中心的屏幕坐标（相对列表视口），只随手指变化。
    var dragCenter by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableFloatStateOf(0f) }
    // 自上次换位以来手指走了多少（正 = 往下）。换位后清零，判定就不会被列表锚点平移带跑。
    var residual by remember { mutableFloatStateOf(0f) }
    var boxHeight by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val latestQueue by rememberUpdatedState(queue)
    val latestReorder by rememberUpdatedState(onReorder)

    // 拖到一半队列被外部改动（切歌、删除）才放弃这次拖动，否则保留本地草稿，
    // 不然拖动中任何一次无关的队列刷新都会把手指下的行弹回原位。
    LaunchedEffect(queue) { if (draggedId == null) draft = queue }

    fun move(from: Int, to: Int): Boolean {
        if (from !in draft.indices || to !in draft.indices || from == to) return false
        draft = draft.toMutableList().apply { add(to, removeAt(from)) }
        return true
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
        dragCenter = info.offset + info.size / 2f
        dragHeight = info.size.toFloat()
        residual = 0f
        error = null
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    fun endDrag() {
        val moved = draggedId != null && draft.map { it.track.id } != latestQueue.map { it.track.id }
        draggedId = null
        if (moved) saveOrder(draft)
    }
    fun cancelDrag() { draggedId = null; residual = 0f; draft = latestQueue }

    // 拖动期间每一帧：先按贴边程度滚列表，再按「手指落在哪一行」落位。
    LaunchedEffect(draggedId) {
        val id = draggedId ?: return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            if (draggedId != id) break
            val layout = state.layoutInfo
            val top = layout.viewportStartOffset.toFloat()
            val bottom = layout.viewportEndOffset.toFloat()
            if (bottom <= top) continue
            val speed = when {
                dragCenter < top + edge ->
                    -(((top + edge - dragCenter) / edge).coerceIn(0f, 1f)) * maxSpeed
                dragCenter > bottom - edge ->
                    (((dragCenter - bottom + edge) / edge).coerceIn(0f, 1f)) * maxSpeed
                else -> 0f
            }
            if (speed != 0f) {
                state.scrollBy(speed)
                // 内容往上滚 = 手指相对内容往下走，等价于手指位移，记账给判定用。
                residual += speed
            }
            val from = draft.indexOfFirst { it.track.id == id }
            if (from < 0) break
            val slots = layout.visibleItemsInfo.map {
                QueueSlot(it.index, it.offset.toFloat(), it.size.toFloat())
            }
            // 判定基准 = 被拖行**当前槽位**的中心 + 自上次换位以来的手指位移。
            // 不能直接用 dragCenter：换位时 LazyColumn 会把「第一个可见项」钉在原像素上，
            // 槽位坐标整体平移，dragCenter 会指到别的行上，于是每帧再换一格，一路窜到列表尾部。
            val slot = slots.firstOrNull { it.index == from } ?: continue
            val y = slot.top + slot.height / 2f + residual
            val target = dropTarget(from, y, slots, inset) ?: continue
            // 换位后保留「手指在这一行里的偏移」，下一次换位才不用重新走满一行；
            // 又不能保留绝对值 —— 锚点平移后槽位坐标已经变了。
            val targetSlot = slots.firstOrNull { it.index == target }
            if (move(from, target)) {
                residual = if (targetSlot != null) y - (targetSlot.top + targetSlot.height / 2f) else 0f
            }
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
        Box(Modifier.onSizeChanged { boxHeight = it.height }) {
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
                            change.consume()
                            // 只挪手指位置，换位交给每帧的循环做，一次事件里绝不连跳好几格。
                            dragCenter += amount.y
                            residual += amount.y
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
                    if (active) {
                        // 占位：保持原来的高度，让上下行原地不动，等手指把它挪到别处。
                        Spacer(Modifier.fillMaxWidth().height(with(density) { dragHeight.toDp() }))
                    } else {
                        QueueRow(
                            index = i,
                            title = item.track.title,
                            uploader = item.track.uploader,
                            durationSec = item.track.durationSec,
                            current = current,
                            playing = current && playing,
                            lifted = false,
                            enabled = !saving && draggedId == null,
                            onPlay = { onPlay(id) },
                            onRemove = { onRemove(id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    customActions = listOfNotNull(
                                        if (i > 0) CustomAccessibilityAction("上移") {
                                            if (!saving && draggedId == null) {
                                                saveOrder(draft.toMutableList().apply { add(i - 1, removeAt(i)) })
                                            }
                                            true
                                        } else null,
                                        if (i < draft.lastIndex) CustomAccessibilityAction("下移") {
                                            if (!saving && draggedId == null) {
                                                saveOrder(draft.toMutableList().apply { add(i + 1, removeAt(i)) })
                                            }
                                            true
                                        } else null,
                                    )
                                },
                        )
                    }
                }
            }
            // 浮起来的被拖行：位置只跟手指走，跟列表布局无关，所以不会抖。
            val dragged = draft.firstOrNull { it.track.id == draggedId }
            if (dragged != null && dragHeight > 0f) {
                val limit = (boxHeight - dragHeight).coerceAtLeast(0f)
                val topPx = (dragCenter - dragHeight / 2f).coerceIn(0f, limit)
                QueueRow(
                    index = draft.indexOfFirst { it.track.id == dragged.track.id },
                    title = dragged.track.title,
                    uploader = dragged.track.uploader,
                    durationSec = dragged.track.durationSec,
                    current = dragged.track.id == currentId,
                    playing = false,
                    lifted = true,
                    enabled = false,
                    onPlay = { },
                    onRemove = { },
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(2f)
                        .offset { IntOffset(0, topPx.roundToInt()) }
                        .graphicsLayer {
                            scaleX = 1.02f
                            scaleY = 1.02f
                            shadowElevation = 18f
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
        tonalElevation = if (lifted) 6.dp else 0.dp,
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

/** 列表里一行在视口中的位置（只收可视行）。 */
internal data class QueueSlot(val index: Int, val top: Float, val height: Float)

/**
 * 拖动落位判定（纯函数，单测覆盖）：**手指在哪一行，就落到哪一行**。
 *
 * - 只从 [slots]（可视区）里选，目标天然被夹在可视区内 —— 这是「一帧冲到底」的根治办法；
 * - 落在自己这行就不动，所以不会来回跳；
 * - 落在行距缝里按下一行算，方向一致，也不会左右摇摆。
 *
 * @param from 被拖行当前下标
 * @param y 手指（行中心）在视口里的坐标
 * @param inset 顶部/底部的安全边距，避免正好压在边上时抖动
 * @return 目标下标；不需要换位返回 null
 */
internal fun dropTarget(
    from: Int,
    y: Float,
    slots: List<QueueSlot>,
    inset: Float = 0f,
): Int? {
    if (slots.isEmpty() || from < 0) return null
    val first = slots.first()
    val last = slots.last()
    val lo = first.top + inset
    val hi = last.top + last.height - inset
    if (hi <= lo) return null
    val clamped = y.coerceIn(lo, hi)
    val hit = slots.firstOrNull { clamped < it.top + it.height } ?: last
    return if (hit.index == from) null else hit.index
}
