package com.getvideo.app.ui.screen

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.getvideo.app.audioedit.*
import com.getvideo.app.data.local.TrackEntity
import com.getvideo.app.data.repo.TrackRepository
import com.getvideo.app.player.PlayerHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioEditScreen(track: TrackEntity, onClose: () -> Unit, onSaved: (TrackEntity) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { TrackRepository(context) }
    val player = remember { ExoPlayer.Builder(context).build().apply { setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true) } }
    var file by remember { mutableStateOf<File?>(null) }
    var duration by remember { mutableLongStateOf(0) }
    var peaks by remember { mutableStateOf(FloatArray(0)) }
    var segments by remember { mutableStateOf(listOf<AudioSegment>()) }
    var active by remember { mutableIntStateOf(0) }
    var position by remember { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var previewSegments by remember { mutableStateOf(listOf<AudioSegment>()) }
    var loading by remember { mutableStateOf(true) }
    var waveformLoading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf(track.title + "（剪辑）") }
    var overwrite by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableFloatStateOf(0f) }
    var startText by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }
    val selected = segments.getOrNull(active)
    val valid = remember(segments, duration) { runCatching { validatedSegments(segments, duration) }.getOrNull() }
    LaunchedEffect(selected) { startText = selected?.let { editTime(it.startMs) } ?: ""; endText = selected?.let { editTime(it.endMs) } ?: "" }
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(file) { val snapshot = file; onDispose { snapshot?.delete() } }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(track.id) {
        try {
            PlayerHolder.get(context).pause()
            val source = repo.editSource(track.id)
            file = source
            duration = withContext(Dispatchers.IO) { AudioEditor.duration(source) }
            require(duration > 0) { "视频时长无效" }
            segments = listOf(AudioSegment(0, duration))
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(source))); player.prepare()
        } catch (e: Exception) { error = e.message ?: "无法打开视频" }
        finally { loading = false }
    }
    LaunchedEffect(file, duration) {
        val source = file ?: return@LaunchedEffect
        if (duration <= 0) return@LaunchedEffect
        try { peaks = AudioEditor.waveform(source, duration) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = "波形读取失败：${e.message}；仍可播放和选择时间" }
        finally { waveformLoading = false }
    }
    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            position = if (preview) previewSegments.getOrNull(player.currentMediaItemIndex)?.let { it.startMs + player.currentPosition } ?: 0 else player.currentPosition
            delay(100)
        }
    }
    fun originalAt(ms: Long, play: Boolean = false) {
        val source = file ?: return
        if (preview) { player.setMediaItem(MediaItem.fromUri(Uri.fromFile(source))); player.prepare(); preview = false }
        player.seekTo(ms.coerceIn(0, duration)); position = ms
        if (play) player.play()
    }
    fun update(segment: AudioSegment) {
        if (preview) { player.pause(); originalAt(position) }
        segments = segments.mapIndexed { index, value -> if (index == active) segment else value }
    }
    fun save() {
        val source = file ?: return
        val clips = valid ?: return
        val name = title.trim()
        if (name.isBlank()) { error = "名称不能为空"; return }
        busy = true; player.pause(); error = null
        scope.launch {
            val output = File(context.cacheDir, "edit-${java.util.UUID.randomUUID()}.mp4")
            try {
                AudioEditor.export(context, source, clips, duration, output)
                val saved = repo.saveEdit(track.id, output, name, overwrite)
                onSaved(saved)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = "保存失败：${e.message}" }
            finally { output.delete(); busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("剪辑视频", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose, enabled = !busy) { Text("关闭") }
                }
                Text(track.title, style = MaterialTheme.typography.titleSmall)
                if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在准备视频…") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (file != null && duration > 0) {
                    VideoSurface(player, Modifier.fillMaxWidth().height(220.dp), controls = false)
                    Text("选中的是保留部分 · 点击波形定位，拖动两端调整范围", style = MaterialTheme.typography.bodySmall)
                    val window = (duration / zoom).toLong().coerceAtLeast(1)
                    val viewStart = ((duration - window) * offset).toLong()
                    WaveformEditor(peaks, duration, viewStart, window, segments, active, position, !busy,
                        onSeek = { originalAt(it, playing) }, onRange = { update(it) })
                    if (waveformLoading) Text("正在生成真实波形…", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { zoom = (zoom / 2).coerceAtLeast(1f) }, enabled = !busy) { Text("缩小") }
                        TextButton(onClick = { zoom = (zoom * 2).coerceAtMost(64f) }, enabled = !busy) { Text("放大") }
                        Text("${editTime(viewStart)} — ${editTime((viewStart + window).coerceAtMost(duration))}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (zoom > 1) { Text("移动波形视窗", style = MaterialTheme.typography.labelSmall); Slider(offset, { offset = it }, enabled = !busy) }
                    Text("${editTime(position)} / ${editTime(duration)}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (playing) player.pause() else originalAt(position, true) }, enabled = !busy) { Text(if (playing) "暂停" else "播放原视频") }
                        OutlinedButton(onClick = {
                            val clips = valid ?: return@OutlinedButton
                            previewSegments = clips; preview = true
                            player.setMediaItems(clips.map { AudioEditor.clippedItem(file!!, it) }); player.prepare(); player.play()
                        }, enabled = valid != null && !busy) { Text("预览剪辑结果") }
                    }
                    Text(if (preview) "正在预览保留片段，按原时间顺序连续播放" else "边看边选择起点和终点", style = MaterialTheme.typography.bodySmall)
                    segments.forEachIndexed { index, segment ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { active = index }, enabled = !busy) { Text("${if (active == index) "● " else ""}片段 ${index + 1}  ${editTime(segment.startMs)} — ${editTime(segment.endMs)}") }
                            TextButton(onClick = { player.pause(); originalAt(position); segments = segments.filterIndexed { i, _ -> i != index }; active = active.coerceAtMost((segments.size - 1).coerceAtLeast(0)) }, enabled = !busy) { Text("移除") }
                        }
                    }
                    if (selected != null) {
                        RangeSlider(value = selected.startMs.toFloat()..selected.endMs.toFloat(), onValueChange = { update(AudioSegment(it.start.toLong(), it.endInclusive.toLong())) }, valueRange = 0f..duration.toFloat(), enabled = !busy)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(startText, { startText = it }, label = { Text("开始 分:秒") }, modifier = Modifier.weight(1f), enabled = !busy, singleLine = true)
                            OutlinedTextField(endText, { endText = it }, label = { Text("结束 分:秒") }, modifier = Modifier.weight(1f), enabled = !busy, singleLine = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                val start = parseEditTime(startText); val end = parseEditTime(endText)
                                if (start == null || end == null || start >= end || end > duration) error = "请填写有效的开始和结束时间"
                                else { update(AudioSegment(start, end)); error = null }
                            }, enabled = !busy) { Text("应用时间") }
                            TextButton(onClick = { update(selected.copy(startMs = position.coerceIn(0, selected.endMs - 1))) }, enabled = !busy && selected.endMs > 0) { Text("当前位置为起点") }
                            TextButton(onClick = { update(selected.copy(endMs = position.coerceIn(selected.startMs + 1, duration))) }, enabled = !busy && selected.startMs < duration) { Text("当前位置为终点") }
                        }
                    }
                    OutlinedButton(onClick = {
                        val sorted = segments.sortedBy { it.startMs }
                        val boundaries = listOf(AudioSegment(0, 0)) + sorted + AudioSegment(duration, duration)
                        val gap = boundaries.zipWithNext().firstOrNull { (a, b) -> b.startMs - a.endMs >= 1000 }
                        if (gap == null) error = "没有可添加的空白区间，请先缩短或移除已有片段"
                        else { player.pause(); originalAt(position); segments = segments + AudioSegment(gap.first.endMs, minOf(gap.second.startMs, gap.first.endMs + 30000)); active = segments.lastIndex; error = null }
                    }, enabled = !busy) { Text("添加片段") }
                    Text(if (valid == null) "请检查片段：至少一段，时间有效且不能重叠" else "最终时长 ${editTime(valid.sumOf { it.endMs - it.startMs })} · 多段按原顺序拼接", color = if (valid == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    HorizontalDivider()
                    Row { RadioButton(!overwrite, { overwrite = false; title = track.title + "（剪辑）" }, enabled = !busy); Text("另存为新视频") }
                    Row { RadioButton(overwrite, { overwrite = true; title = track.title }, enabled = !busy); Text("覆盖本地视频") }
                    OutlinedTextField(title, { title = it }, label = { Text("视频名称（允许同名）") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                    Text("剪辑在手机本地完成，结果保存为 M4A；服务器原文件保持原样。", style = MaterialTheme.typography.bodySmall)
                    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在生成并保存剪辑结果…") }
                    Button(onClick = { if (overwrite) confirmOverwrite = true else save() }, enabled = !busy && valid != null && title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("保存剪辑") }
                }
            }
        }
    }
    if (confirmOverwrite) AlertDialog(onDismissRequest = { confirmOverwrite = false }, title = { Text("覆盖本地视频？") },
        text = { Text("原时长 ${editTime(duration)}，剪辑后 ${editTime(valid?.sumOf { it.endMs - it.startMs } ?: 0)}。收藏、类别和播放列表保留。手机上的文件将替换，服务器原版不受影响。") },
        confirmButton = { TextButton(onClick = { confirmOverwrite = false; save() }) { Text("确认覆盖") } },
        dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("取消") } })
}

@Composable
private fun WaveformEditor(peaks: FloatArray, duration: Long, viewStart: Long, window: Long, segments: List<AudioSegment>, active: Int, position: Long, enabled: Boolean, onSeek: (Long) -> Unit, onRange: (AudioSegment) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val selected = segments.getOrNull(active)
    val currentSeek by rememberUpdatedState(onSeek)
    val currentRange by rememberUpdatedState(onRange)
    val currentSelected by rememberUpdatedState(selected)
    Canvas(Modifier.fillMaxWidth().height(160.dp)
        .pointerInput(viewStart, window, enabled) { if (enabled) detectTapGestures { currentSeek((viewStart + it.x / size.width * window).toLong().coerceIn(0, duration)) } }
        .pointerInput(viewStart, window, enabled, active) { if (enabled) {
            var dragStart = true
            detectDragGestures(onDragStart = { point ->
                currentSelected?.let { segment ->
                    val time = (viewStart + point.x / size.width * window).toLong()
                    dragStart = abs(time - segment.startMs) <= abs(time - segment.endMs)
                }
            }, onDrag = { change, _ ->
            val segment = currentSelected
            if (segment != null) {
                val time = (viewStart + change.position.x / size.width * window).toLong().coerceIn(0, duration)
                if (dragStart && segment.endMs > 0) currentRange(segment.copy(startMs = time.coerceAtMost(segment.endMs - 1)))
                else if (!dragStart && segment.startMs < duration) currentRange(segment.copy(endMs = time.coerceAtLeast(segment.startMs + 1)))
                change.consume()
            }
        }) } }) {
        fun x(ms: Long) = (ms - viewStart).toFloat() / window * size.width
        segments.forEachIndexed { index, segment ->
            val left = x(segment.startMs).coerceIn(0f, size.width); val right = x(segment.endMs).coerceIn(0f, size.width)
            if (right > left) drawRect(accent.copy(alpha = if (index == active) 0.22f else 0.09f), Offset(left, 0f), androidx.compose.ui.geometry.Size(right - left, size.height))
        }
        val columns = (size.width / 3.dp.toPx()).toInt().coerceAtLeast(1)
        for (column in 0 until columns) {
            val a = ((viewStart + column.toDouble() / columns * window) / duration * peaks.size).toInt().coerceIn(0, peaks.size)
            val b = ((viewStart + (column + 1.0) / columns * window) / duration * peaks.size).toInt().coerceIn(a, peaks.size)
            var peak = 0f
            for (i in a until maxOf(b, (a + 1).coerceAtMost(peaks.size))) peak = maxOf(peak, peaks[i])
            val height = maxOf(2f, peak * size.height * 0.85f)
            val at = column.toFloat() / columns * size.width
            drawLine(accent, Offset(at, (size.height - height) / 2), Offset(at, (size.height + height) / 2), 2.dp.toPx())
        }
        selected?.let { segment -> listOf(segment.startMs, segment.endMs).forEach { time ->
            val at = x(time)
            if (at in 0f..size.width) { drawLine(accent, Offset(at, 0f), Offset(at, size.height), 3.dp.toPx()); drawCircle(accent, 7.dp.toPx(), Offset(at, size.height / 2)) }
        } }
        val cursor = x(position)
        if (cursor in 0f..size.width) drawLine(Color.Red, Offset(cursor, 0f), Offset(cursor, size.height), 2.dp.toPx())
    }
}
