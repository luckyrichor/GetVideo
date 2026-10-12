package com.getvideo.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.getvideo.app.data.local.AppDatabase
import com.getvideo.app.data.repo.DiagnosticsStore
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MyScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracks by AppDatabase.get(context).trackDao().observeAll().collectAsState(initial = emptyList())
    var dialog by remember { mutableStateOf<String?>(null) }
    var showAccount by remember { mutableStateOf(false) }
    val account by com.getvideo.app.data.repo.AccountStore.current.collectAsState()
    var showLogin by remember { mutableStateOf(false) }
    var showAgent by remember { mutableStateOf(false) }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知" }
    val diagnosticExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = DiagnosticsStore.exportFailures()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        ?: error("无法创建文件")
                }
            }.onFailure { DiagnosticsStore.event("导出诊断日志", it.javaClass.simpleName, false) }
        }
    }
    val bytes = tracks.sumOf { it.localPath?.let { path -> File(path).takeIf(File::exists)?.length() } ?: 0L }
    if (showAccount) {
        Column {
            AreaButton(onClick = { showAccount = false }) { Text("‹ 返回") }
            AccountScreen()
        }
        return
    }
    if (showLogin) {
        Column {
            AreaButton(onClick = { showLogin = false }) { Text("‹ 返回") }
            LoginScreen()
        }
        return
    }
    if (showAgent) {
        Column {
            AreaButton(onClick = { showAgent = false }) { Text("‹ 返回") }
            AgentScreen()
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("我的", style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth().regionFrame().clickable { showAccount = true }.padding(16.dp)) {
            Icon(Icons.Default.AccountCircle, null, Modifier.size(52.dp)); Spacer(Modifier.width(12.dp))
            Column { Text(account?.maskedPhone ?: "访客", style = MaterialTheme.typography.titleMedium); Text(if (account == null) "点击登录账号" else "账号管理", style = MaterialTheme.typography.bodySmall) }
        }

        AreaAction(onClick = { dialog = "server" }) { Text("服务器设置") }
        AreaAction(onClick = { showLogin = true }) { Text("平台授权") }
        AreaAction(onClick = { dialog = "storage" }) { Text("存储空间：${bytes / 1024 / 1024} MB") }
        AreaAction(onClick = { dialog = "feedback" }) { Text("意见反馈") }
        AreaAction(onClick = { diagnosticExport.launch("GetVideo-diagnostics.txt") }) { Text("导出诊断日志") }
        AreaAction(onClick = { scope.launch {
            runCatching {
                val text = DiagnosticsStore.exportFailures().ifBlank { "暂无失败诊断日志" }
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("GetVideo 诊断日志", text))
            }.onSuccess { Toast.makeText(context, "已复制诊断日志", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "复制失败，请重试", Toast.LENGTH_SHORT).show() }
        } }) { Text("复制诊断日志") }
        AreaAction(onClick = { showAgent = true }) { Text("视频助手") }

        AreaAction(onClick = { dialog = "about" }) { Text("版本 $version") }
    }
    when (dialog) {
        "storage" -> InfoDialog("存储空间", "本机已保存 ${tracks.size} 首视频，约 ${bytes / 1024 / 1024} MB。") { dialog = null }
        "feedback" -> InfoDialog("意见反馈", "意见反馈功能暂未开放。") { dialog = null }
        "about" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("GetVideo $version") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("分享链接提取视频，分类、播放和管理均可在本机完成。")
            AreaButton(onClick = { dialog = "server" }) { Text("开发者选项") }
        } }, confirmButton = { AreaButton(onClick = { dialog = null }) { Text("关闭") } })
        "server" -> ServerSettingsDialog { dialog = null }
    }
}

@Composable
private fun InfoDialog(title: String, text: String, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Text(text) }, confirmButton = { AreaButton(onClick = close) { Text("关闭") } })
}
