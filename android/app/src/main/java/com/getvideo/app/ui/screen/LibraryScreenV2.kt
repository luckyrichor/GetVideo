package com.getvideo.app.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import androidx.activity.compose.BackHandler
import com.getvideo.app.data.repo.DiagnosticsStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.getvideo.app.ui.formatDuration
import com.getvideo.app.ui.platformLabel
import com.getvideo.app.ui.components.uiAccentContainer
import com.getvideo.app.ui.components.uiCardColor
import com.getvideo.app.ui.components.uiHairline
import com.getvideo.app.ui.components.uiMuted
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import com.getvideo.app.data.local.AppDatabase
import com.getvideo.app.data.local.CategoryEntity
import com.getvideo.app.data.local.TrackEntity
import com.getvideo.app.data.repo.CategoryRepository
import com.getvideo.app.data.repo.PlaylistRepository
import com.getvideo.app.data.repo.TrackRepository
import com.getvideo.app.data.repo.Settings
import com.getvideo.app.data.repo.ApiProvider
import com.getvideo.app.player.PlayerHolder
import com.getvideo.app.data.repo.CoverCache
import kotlinx.coroutines.launch
import java.io.File

private sealed interface LibraryTarget {
    data class Audio(val track: TrackEntity) : LibraryTarget
    data class Category(val category: CategoryEntity) : LibraryTarget
}

/**
 * 排序字段：点排序按钮右侧的箭头从下拉里选一个，方向则靠点按钮主体切换。
 *
 * [MANUAL] 是拖动排出来的原始顺序（sortIndex），只有它允许长按拖动；
 * 其余几种由字段决定，拖动没有意义，所以拖动会自动关掉。
 * [defaultAsc] 是第一次选到这个字段时的默认方向：「时间/时长/文件」倒序（新的、长的、大的在前），
 * 「名称」正序（A→Z）。
 */
private enum class SortField(val label: String, val defaultAsc: Boolean) {
    FILE("文件", false),
    MANUAL("手动", false),
    TIME("时间", false),
    DURATION("时长", false),
    NAME("名称", true),
    ;

    /** 排序结果的一句人话说明，用于切换时弹提示条。 */
    fun describe(ascending: Boolean): String = when (this) {
        MANUAL -> "手动顺序，长按条目可拖动调整"
        FILE -> "按文件大小：" + if (ascending) "小→大" else "大→小"
        TIME -> "按加入时间：" + if (ascending) "旧→新" else "新→旧"
        DURATION -> "按时长：" + if (ascending) "短→长" else "长→短"
        NAME -> "按名称：" + if (ascending) "A→Z" else "Z→A"
    }

    companion object {
        /**
         * 按 [field] + 方向排序；手动方式原样返回，交给拖动顺序。
         *
         * 「时间」把没记录时间（addedAtMs<=0）的条目固定排在末尾，不随方向翻到最前面。
         */
        fun apply(field: SortField, ascending: Boolean, tracks: List<TrackEntity>): List<TrackEntity> {
            if (field == MANUAL) return tracks
            val cmp: Comparator<TrackEntity> = when (field) {
                TIME -> compareBy { it.addedAtMs }
                DURATION -> compareBy { it.durationSec ?: 0.0 }
                NAME -> compareBy { it.title.lowercase() }
                FILE, MANUAL -> compareBy { it.audioBytes ?: 0L }
            }
            val sorted = if (ascending) tracks.sortedWith(cmp) else tracks.sortedWith(cmp.reversed())
            // sortedBy 是稳定排序，等价于把「时间未记录」的条目挪到末尾。
            return if (field == TIME) sorted.sortedBy { it.addedAtMs <= 0 } else sorted
        }
    }
}

/**
 * 排序按钮：「字段 + 方向箭头 | 下拉箭头」两段式，外面套一个和旁边「导入」等高的圆角框。
 *
 * - 点左边主体：当前字段正序/倒序互换（手动模式没有方向，只弹提示）。
 * - 点右侧箭头：展开字段下拉（文件/手动/时间/时长/名称）。
 */
@Composable
private fun SortChip(
    field: SortField,
    ascending: Boolean,
    onToggleDirection: () -> Unit,
    onPickField: (SortField) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // 和旁边的「云端同步」「导入」保持同一套外观：透明底 + outline 描边 + 12dp 圆角 + 32dp 高。
    val shape = RoundedCornerShape(12.dp)
    Box {
        Row(
            Modifier.height(32.dp).clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左半段：点一下翻转正/倒序。
            Row(
                Modifier.fillMaxHeight().clickable(onClick = onToggleDirection)
                    .padding(start = 9.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort, null, Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(4.dp))
                Text(field.label, style = MaterialTheme.typography.labelLarge)
                if (field != SortField.MANUAL) {
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        if (ascending) "正序" else "倒序",
                        Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 右半段：点一下展开字段下拉。
            Box(
                Modifier.fillMaxHeight().width(24.dp).clickable { menu = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ArrowDropDown, "选择排序字段", Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            SortField.entries.forEach { f ->
                DropdownMenuItem(
                    text = { Text(if (f == field) "${f.label}（当前）" else f.label) },
                    onClick = { onPickField(f); menu = false },
                    trailingIcon = {
                        if (f == field) {
                            Icon(
                                Icons.Default.Check, null, Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreenV2(openCategories: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.get(context) }
    val trackRepo = remember { TrackRepository(context) }
    val categoryRepo = remember { CategoryRepository(context) }
    val playlistRepo = remember { PlaylistRepository(context) }
    val allCategories by categoryRepo.observeAll().collectAsState(initial = emptyList())
    val frequent by categoryRepo.frequent().collectAsState(initial = emptyList())
    val playlists by playlistRepo.observeAll().collectAsState(initial = emptyList())
    val sourcePlaylistId by PlayerHolder.sourcePlaylistId.collectAsState()
    val currentPlaylistId = sourcePlaylistId?.takeIf { id -> playlists.any { it.id == id } } ?: playlists.firstOrNull { it.isDefault }?.id
    val currentListName = playlists.firstOrNull { it.id == currentPlaylistId }?.name ?: "播放列表"
    var selecting by remember { mutableStateOf(false) }
    var batchIds by remember { mutableStateOf(emptySet<Long>()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    fun exitSelection() { selecting = false; batchIds = emptySet(); confirmBatchDelete = false }
    fun selectTrack(id: Long) {
        selecting = true
        batchIds = if (id in batchIds) batchIds - id else batchIds + id
    }
    BackHandler(enabled = selecting && !deleting) { exitSelection() }
    var section by remember { mutableIntStateOf(0) }
    LaunchedEffect(openCategories) { if (openCategories) section = 1 }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    var startMs by remember { mutableStateOf<Long?>(null) }
    var endTimeMs by remember { mutableStateOf<Long?>(null) }
    var dateDialog by remember { mutableStateOf(false) }
    var recreationTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var editTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var restoreTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var accountDialog by remember { mutableStateOf(false) }
    if (accountDialog) AccountLoginDialog { accountDialog = false }
    val serverUrl by remember(context) { Settings(context).serverUrl }.collectAsState(initial = "")
    var target by remember { mutableStateOf<LibraryTarget?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var selectedParent by remember { mutableStateOf<Long?>(null) }
    var exportTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var playlistId by remember { mutableStateOf<Long?>(null) }
    val selectedListName = playlists.firstOrNull { it.id == playlistId }?.name ?: "播放列表"
    var deletePlaylistId by remember { mutableStateOf<Long?>(null) }
    var playlistSearch by remember { mutableStateOf("") }
    var playlistPickMode by remember { mutableIntStateOf(0) }
    var selectedTracks by remember { mutableStateOf(setOf<Long>()) }
    var selectedCategories by remember { mutableStateOf(setOf<Long>()) }
    val endMs = endTimeMs?.plus(59_999L)
    val mode by PlayerHolder.mode.collectAsState()
    val timer = timerStatus()
    LaunchedEffect(Unit) { playlistRepo.ensureDefault() }
    val allTracks by db.trackDao().allWithTimeRange(query, startMs, endMs).collectAsState(initial = emptyList())
    val libraryTracks by db.trackDao().observeAll().collectAsState(initial = emptyList())
    val categoryTracks by db.trackDao().inCategory(categoryId ?: -1L).collectAsState(initial = emptyList())
    val visibleTracks = if (section == 0) allTracks else categoryTracks
    // 「排序」按钮循环切换的顺序：手动 → 时间 → 时长 → 名称 → 文件大小，各带升/降两向。
    var sortField by remember { mutableStateOf(SortField.MANUAL) }
    var sortAsc by remember { mutableStateOf(false) }
    val displayTracks = remember(allTracks, sortField, sortAsc) { SortField.apply(sortField, sortAsc, allTracks) }
    LaunchedEffect(visibleTracks, selecting) {
        if (selecting) batchIds = batchIds.intersect(visibleTracks.map { it.id }.toSet())
    }
    val snackbar = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri: Uri? ->
        val track = exportTrack
        if (uri != null && track != null) scope.launch {
            trackRepo.download(track).onSuccess { file ->
                runCatching { context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("无法打开文件") }
                    .onSuccess { notice = "已导出" }.onFailure { notice = it.message ?: "导出失败" }
            }.onFailure { notice = it.message ?: "下载失败" }
        }
        exportTrack = null
    }
    var importing by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            runCatching { DiagnosticsStore.operation("导入本地视频") { trackRepo.importVideo(uris).getOrThrow() } }
                .onSuccess { notice = if (it == 0) "没有新增视频（都已在库中）" else "已导入 $it 个视频" }
                .onFailure { notice = it.message ?: "导入失败" }
            importing = false
        }
    }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); notice = null } }

    fun operate(block: suspend () -> Unit) { scope.launch { runCatching { DiagnosticsStore.operation("视频库操作", block) }.onFailure { notice = it.message ?: "操作失败" } } }
    fun queueItems(tracks: List<TrackEntity>, play: Boolean, sourceId: Long? = null) = operate {
        val ready = mutableListOf<PlayerHolder.QueueItem>()
        for (track in tracks) {
            val path = track.localPath?.takeIf { File(it).exists() }
                ?: trackRepo.download(track).getOrThrow().absolutePath
            ready += PlayerHolder.QueueItem(track, path)
        }
        if (ready.isEmpty()) notice = "类别内没有视频"
        else if (play) {
            if (sourceId == null) PlayerHolder.playSelection(context, ready)
            else PlayerHolder.playAll(context, ready, sourceId)
        }
        else {
            val count = PlayerHolder.append(context, ready)
            notice = if (count == 0) "视频已在播放列表中" else "已加入 $count 个视频"
        }
    }
    fun targetTracks(item: LibraryTarget) = operate {
        val tracks = when (item) {
            is LibraryTarget.Audio -> listOf(item.track)
            is LibraryTarget.Category -> categoryRepo.recursiveTracks(item.category.id).also { categoryRepo.recordAccess(item.category.id) }
        }
        queueItems(tracks, true)
    }

    // 类别视图也是一个子层级：返回键先退回上一层类别，而不是直接退出应用。
    BackHandler(enabled = section == 1 && categoryId != null && !selecting) {
        val parent = allCategories.firstOrNull { it.id == categoryId }?.parentId
        exitSelection()
        categoryId = parent
    }
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .height(44.dp).clip(RoundedCornerShape(14.dp)).background(uiAccentContainer()).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf("全部视频", "类别").forEachIndexed { index, label ->
                    val selected = section == index
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (selected) uiCardColor() else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab, onClick = {
                            if (deleting) return@selectable
                            exitSelection()
                            section = index
                            if (index == 1) categoryId = null
                        }), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (selecting) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).regionFrame().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val visibleIds = visibleTracks.map { it.id }.toSet()
                    Checkbox(checked = visibleIds.isNotEmpty() && batchIds.containsAll(visibleIds), enabled = !deleting,
                        onCheckedChange = { batchIds = if (it) visibleIds else emptySet() })
                    Text("全选 · 已选 ${batchIds.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    AreaButton(onClick = { confirmBatchDelete = true }, enabled = batchIds.isNotEmpty() && !deleting, tone = ActionTone.DESTRUCTIVE) { Text("删除") }
                    TextButton(onClick = { exitSelection() }, enabled = !deleting) { Text("取消") }
                }
            }
            if (section == 0) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        query, { query = it },
                        placeholder = { Text("搜索视频名称", style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = uiMuted()) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = uiCardColor(),
                            unfocusedContainerColor = uiAccentContainer(),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        Modifier
                            .height(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (startMs != null || endTimeMs != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else uiAccentContainer())
                            .clickable { dateDialog = true }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.DateRange, null, Modifier.size(17.dp),
                            tint = if (startMs != null || endTimeMs != null) MaterialTheme.colorScheme.primary else uiMuted(),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "日期",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (startMs != null || endTimeMs != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (serverUrl.isNotBlank()) AssistChip(
                        onClick = { scope.launch {
                            if (com.getvideo.app.data.repo.AccountStore.forServer(serverUrl) == null) { accountDialog = true; return@launch }
                            syncing = true
                            runCatching { DiagnosticsStore.operation("云端同步") { trackRepo.sync() } }.onSuccess { notice = "已同步 $it 条" }
                                .onFailure { notice = ApiProvider.humanMessage(it) }
                            syncing = false
                        } },
                        enabled = !syncing,
                        label = { Text(if (syncing) "同步中…" else "云端同步", style = MaterialTheme.typography.labelLarge) },
                        leadingIcon = { Icon(Icons.Default.CloudSync, null, Modifier.size(16.dp)) },
                        shape = RoundedCornerShape(12.dp),
                    )
                    if (timer != null) {
                        Text(if (timer.contains(":")) "剩余 $timer" else timer,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        AreaButton(onClick = { PlayerHolder.cancelTimer(); notice = "已取消定时播放" }, tone = ActionTone.TIMER) {
                            Text("取消定时播放")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // 排序：主体点一下翻转正/倒序，右侧小箭头点开字段下拉（手动才能长按拖动）。
                    SortChip(
                        field = sortField,
                        ascending = sortAsc,
                        onToggleDirection = {
                            if (sortField == SortField.MANUAL) {
                                notice = "手动顺序：长按条目上下拖动即可调整"
                            } else {
                                sortAsc = !sortAsc
                                notice = "排序：" + sortField.describe(sortAsc)
                            }
                        },
                        onPickField = { f ->
                            sortField = f
                            sortAsc = f.defaultAsc
                            notice = "排序：" + f.describe(sortAsc)
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    // 导入本地视频：把系统文件选择器里选中的视频复制进库，靠最右侧。
                    AssistChip(
                        onClick = { importLauncher.launch(arrayOf("video/*")) },
                        enabled = !importing,
                        label = { Text(if (importing) "导入中…" else "导入", style = MaterialTheme.typography.labelLarge) },
                        leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) },
                        shape = RoundedCornerShape(12.dp),
                    )
                }
                if (startMs != null || endTimeMs != null) {
                    AreaButton(onClick = { startMs = null; endTimeMs = null }) { Text("${startMs?.let { displayDateTime(it) } ?: "不限"} ~ ${endTimeMs?.let { displayDateTime(it) } ?: "不限"} ×") }
                }
                if (allTracks.isEmpty()) EmptyLibrary()
                else LibraryTrackList(
                    tracks = displayTracks,
                    dragEnabled = sortField == SortField.MANUAL,
                    selecting = selecting,
                    batchIds = batchIds,
                    deleting = deleting,
                    onReorder = { ids -> trackRepo.reorderLibrary(allTracks.map { it.id }, ids) },
                    onPlay = { track -> queueItems(listOf(track), true) },
                    onMore = { track -> target = LibraryTarget.Audio(track) },
                    onSelect = { id -> selectTrack(id) },
                    onReorderFailed = { notice = it },
                )
            } else {
                val parent = allCategories.firstOrNull { it.id == categoryId }
                if (parent != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SheetPill("返回", Icons.Default.ChevronLeft, { exitSelection(); categoryId = parent.parentId })
                        Text(
                            categoryPath(parent, allCategories),
                            modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else if (frequent.any { it.accessCount > 0 }) {
                    Text(
                        "常用类别",
                        Modifier.padding(start = 18.dp, top = 12.dp, bottom = 6.dp),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium, color = uiMuted(),
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                        frequent.filter { it.accessCount > 0 }.forEach { cat ->
                            AssistChip(onClick = { exitSelection(); categoryId = cat.id; operate { categoryRepo.recordAccess(cat.id) } },
                                label = { Text(cat.name) }, shape = CircleShape, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
                val children = allCategories.filter { it.parentId == categoryId }
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (categoryId == null) {
                        SheetPill("新建类别", Icons.Default.Add, { dialog = "newCategory"; editText = ""; selectedParent = null }, primary = true)
                        Spacer(Modifier.weight(1f))
                        SheetPill("播放列表", Icons.AutoMirrored.Filled.QueueMusic, { dialog = "playlists" })
                    } else {
                        if (parent?.kind != "LEAF") {
                            SheetPill("新建子类别", Icons.Default.Add, { dialog = "newCategory"; editText = ""; selectedParent = categoryId }, primary = true)
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
                if (children.isEmpty() && categoryTracks.isEmpty()) EmptyCategory()
                LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(children, key = { "c${it.id}" }) { cat ->
                        val subCount = allCategories.count { it.parentId == cat.id }
                        val container = subCount > 0 || cat.kind == "CONTAINER"
                        val count = if (container) subCount else libraryTracks.count { it.localCategoryId == cat.id }
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
                            CategoryCard(
                                name = cat.name,
                                countText = if (container) "$count 个类别" else "$count 个视频",
                                icon = if (container) Icons.Default.Folder else Icons.Default.FolderOpen,
                                container = container,
                                onOpen = { exitSelection(); categoryId = cat.id; operate { categoryRepo.recordAccess(cat.id) } },
                                onPlay = { targetTracks(LibraryTarget.Category(cat)) },
                                onMore = { target = LibraryTarget.Category(cat) },
                            )
                        }
                    }
                    itemsIndexed(categoryTracks, key = { _, track -> "t${track.id}" }) { index, track ->
                        LibraryAudioRow(track, number = index + 1, onPlay = { queueItems(listOf(track), true) }, onMore = { target = LibraryTarget.Audio(track) },
                        selecting = selecting, selected = track.id in batchIds, selectionEnabled = !deleting,
                        onSelect = { selectTrack(track.id) }, longPressSelects = true)
                    }
                }
            }
        }
    }

    if (confirmBatchDelete) AlertDialog(
        onDismissRequest = { if (!deleting) confirmBatchDelete = false }, title = { Text("删除 ${batchIds.size} 个视频？") },
        text = { Text("将删除手机上的视频文件，并从所有播放列表及正在播放的队列中移除。") },
        confirmButton = { AreaButton(onClick = {
            val ids = batchIds.toSet()
            deleting = true
            scope.launch {
                try {
                    val count = DiagnosticsStore.operation("批量删除") { trackRepo.removeAll(ids) }
                    exitSelection()
                    notice = "已删除 $count 个视频"
                } catch (error: Exception) { notice = ApiProvider.humanMessage(error) }
                finally { deleting = false; confirmBatchDelete = false }
            }
        }, enabled = !deleting, tone = ActionTone.DESTRUCTIVE) { Text(if (deleting) "删除中…" else "删除") } },
        dismissButton = { TextButton(onClick = { confirmBatchDelete = false }, enabled = !deleting) { Text("取消") } },
    )

    recreationTrack?.let { RecreationDialog(it) { recreationTrack = null } }
    editTrack?.let { track -> AudioEditScreen(track, onClose = { editTrack = null }, onSaved = { editTrack = null; notice = "剪辑已保存" }) }
    restoreTrack?.let { track -> AlertDialog(onDismissRequest = { restoreTrack = null }, title = { Text("恢复服务器原版？") }, text = { Text("本地剪辑版将替换为服务器原版，收藏与播放列表保留。") }, confirmButton = { TextButton(onClick = { restoreTrack = null; operate { trackRepo.restoreOriginal(track.id); notice = "已恢复原版" } }) { Text("恢复") } }, dismissButton = { TextButton(onClick = { restoreTrack = null }) { Text("取消") } }) }

    if (dateDialog) DateTimeRangeDialog(startMs, endTimeMs, onClose = { dateDialog = false }, onApply = { from, until ->
        startMs = from; endTimeMs = until; dateDialog = false
    })

    if (dialog == null) target?.let { item ->
        val audio = item as? LibraryTarget.Audio
        val category = (item as? LibraryTarget.Category)?.category
        MusicSheet(
            title = audio?.track?.title ?: category?.name.orEmpty(),
            onDismiss = { target = null },
            subtitle = audio?.let {
                listOfNotNull(
                    it.track.uploader?.takeIf { u -> u.isNotBlank() },
                    it.track.durationSec?.takeIf { s -> s > 0 }?.let { s -> formatDuration(s) },
                    platformLabel(it.track.platform),
                ).joinToString("  ·  ")
            } ?: category?.let { if (it.kind == "CONTAINER") "包含子类别的分类" else "分类下的视频" },
            confirmText = "完成",
            onConfirm = { target = null },
        ) {
            SheetGroup("播放") {
                SheetRow("加入当前播放列表", Icons.AutoMirrored.Filled.PlaylistAdd, featured = true, status = currentListName, onClick = {
                    target = null
                    operate { val tracks = if (item is LibraryTarget.Audio) listOf(item.track) else categoryRepo.recursiveTracks((item as LibraryTarget.Category).category.id); queueItems(tracks, false) }
                })
                SheetDivider()
                SheetRow("加入其他播放列表", Icons.AutoMirrored.Filled.QueueMusic, onClick = { dialog = "addToPlaylist" })
                SheetDivider()
                SheetRow("播放列表", Icons.Default.LibraryMusic, onClick = { target = null; dialog = "playlists" })
                SheetDivider()
                SheetRow("播放方式", Icons.Default.Repeat, status = mode.label, onClick = { target = null; dialog = "mode" })
                SheetDivider()
                SheetRow("定时播放", Icons.Default.Timer, status = timer ?: "未设置", onClick = { target = null; dialog = "timer" })
                if (timer != null) {
                    SheetDivider()
                    SheetRow("取消定时播放", Icons.Default.TimerOff, onClick = { PlayerHolder.cancelTimer(); target = null; notice = "已取消定时播放" })
                }
            }
            if (item !is LibraryTarget.Category || item.category.id != 1L) {
                SheetGroup("整理") {
                    SheetRow("重命名", Icons.Default.Edit, onClick = { editText = if (item is LibraryTarget.Audio) item.track.title else (item as LibraryTarget.Category).category.name; dialog = "rename" })
                    SheetDivider()
                    SheetRow("移动到…", Icons.AutoMirrored.Filled.DriveFileMove, onClick = { selectedParent = null; dialog = "move" })
                    // 长按改成了拖动排序，多选就从这里进（进入时带上当前这条）。
                    if (item is LibraryTarget.Audio) {
                        SheetDivider()
                        SheetRow("多选", Icons.Default.Checklist, onClick = {
                            target = null
                            batchIds = batchIds + item.track.id
                            selecting = true
                        })
                    }
                }
            }
            if (item is LibraryTarget.Category && item.category.kind != "LEAF") {
                SheetGroup("分类") {
                    SheetRow("新建子类别", Icons.Default.CreateNewFolder, onClick = { selectedParent = item.category.id; editText = ""; target = null; dialog = "newCategory" })
                }
            }
            if (item is LibraryTarget.Audio) {
                SheetGroup("视频") {
                    SheetRow("AI 描述与生成", Icons.Default.AutoAwesome, onClick = { recreationTrack = item.track; target = null })
                    SheetDivider()
                    SheetRow("剪辑视频", Icons.Default.ContentCut, status = if (item.track.locallyEdited) "本地剪辑版" else null, onClick = { editTrack = item.track; target = null })
                    if (item.track.locallyEdited && item.track.id > 0) {
                        SheetDivider()
                        SheetRow("恢复服务器原版", Icons.Default.Restore, onClick = { restoreTrack = item.track; target = null })
                    }
                    SheetDivider()
                    SheetRow("导出", Icons.Default.IosShare, onClick = { exportTrack = item.track; target = null; exportLauncher.launch("${item.track.title.take(60)}.${item.track.audioFormat}") })
                }
            }
            if (item !is LibraryTarget.Category || item.category.id != 1L) {
                SheetGroup {
                    SheetRow("删除", Icons.Default.DeleteOutline, destructive = true, chevron = false, onClick = { dialog = "delete" })
                }
            }
        }
    }
    when (dialog) {
        "newCategory", "rename", "newPlaylist", "renamePlaylist" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(when (dialog) { "newCategory" -> "新建类别"; "newPlaylist" -> "新建播放列表"; else -> "重命名" }) },
            text = { Column {
                NameEditor(editText, { editText = it })
                if (dialog == "newPlaylist") Text("不填写名称时，自动命名为${com.getvideo.app.data.repo.playlistName("", playlists.map { it.name })}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            } },
            confirmButton = { AreaButton(onClick = {
                val action = dialog; val item = target; val name = editText.trim(); dialog = null; target = null
                operate { when (action) {
                    "newCategory" -> categoryRepo.create(name, selectedParent)
                    "rename" -> when (item) { is LibraryTarget.Audio -> trackRepo.rename(item.track.id, name); is LibraryTarget.Category -> categoryRepo.rename(item.category.id, name); else -> Unit }
                    "newPlaylist" -> { playlistId = playlistRepo.create(name); dialog = "playlistEdit" }
                    "renamePlaylist" -> playlistId?.let { playlistRepo.rename(it, name) }
                } }
            }, tone = ActionTone.CONFIRM) { Text("保存") } }, dismissButton = { AreaButton(onClick = { dialog = null; target = null }) { Text("取消") } })
        "move" -> AlertDialog(onDismissRequest = { dialog = null; target = null }, title = { Text("移动到类别") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (target is LibraryTarget.Category) AreaAction(onClick = { selectedParent = null }) { Text("根目录 ${if (selectedParent == null) "✓" else ""}") }
                allCategories.filter { cat -> cat.id != (target as? LibraryTarget.Category)?.category?.id && (target is LibraryTarget.Category && cat.kind != "LEAF" || target is LibraryTarget.Audio && cat.kind != "CONTAINER") }.forEach { cat ->
                    AreaAction(onClick = { selectedParent = cat.id }) { Text("${categoryPath(cat, allCategories)} ${if (selectedParent == cat.id) "✓" else ""}") }
                }
            } }, confirmButton = { AreaButton(onClick = { val item = target; val dest = selectedParent; dialog = null; target = null; operate { when (item) {
                is LibraryTarget.Audio -> categoryRepo.moveTrack(item.track.id, dest ?: error("请选择目标类别"))
                is LibraryTarget.Category -> categoryRepo.moveCategory(item.category.id, dest)
                else -> Unit
            } } }) { Text("移动") } }, dismissButton = { AreaButton(onClick = { dialog = null; target = null }) { Text("取消") } })
        "delete" -> {
            val item = target
            var count by remember(item) { mutableIntStateOf(0) }
            LaunchedEffect(item) { if (item is LibraryTarget.Category) count = categoryRepo.recursiveTracks(item.category.id).size }
            AlertDialog(onDismissRequest = { dialog = null; target = null }, title = { Text("确认删除") },
                text = { Text(if (item is LibraryTarget.Category) "删除该类别将同时删除其中的 $count 首视频，是否继续？" else "删除这首视频？") },
                confirmButton = { AreaButton(onClick = { dialog = null; target = null; operate { when (item) { is LibraryTarget.Audio -> trackRepo.remove(item.track.id); is LibraryTarget.Category -> categoryRepo.delete(item.category.id); else -> Unit } } }, tone = ActionTone.DESTRUCTIVE) { Text("删除") } },
                dismissButton = { AreaButton(onClick = { dialog = null; target = null }) { Text("取消") } })
        }
        "deletePlaylist" -> {
            val list = playlists.firstOrNull { it.id == deletePlaylistId }
            AlertDialog(onDismissRequest = { dialog = "playlists" }, title = { Text("删除播放列表？") },
                text = { Text("删除「${list?.name.orEmpty()}」及其列表记录，视频库中的文件会保留。" +
                    if (list?.id == currentPlaylistId) "当前列表的播放会停止。" else "") },
                confirmButton = { TextButton(onClick = {
                    val id = deletePlaylistId
                    dialog = "playlists"
                    if (id != null) operate { PlayerHolder.deletePlaylist(context, id); notice = "已删除播放列表" }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { dialog = "playlists" }) { Text("取消") } })
        }
        "mode" -> PlaybackModeDialog(onClose = { dialog = null })
        "timer" -> TimerDialog(onClose = { dialog = null })
        "addToPlaylist" -> AlertDialog(onDismissRequest = { dialog = null; target = null }, containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, title = { Text("加入播放列表", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NewMusicPlaylist(onClick = { target = null; editText = ""; dialog = "newPlaylist" })
                playlists.forEach { list ->
                    val entries by playlistRepo.items(list.id).collectAsState(initial = emptyList())
                    MusicPlaylistCard(name = list.name, count = entries.size, current = list.id == currentPlaylistId, onOpen = {
                    val item = target
                    operate {
                        val tracks = when (item) {
                            is LibraryTarget.Audio -> listOf(item.track)
                            is LibraryTarget.Category -> categoryRepo.recursiveTracks(item.category.id)
                            else -> return@operate
                        }
                        if (list.id == currentPlaylistId) queueItems(tracks, false)
                        else {
                            playlistRepo.addTracks(list.id, tracks.map { it.id })
                            notice = "已加入${list.name}"
                        }
                    }
                    dialog = null; target = null
                }) }
            } }, confirmButton = { AreaButton(onClick = { dialog = null; target = null }) { Text("取消") } })
        "playlists", "playlistEdit", "playlistPick" -> AlertDialog(onDismissRequest = { dialog = null }, containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, title = { Text(if (dialog == "playlists") "播放列表" else selectedListName, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dialog == "playlists") {
                    NewMusicPlaylist(onClick = { editText = ""; dialog = "newPlaylist" })
                    Text("共 ${playlists.size} 个列表", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    playlists.forEach { list ->
                        val entries by playlistRepo.items(list.id).collectAsState(initial = emptyList())
                        MusicPlaylistCard(name = list.name, count = entries.size, current = list.id == currentPlaylistId,
                            onOpen = { playlistId = list.id; dialog = "playlistEdit" },
                            onRename = { playlistId = list.id; editText = list.name; dialog = "renamePlaylist" },
                            onDelete = { deletePlaylistId = list.id; dialog = "deletePlaylist" })
                    }
                } else if (dialog == "playlistEdit") {
                    AreaAction(onClick = { selectedTracks = emptySet(); selectedCategories = emptySet(); dialog = "playlistPick" }, modifier = Modifier.fillMaxWidth(), tone = ActionTone.CONFIRM) {
                        Text("＋ 添加视频或类别")
                        Spacer(Modifier.weight(1f))
                        Text(selectedListName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 100.dp))
                    }
                    val entries by playlistRepo.items(playlistId ?: -1L).collectAsState(initial = emptyList())
                    AreaAction(onClick = { queueItems(entries.mapNotNull { entry -> libraryTracks.firstOrNull { it.id == entry.trackId } }, true, playlistId); dialog = null }, enabled = entries.isNotEmpty()) { Text("播放此列表") }
                    entries.forEach { entry -> val track = libraryTracks.firstOrNull { it.id == entry.trackId }; if (track != null) Text(track.title, Modifier.fillMaxWidth().regionFrame().padding(12.dp)) }
                } else {
                    Row {
                        FilterChip(playlistPickMode == 0, { playlistPickMode = 0 }, { Text("按类别选择") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(playlistPickMode == 1, { playlistPickMode = 1 }, { Text("按文件选择") })
                    }
                    if (playlistPickMode == 0) {
                        allCategories.forEach { cat -> Row(Modifier.fillMaxWidth().regionFrame(cat.id in selectedCategories).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = cat.id in selectedCategories, onCheckedChange = { selectedCategories = if (it) selectedCategories + cat.id else selectedCategories - cat.id })
                            Text(categoryPath(cat, allCategories))
                        } }
                    } else {
                        OutlinedTextField(playlistSearch, { playlistSearch = it }, label = { Text("搜索文件名") })
                        libraryTracks.filter { it.title.contains(playlistSearch, ignoreCase = true) }.forEach { track -> Row(Modifier.fillMaxWidth().regionFrame(track.id in selectedTracks).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = track.id in selectedTracks, onCheckedChange = { selectedTracks = if (it) selectedTracks + track.id else selectedTracks - track.id })
                            Text(track.title)
                        } }
                    }
                    Button(onClick = { val id = playlistId; operate {
                        if (id != null) {
                            val tracks = libraryTracks.filter { it.id in selectedTracks } +
                                selectedCategories.flatMap { categoryRepo.recursiveTracks(it) }
                            if (id == sourcePlaylistId) queueItems(tracks, false)
                            else {
                                playlistRepo.addTracks(id, tracks.map { it.id })
                                notice = "已加入$selectedListName"
                            }
                            dialog = "playlistEdit"
                        }
                    } }, enabled = selectedTracks.isNotEmpty() || selectedCategories.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text("加入列表")
                        Spacer(Modifier.weight(1f))
                        Text(selectedListName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
                    }
                }
            } }, confirmButton = { AreaButton(onClick = { dialog = if (dialog == "playlists") null else "playlists" }) { Text(if (dialog == "playlists") "关闭" else "返回") } })
    }
}

private fun categoryPath(category: CategoryEntity, all: List<CategoryEntity>): String {
    val names = mutableListOf(category.name)
    var parentId = category.parentId
    while (parentId != null) {
        val parent = all.firstOrNull { it.id == parentId } ?: break
        names.add(parent.name)
        parentId = parent.parentId
    }
    return names.asReversed().joinToString(" / ")
}

/** 类别页空态：说明两类可行动作，避免只有一个「这里还没有内容」。 */
@Composable
private fun EmptyCategory() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(68.dp).clip(RoundedCornerShape(22.dp)).background(uiAccentContainer()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.FolderOpen, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f))
        }
        Text("这里还是空的", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(
            "新建类别给视频分组，比如「睡前」「通勤」\n也可以长按视频，用「移动到…」放进来",
            style = MaterialTheme.typography.bodySmall,
            color = uiMuted(),
            textAlign = TextAlign.Center,
            lineHeight = 19.sp,
        )
    }
}

/** 空库引导：给出下一步动作，而不是只丢一句「暂无视频」。 */
@Composable
private fun EmptyLibrary() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(uiAccentContainer()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.GraphicEq,
                    null,
                    Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                )
            }
            Text("还没有视频", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                "去「提取」页粘贴 B 站或抖音链接\n就能把视频保存到这里",
                style = MaterialTheme.typography.bodySmall,
                color = uiMuted(),
                textAlign = TextAlign.Center,
                lineHeight = 19.sp,
            )
        }
    }
}

/**
 * 视频列表：左侧序号 + 卡片内容。
 *
 * 拖动靠**长按**整行触发（不再单独放把手），松手时把新顺序交给 [onReorder] 落库；
 * 按字段排序时（[dragEnabled] = false）拖动无意义，自动关掉。
 */
@Composable
private fun LibraryTrackList(
    tracks: List<TrackEntity>,
    selecting: Boolean,
    batchIds: Set<Long>,
    deleting: Boolean,
    onReorder: suspend (List<Long>) -> Unit,
    onPlay: (TrackEntity) -> Unit,
    onMore: (TrackEntity) -> Unit,
    onSelect: (Long) -> Unit,
    onReorderFailed: (String) -> Unit,
    dragEnabled: Boolean = true,
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val maxSpeed = with(density) { 9.dp.toPx() }
    val inset = with(density) { 2.dp.toPx() }
    var draft by remember { mutableStateOf(tracks) }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    // 被拖行中心的屏幕坐标（相对列表视口），只跟手指走，用来画浮层。
    var pointerY by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableFloatStateOf(0f) }
    // 自上次换位以来手指走了多少（正 = 往下），判定用，换位后清零。
    var residual by remember { mutableFloatStateOf(0f) }
    var boxHeight by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(tracks)
    val latestReorder by rememberUpdatedState(onReorder)

    // 库数据刷新时同步草稿；正在拖动时不要打断。
    LaunchedEffect(tracks) { if (draggedId == null) draft = tracks }

    fun commit() {
        val ids = draft.map { it.id }
        if (ids == latest.map { it.id } || saving) return
        saving = true
        scope.launch {
            try { latestReorder(ids) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onReorderFailed("顺序未保存，请重试") }
            finally { saving = false }
        }
    }
    fun beginDrag(id: Long) {
        val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        draggedId = id
        pointerY = info.offset + info.size / 2f
        dragHeight = info.size.toFloat()
        residual = 0f
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    // 每一帧：先按贴边程度滚列表，再按「手指落在哪一行」落位。
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
                pointerY < top + edge -> -(((top + edge - pointerY) / edge).coerceIn(0f, 1f)) * maxSpeed
                pointerY > bottom - edge -> (((pointerY - bottom + edge) / edge).coerceIn(0f, 1f)) * maxSpeed
                else -> 0f
            }
            if (speed != 0f) {
                state.scrollBy(speed)
                // 内容往上滚 = 手指相对内容往下走，等价于手指位移。
                residual += speed
            }
            val from = draft.indexOfFirst { it.id == id }
            if (from < 0) break
            val slots = layout.visibleItemsInfo.map {
                QueueSlot(it.index, it.offset.toFloat(), it.size.toFloat())
            }
            val slot = slots.firstOrNull { it.index == from } ?: continue
            val y = slot.top + slot.height / 2f + residual
            val target = dropTarget(from, y, slots, inset) ?: continue
            // 换位后保留「手指落在这一行的哪个位置」，同时丢掉槽位绝对值（锚点会整体平移）。
            val targetSlot = slots.firstOrNull { it.index == target }
            draft = draft.toMutableList().apply { add(target, removeAt(from)) }
            residual = if (targetSlot != null) y - (targetSlot.top + targetSlot.height / 2f) else 0f
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { boxHeight = it.height }) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(bottom = 12.dp),
            // 手势挂在列表上，而不是挂在某一行上：拖动开始后被拖行会换成等高的占位 Spacer，
            // 挂在行上会在第一次换位时随节点一起销毁，拖动当场中断（表现为「拖一下就自己停了」）。
            modifier = Modifier.pointerInput(dragEnabled, selecting, deleting, saving) {
                if (dragEnabled && !selecting && !deleting) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val infos = state.layoutInfo.visibleItemsInfo
                            val hit = infos.firstOrNull { offset.y >= it.offset && offset.y < it.offset + it.size }
                                ?: infos.minByOrNull { abs(it.offset + it.size / 2f - offset.y) }
                            if (!saving) hit?.let { beginDrag(it.key as Long) }
                        },
                        onDrag = { change, amount ->
                            if (draggedId != null) {
                                change.consume()
                                // 一次事件只挪位置，换位交给每帧的循环。
                                pointerY += amount.y
                                residual += amount.y
                            }
                        },
                        onDragEnd = {
                            if (draggedId != null) {
                                draggedId = null
                                commit()
                            }
                        },
                        onDragCancel = { draggedId = null; residual = 0f; draft = latest },
                    )
                }
            },
        ) {
            itemsIndexed(draft, key = { _, track -> track.id }) { index, track ->
                if (draggedId == track.id) {
                    // 占位：保住原来的高度，上下行原地不动。
                    Spacer(Modifier.fillMaxWidth().height(with(density) { dragHeight.toDp() }))
                } else {
                    LibraryAudioRow(
                        track = track,
                        number = index + 1,
                        onPlay = { onPlay(track) },
                        onMore = { onMore(track) },
                        selecting = selecting,
                        selected = track.id in batchIds,
                        selectionEnabled = !deleting && draggedId == null,
                        onSelect = { onSelect(track.id) },
                    )
                }
            }
        }
        // 浮起来的被拖行：位置只跟手指走，不跟列表布局纠缠，所以不会抖。
        val dragged = draft.firstOrNull { it.id == draggedId }
        if (dragged != null && dragHeight > 0f) {
            val limit = (boxHeight - dragHeight).coerceAtLeast(0f)
            val topPx = (pointerY - dragHeight / 2f).coerceIn(0f, limit)
            LibraryAudioRow(
                track = dragged,
                number = draft.indexOfFirst { it.id == dragged.id } + 1,
                onPlay = { },
                onMore = { },
                selecting = selecting,
                selected = dragged.id in batchIds,
                selectionEnabled = false,
                onSelect = { },
                modifier = Modifier
                    .zIndex(2f)
                    .offset { IntOffset(0, topPx.roundToInt()) }
                    .graphicsLayer {
                        scaleX = 1.02f
                        scaleY = 1.02f
                        shadowElevation = 16f
                    },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryAudioRow(track: TrackEntity, onPlay: () -> Unit, onMore: () -> Unit,
    selecting: Boolean = false, selected: Boolean = false, selectionEnabled: Boolean = true, onSelect: () -> Unit = {},
    number: Int = 0, modifier: Modifier = Modifier,
    longPressSelects: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val queue by PlayerHolder.queue.collectAsState()
    val index by PlayerHolder.currentIndex.collectAsState()
    val playing by PlayerHolder.isPlaying.collectAsState()
    val isCurrent = queue.getOrNull(index)?.track?.id == track.id
    val highlighted = if (selecting) selected else isCurrent
    val shape = RoundedCornerShape(16.dp)

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clip(shape)
            .background(if (highlighted) uiAccentContainer() else uiCardColor())
            .border(
                1.dp,
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else uiHairline(),
                shape,
            )
            .padding(start = 4.dp, end = 2.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 长按整行 = 拖动排序（由列表上的长按手势处理），所以这里不再挂 onLongClick；
        // 多选改从「更多 → 多选」进入。只有类别视图的小列表保留长按多选（longPressSelects）。
        Row(
            Modifier.weight(1f).combinedClickable(
                enabled = selectionEnabled,
                onClick = { if (selecting) onSelect() },
                onLongClick = if (longPressSelects) onSelect else null,
                onLongClickLabel = if (longPressSelects) "选择视频" else null,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        if (number > 0 && !selecting) {
            // 序号做成小圆标：贴左边缘、和内容只隔一点，比裸数字更好认也更整齐。
            // 两端统一用主色实底 —— 之前「正在播放用实底、其余用 10% 淡底」会让两端看起来不一致。
            Box(
                Modifier
                    .height(22.dp)
                    .widthIn(min = 22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$number",
                    Modifier.padding(horizontal = 6.dp),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        if (selecting) {
            Checkbox(checked = selected, enabled = selectionEnabled, onCheckedChange = { onSelect() })
            Spacer(Modifier.width(4.dp))
        }
        TrackCover(track)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            // 作者 · 时长一行；入库时间单独一行 —— 一行塞不下完整的「年-月-日 时:分」，
            // 之前被 maxLines=1 截成了「2026-10-27 16:4」。
            val uploader = track.uploader?.takeIf { it.isNotBlank() }
            val meta = buildList {
                if (uploader != null) add(uploader)
                track.durationSec?.takeIf { it > 0 }?.let { add(formatDuration(it)) }
                if (track.locallyEdited) add("已剪辑")
            }.joinToString("  ·  ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    Modifier.fillMaxWidth(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = uiMuted(),
                )
            }
            Text(
                if (track.addedAtMs > 0) shortAddedTime(track.addedAtMs) else "时间未记录",
                Modifier.fillMaxWidth(),
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = uiMuted(),
            )
        }
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (isCurrent) MaterialTheme.colorScheme.primary else uiAccentContainer())
                .clickable(enabled = !selecting) {
                    if (isCurrent) scope.launch { PlayerHolder.toggle(context) } else onPlay()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isCurrent && playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (isCurrent && playing) "暂停视频" else "播放视频",
                Modifier.size(21.dp),
                tint = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            )
        }
        }
        if (!selecting) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onMore),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    "更多",
                    Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * 封面：优先用平台地址，其次用本地抽帧缓存，都没有才显示平台底色占位。
 *
 * 老版本入库的视频没有 coverUrl，本地抽帧能把封面补上；平台地址过期时也能兜住。
 */
@Composable
private fun TrackCover(track: TrackEntity) {
    val context = LocalContext.current
    val isBili = track.platform.equals("bilibili", ignoreCase = true)
    val start = if (isBili) Color(0xFFFB7299) else Color(0xFF28262F)
    val end = if (isBili) Color(0xFFFFA9C2) else Color(0xFF4C4857)
    var local by remember(track.id, track.localPath) { mutableStateOf<File?>(null) }
    LaunchedEffect(track.id, track.localPath) {
        if (track.coverUrl.isNullOrBlank()) local = CoverCache.poster(context, track)
    }
    val model: Any? = track.coverUrl?.takeIf { it.isNotBlank() } ?: local
    Box(
        Modifier
            .size(width = 68.dp, height = 48.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Brush.linearGradient(listOf(start, end))),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.Movie,
                null,
                Modifier.size(22.dp),
                tint = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}

/** 入库时间：一律显示「yyyy-MM-dd HH:mm」，不用「今天/昨天」这类相对时间。 */
private fun shortAddedTime(ms: Long): String {
    if (ms <= 0) return "时间未记录"
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
}

@Composable
internal fun TimerDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val status = timerStatus()
    var minutes by remember { mutableStateOf("15") }
    var finish by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = onClose, title = { Text("定时播放") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        status?.let { Text("当前定时：$it", style = MaterialTheme.typography.titleSmall) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(15L, 30L, 60L).forEach { value -> AreaButton(onClick = { minutes = value.toString() }, tone = ActionTone.TIMER) { Text(if (value == 60L) "1小时" else "${value}分钟") } } }
        OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit) }, label = { Text("自定义分钟") })
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = finish, onCheckedChange = { finish = it }); Text("播完整个视频再停") }
        Text(if (finish) "计时结束后，播放完当前视频再停止。" else "计时结束后立即停止播放。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status != null) AreaButton(onClick = { PlayerHolder.cancelTimer(); onClose() }, modifier = Modifier.fillMaxWidth(), tone = ActionTone.TIMER) { Text("取消定时播放") }
    } }, confirmButton = { AreaButton(onClick = { val duration = minutes.toLongOrNull(); if (duration != null && duration in 1..1440) { PlayerHolder.setTimer(context, duration, finish); onClose() } }, tone = ActionTone.TIMER) { Text("开始计时") } }, dismissButton = { AreaButton(onClick = onClose) { Text("关闭") } })
}

@Composable
private fun NameEditor(initialName: String, onNameChange: (String) -> Unit) {
    var value by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(
        initialName, androidx.compose.ui.text.TextRange(0, initialName.length)
    )) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it; onNameChange(it.text) },
        label = { Text("名称") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
}
