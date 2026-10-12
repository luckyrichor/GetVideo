package com.getvideo.app.ui.screen

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.getvideo.app.data.local.TrackEntity
import com.getvideo.app.data.repo.ApiProvider
import com.getvideo.app.data.repo.TrackRepository
import com.getvideo.app.data.repo.Settings
import com.getvideo.app.player.PlayerHolder
import com.getvideo.app.ui.formatDuration
import com.getvideo.app.ui.formatSize
import com.getvideo.app.ui.platformLabel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LibraryViewModel(private val repo: TrackRepository, context: Context) : ViewModel() {

    private val query = MutableStateFlow("")
    private val platform = MutableStateFlow<String?>(null)
    private val onlyFavorites = MutableStateFlow(false)

    var message by mutableStateOf<String?>(null)
        private set
    var syncing by mutableStateOf(false)
        private set

    val serverConfigured: StateFlow<Boolean> = Settings(context).serverUrl
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val tracks: StateFlow<List<TrackEntity>> =
        combine(query, platform, onlyFavorites) { q, p, fav -> Triple(q, p, fav) }
            .flatMapLatest { (q, p, fav) ->
                repo.filtered(q, p, fav)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentQuery: StateFlow<String> = query.asStateFlow()
    val currentPlatform: StateFlow<String?> = platform.asStateFlow()
    val favoritesOnly: StateFlow<Boolean> = onlyFavorites.asStateFlow()

    fun setQuery(q: String) { query.value = q }
    fun setPlatform(p: String?) { platform.value = p; onlyFavorites.value = false }
    fun toggleFavoritesFilter() { onlyFavorites.value = !onlyFavorites.value }

    fun sync() = viewModelScope.launch {
        syncing = true
        runCatching { repo.sync() }
            .onSuccess { message = if (it > 0) "同步到 $it 条" else "已是最新" }
            .onFailure { message = ApiProvider.humanMessage(it) }
        syncing = false
    }

    fun toggleFavorite(t: TrackEntity) = viewModelScope.launch {
        repo.setFavorite(t.id, !t.favorite)
    }

    fun playOrDownload(context: Context, t: TrackEntity) = viewModelScope.launch {
        val local = t.localPath?.takeIf { java.io.File(it).exists() }
        if (local != null) {
            PlayerHolder.play(context, t, local)
            message = "正在播放：${t.title}"
            return@launch
        }
        message = "下载中…"
        repo.download(t)
            .onSuccess {
                PlayerHolder.play(context, t, it.absolutePath)
                message = "正在播放：${t.title}"
            }
            .onFailure { message = ApiProvider.humanMessage(it) }
    }

    fun export(context: Context, t: TrackEntity, uri: Uri) = viewModelScope.launch {
        message = "正在导出…"
        repo.download(t).onSuccess { file ->
            runCatching {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("无法打开目标文件")
                output.use { out -> file.inputStream().use { it.copyTo(out) } }
            }.onSuccess { message = "视频已导出" }
                .onFailure { message = ApiProvider.humanMessage(it) }
        }.onFailure { message = ApiProvider.humanMessage(it) }
    }

    fun clearMessage() { message = null }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryViewModel(TrackRepository(context), context) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(context))
    val tracks by vm.tracks.collectAsState()
    val query by vm.currentQuery.collectAsState()
    val platform by vm.currentPlatform.collectAsState()
    val favOnly by vm.favoritesOnly.collectAsState()
    val serverConfigured by vm.serverConfigured.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var exportTrack by remember { mutableStateOf<TrackEntity?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) exportTrack?.let { vm.export(context, it, uri) }
        exportTrack = null
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                label = { Text("搜索标题、作者或分类") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(platform == null && !favOnly, { vm.setPlatform(null) }, { Text("全部") })
                FilterChip(platform == "bilibili", { vm.setPlatform("bilibili") }, { Text("B站") })
                FilterChip(platform == "douyin", { vm.setPlatform("douyin") }, { Text("抖音") })
                FilterChip(favOnly, { vm.toggleFavoritesFilter() }, { Text("收藏") })
                Spacer(Modifier.weight(1f))
                if (serverConfigured) {
                    TextButton(onClick = vm::sync, enabled = !vm.syncing) {
                        Text(if (vm.syncing) "同步中…" else "云同步")
                    }
                }
            }

            if (tracks.isEmpty()) {
                EmptyHint(query.isNotBlank() || favOnly)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(tracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            onPlay = { vm.playOrDownload(context, track) },
                            onFavorite = { vm.toggleFavorite(track) },
                            onExport = {
                                exportTrack = track
                                val name = track.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80)
                                val extension = track.audioFormat.takeIf { it in setOf("mp4", "webm", "mkv") } ?: "mp4"
                                exportLauncher.launch("${name.ifBlank { "GetVideo" }}.$extension")
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(filtered: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            if (filtered) "没有符合条件的视频" else "视频库还是空的\n去「提取」页粘一条分享链接试试",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackRow(track: TrackEntity, onPlay: () -> Unit, onFavorite: () -> Unit, onExport: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(16.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            // 没下载的显示下载图标，避免点下去才发现要等
            if (track.localPath != null) Icons.Default.PlayArrow else Icons.Default.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge)
            if (track.categories.isNotBlank()) {
                Text(
                    track.categories + when (track.hasVocal) {
                        true -> " · 人声"
                        false -> " · 无人声"
                        null -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(platformLabel(track.platform))
                    track.uploader?.let { append(" · ").append(it) }
                    append(" · ").append(formatDuration(track.durationSec))
                    append(" · ").append(formatSize(track.audioBytes))
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onExport) {
            Icon(Icons.Default.SaveAlt, contentDescription = "导出视频")
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (track.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "收藏",
                tint = if (track.favorite) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
