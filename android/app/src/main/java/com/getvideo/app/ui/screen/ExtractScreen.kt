package com.getvideo.app.ui.screen

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.getvideo.app.data.local.TrackEntity
import com.getvideo.app.data.repo.ApiProvider
import com.getvideo.app.data.repo.TrackRepository
import com.getvideo.app.data.repo.DiagnosticsStore
import com.getvideo.app.data.repo.PendingNotice
import com.getvideo.app.ui.formatDuration
import kotlinx.coroutines.launch

class ExtractViewModel(private val repo: TrackRepository, private val version: String) : ViewModel() {

    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var result by mutableStateOf<TrackEntity?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var usingServer by mutableStateOf(false)
        private set
    internal val notification = PendingNotice<String>()
    private var startedAt = 0L

    private fun record(message: String) {
        DiagnosticsStore.record(startedAt, message)
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        viewModelScope.launch {
            busy = true
            usingServer = false
            error = null
            result = null
            notification.take()
            startedAt = SystemClock.elapsedRealtime()
            DiagnosticsStore.beginLocal(version)
            repo.extract(text, ::record)
                .onSuccess {
                    result = it
                    notification.post("${it.title} · ${formatDuration(it.durationSec)} · 已加入视频库")
                    DiagnosticsStore.finish(true)
                }
                .onFailure {
                    error = ApiProvider.humanMessage(it)
                    notification.post(error!!)
                    record("提取失败：${it.javaClass.simpleName} · $error")
                    DiagnosticsStore.finish(false)
                }
            busy = false
        }
    }

    fun submitViaServer() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        viewModelScope.launch {
            busy = true
            usingServer = true
            error = null
            result = null
            notification.take()
            startedAt = SystemClock.elapsedRealtime()
            DiagnosticsStore.beginServer(version)
            record("手动选择服务器提取")
            repo.extractViaServer(text, ::record)
                .onSuccess {
                    result = it
                    notification.post("${it.title} · ${formatDuration(it.durationSec)} · 已加入视频库")
                    DiagnosticsStore.finish(true)
                }
                .onFailure {
                    error = ApiProvider.humanMessage(it)
                    notification.post(error!!)
                    record("服务器提取失败：${it.javaClass.simpleName} · $error")
                    DiagnosticsStore.finish(false)
                }
            busy = false
            usingServer = false
        }
    }

    /**
     * 读剪贴板自动填入。
     *
     * 这是整个 App 最关键的体验点：用户刚从抖音复制完分享文案切过来，
     * 手动粘贴多一步就少一分用。
     */
    fun pasteFromClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotBlank() && looksLikeShareLink(text)) input = text
    }

    private fun looksLikeShareLink(text: String): Boolean =
        listOf("bilibili.com", "b23.tv", "douyin.com", "BV").any { text.contains(it) }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ExtractViewModel(
                TrackRepository(context),
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知版本",
            ) as T
    }
}

@Composable
fun ExtractScreen(prefill: String? = null, onSharedConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    val vm: ExtractViewModel = viewModel(factory = ExtractViewModel.Factory(context))
    val snackbar = remember { SnackbarHostState() }
    var accountDialog by remember { mutableStateOf(false) }
    val server by remember { com.getvideo.app.data.repo.Settings(context).serverUrl }.collectAsState(initial = "")
    if (accountDialog) AccountLoginDialog { accountDialog = false }

    LaunchedEffect(prefill) {
        if (!prefill.isNullOrBlank()) {
            vm.input = prefill
            onSharedConsumed()
            vm.submit()
        } else if (vm.input.isBlank()) vm.pasteFromClipboard(context)
    }
    LaunchedEffect(Unit) {
        snapshotFlow { vm.notification.value }.collect { message ->
            if (message != null) {
                vm.notification.take()
                snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Short)
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("提取视频", style = MaterialTheme.typography.headlineSmall)
            Text("粘贴视频链接或完整分享文本，保存你想听的声音。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = vm.input, onValueChange = { vm.input = it }, enabled = !vm.busy,
                label = { Text("分享文本或链接") },
                placeholder = { Text("在这里粘贴抖音、哔哩哔哩的分享内容") },
                minLines = 5, maxLines = 8, modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AreaButton(onClick = { vm.pasteFromClipboard(context) }, enabled = !vm.busy, tone = ActionTone.SYNC) { Text("粘贴剪贴板") }
                AreaButton(onClick = { vm.input = "" }, enabled = !vm.busy && vm.input.isNotBlank()) { Text("清空") }
            }
            Button(onClick = { vm.submit() }, enabled = !vm.busy && vm.input.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.large) {
                Text(if (vm.busy && !vm.usingServer) "正在提取…" else "提取视频")
            }
            OutlinedButton(onClick = { if (com.getvideo.app.data.repo.AccountStore.forServer(server) == null) accountDialog = true else vm.submitViaServer() }, enabled = !vm.busy && vm.input.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.large) {
                Text(if (vm.busy && vm.usingServer) "服务器提取中…" else "从服务器提取")
            }
            if (vm.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (vm.usingServer) "服务器正在提取并下载到手机" else "手机正在解析并下载视频", style = MaterialTheme.typography.bodySmall)
            }
            ExtractionDiagnostics()
        }
    }
}
