package com.getvideo.app.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.BackHandler
import com.getvideo.app.data.repo.DiagnosticsStore
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import kotlinx.coroutines.launch
import java.io.File

private sealed interface LibraryTarget {
    data class Audio(val track: TrackEntity) : LibraryTarget
    data class Category(val category: CategoryEntity) : LibraryTarget
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

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .height(48.dp).regionFrame(), verticalAlignment = Alignment.CenterVertically) {
                listOf("全部视频", "类别").forEachIndexed { index, label ->
                    if (index > 0) VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    val selected = section == index
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .selectable(selected = selected, role = Role.Tab, onClick = {
                            if (deleting) return@selectable
                            exitSelection()
                            section = index
                            if (index == 1) categoryId = null
                        }), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
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
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, label = { Text("搜索视频名称") }, singleLine = true, modifier = Modifier.weight(1f))
                    AreaButton(onClick = { dateDialog = true }, modifier = Modifier.padding(top = 8.dp).height(56.dp)) { Text("日期", style = MaterialTheme.typography.bodyLarge) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (serverUrl.isNotBlank()) AreaButton(onClick = { scope.launch {
                        if (com.getvideo.app.data.repo.AccountStore.forServer(serverUrl) == null) { accountDialog = true; return@launch }
                        syncing = true
                        runCatching { DiagnosticsStore.operation("云端同步") { trackRepo.sync() } }.onSuccess { notice = "已同步 $it 条" }
                            .onFailure { notice = ApiProvider.humanMessage(it) }
                        syncing = false
                    } }, enabled = !syncing, tone = ActionTone.SYNC) { Text(if (syncing) "同步中…" else "云端同步") }
                    if (timer != null) {
                        Text(if (timer.contains(":")) "剩余 $timer" else timer,
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        AreaButton(onClick = { PlayerHolder.cancelTimer(); notice = "已取消定时播放" }, tone = ActionTone.TIMER) {
                            Text("取消定时播放")
                        }
                    }
                }
                if (startMs != null || endTimeMs != null) {
                    AreaButton(onClick = { startMs = null; endTimeMs = null }) { Text("${startMs?.let { displayDateTime(it) } ?: "不限"} ~ ${endTimeMs?.let { displayDateTime(it) } ?: "不限"} ×") }
                }
                if (allTracks.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无视频") }
                else LazyColumn { items(allTracks, key = { it.id }) { track ->
                    LibraryAudioRow(track, onPlay = { queueItems(listOf(track), true) }, onMore = { target = LibraryTarget.Audio(track) },
                        selecting = selecting, selected = track.id in batchIds, selectionEnabled = !deleting,
                        onSelect = { selectTrack(track.id) })
                } }
            } else {
                val parent = allCategories.firstOrNull { it.id == categoryId }
                if (parent != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AreaButton(onClick = { exitSelection(); categoryId = parent.parentId }) { Text("‹ 返回") }
                        Text(categoryPath(parent, allCategories), modifier = Modifier.weight(1f).padding(start = 20.dp, end = 16.dp),
                            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    }
                } else if (frequent.any { it.accessCount > 0 }) {
                    Text("常用类别", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                        frequent.filter { it.accessCount > 0 }.forEach { cat ->
                            AssistChip(onClick = { exitSelection(); categoryId = cat.id; operate { categoryRepo.recordAccess(cat.id) } },
                                label = { Text(cat.name) }, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
                val children = allCategories.filter { it.parentId == categoryId }
                if (categoryId == null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        AreaButton(onClick = { dialog = "newCategory"; editText = ""; selectedParent = null }, tone = ActionTone.CONFIRM) { Text("＋ 新建类别") }
                        AreaButton(onClick = { dialog = "playlists" }) { Text("播放列表") }
                    }
                } else if (parent?.kind != "LEAF") {
                    AreaButton(onClick = { dialog = "newCategory"; editText = ""; selectedParent = categoryId }, tone = ActionTone.CONFIRM) { Text("＋ 新建子类别") }
                }
                if (children.isEmpty() && categoryTracks.isEmpty()) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("这里还没有内容") }
                LazyColumn {
                    items(children, key = { "c${it.id}" }) { cat ->
                        val count = if (cat.kind == "CONTAINER") allCategories.count { it.parentId == cat.id }
                            else libraryTracks.count { it.localCategoryId == cat.id }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).regionFrame().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null)
                            Column(Modifier.weight(1f).clickable { exitSelection(); categoryId = cat.id; operate { categoryRepo.recordAccess(cat.id) } }.padding(12.dp)) {
                                Text(cat.name)
                                Text(if (cat.kind == "CONTAINER") "$count 个类别" else "$count 首视频", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { targetTracks(LibraryTarget.Category(cat)) }) { Icon(Icons.Default.PlayArrow, "播放类别") }
                            IconButton(onClick = { target = LibraryTarget.Category(cat) }) { Icon(Icons.Default.MoreVert, "更多") }
                        }
                    }
                    items(categoryTracks, key = { "t${it.id}" }) { track ->
                        LibraryAudioRow(track, onPlay = { queueItems(listOf(track), true) }, onMore = { target = LibraryTarget.Audio(track) },
                        selecting = selecting, selected = track.id in batchIds, selectionEnabled = !deleting,
                        onSelect = { selectTrack(track.id) })
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

    editTrack?.let { track -> AudioEditScreen(track, onClose = { editTrack = null }, onSaved = { editTrack = null; notice = "剪辑已保存" }) }
    restoreTrack?.let { track -> AlertDialog(onDismissRequest = { restoreTrack = null }, title = { Text("恢复服务器原版？") }, text = { Text("本地剪辑版将替换为服务器原版，收藏与播放列表保留。") }, confirmButton = { TextButton(onClick = { restoreTrack = null; operate { trackRepo.restoreOriginal(track.id); notice = "已恢复原版" } }) { Text("恢复") } }, dismissButton = { TextButton(onClick = { restoreTrack = null }) { Text("取消") } }) }

    if (dateDialog) DateTimeRangeDialog(startMs, endTimeMs, onClose = { dateDialog = false }, onApply = { from, until ->
        startMs = from; endTimeMs = until; dateDialog = false
    })

    if (dialog == null) target?.let { item ->
        AlertDialog(onDismissRequest = { target = null }, containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
            title = { Text(if (item is LibraryTarget.Audio) item.track.title else (item as LibraryTarget.Category).category.name,
                fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MusicMenuAction("加入当前播放列表", Icons.AutoMirrored.Filled.PlaylistAdd, featured = true, status = currentListName, onClick = {
                    target = null
                    operate { val tracks = if (item is LibraryTarget.Audio) listOf(item.track) else categoryRepo.recursiveTracks((item as LibraryTarget.Category).category.id); queueItems(tracks, false) }
                })
                MusicMenuAction("加入其他播放列表", Icons.AutoMirrored.Filled.QueueMusic, onClick = { dialog = "addToPlaylist" })
                MusicMenuAction("播放列表", Icons.Default.LibraryMusic, onClick = { target = null; dialog = "playlists" })
                MusicMenuAction("播放方式", Icons.Default.Repeat, status = mode.label, onClick = { target = null; dialog = "mode" })
                MusicMenuAction("定时播放", Icons.Default.Timer, status = timer ?: "未设置", onClick = { target = null; dialog = "timer" })
                if (timer != null) MusicMenuAction("取消定时播放", Icons.Default.TimerOff, onClick = { PlayerHolder.cancelTimer(); target = null; notice = "已取消定时播放" })
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                if (item !is LibraryTarget.Category || item.category.id != 1L) {
                    MusicMenuAction("重命名", Icons.Default.Edit, onClick = { editText = if (item is LibraryTarget.Audio) item.track.title else (item as LibraryTarget.Category).category.name; dialog = "rename" })
                    MusicMenuAction("移动到…", Icons.AutoMirrored.Filled.DriveFileMove, onClick = { selectedParent = null; dialog = "move" })
                    MusicMenuAction("删除", Icons.Default.DeleteOutline, destructive = true, onClick = { dialog = "delete" })
                }
                if (item is LibraryTarget.Audio) {
                    MusicMenuAction("剪辑视频", Icons.Default.ContentCut, status = if (item.track.locallyEdited) "本地剪辑版" else null, onClick = { editTrack = item.track; target = null })
                    if (item.track.locallyEdited && item.track.id > 0) MusicMenuAction("恢复服务器原版", Icons.Default.Restore, onClick = { restoreTrack = item.track; target = null })
                }
                if (item is LibraryTarget.Audio) MusicMenuAction("导出", Icons.Default.IosShare, onClick = { exportTrack = item.track; target = null; exportLauncher.launch("${item.track.title.take(60)}.${item.track.audioFormat}") })
                if (item is LibraryTarget.Category && item.category.kind != "LEAF") MusicMenuAction("新建子类别", Icons.Default.CreateNewFolder, onClick = { selectedParent = item.category.id; editText = ""; target = null; dialog = "newCategory" })
            } }, confirmButton = { AreaButton(onClick = { target = null }) { Text("关闭") } })
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryAudioRow(track: TrackEntity, onPlay: () -> Unit, onMore: () -> Unit,
    selecting: Boolean = false, selected: Boolean = false, selectionEnabled: Boolean = true, onSelect: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val queue by PlayerHolder.queue.collectAsState()
    val index by PlayerHolder.currentIndex.collectAsState()
    val playing by PlayerHolder.isPlaying.collectAsState()
    val isCurrent = queue.getOrNull(index)?.track?.id == track.id
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).regionFrame(if (selecting) selected else isCurrent)
        .combinedClickable(enabled = selectionEnabled, onClick = { if (selecting) onSelect() }, onLongClick = onSelect,
            onLongClickLabel = "选择视频")
        .padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selecting) Checkbox(checked = selected, enabled = selectionEnabled, onCheckedChange = { onSelect() })
        Column(Modifier.weight(1f).padding(8.dp)) {
            Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.uploader ?: track.platform, style = MaterialTheme.typography.bodySmall)
            Text(if (track.addedAtMs > 0) displayDateTime(track.addedAtMs, seconds = true) else "时间未记录",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!selecting) IconButton(onClick = { if (isCurrent) scope.launch { PlayerHolder.toggle(context) } else onPlay() }) {
            Icon(if (isCurrent && playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (isCurrent && playing) "暂停视频" else "播放视频", tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        if (!selecting) IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "更多") }
    }
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
