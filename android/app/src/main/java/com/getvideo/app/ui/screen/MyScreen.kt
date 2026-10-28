package com.getvideo.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getvideo.app.data.local.AppDatabase
import com.getvideo.app.data.repo.DiagnosticsStore
import com.getvideo.app.ui.components.HeroCard
import com.getvideo.app.ui.components.PageColumn
import com.getvideo.app.ui.components.RowDivider
import com.getvideo.app.ui.components.SectionCard
import com.getvideo.app.ui.components.SectionTitle
import com.getvideo.app.ui.components.SettingRow
import com.getvideo.app.ui.components.StatusChip
import com.getvideo.app.ui.components.SubPageScaffold
import com.getvideo.app.ui.components.uiAccentContainer
import com.getvideo.app.ui.components.uiHairline
import com.getvideo.app.ui.components.uiIsDark
import com.getvideo.app.ui.components.uiMuted
import com.getvideo.app.ui.components.uiSuccess
import com.getvideo.app.ui.components.uiSuccessContainer
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
    var showAppearance by remember { mutableStateOf(false) }
    val background by com.getvideo.app.data.repo.BackgroundStore.state.collectAsState()
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
        // 系统返回键与左上角箭头走同一条路：回到「我的」，而不是直接退出应用。
        BackHandler { showAccount = false }
        SubPageScaffold("账号", onBack = { showAccount = false }) { AccountScreen() }
        return
    }
    if (showLogin) {
        // 系统返回键与左上角箭头走同一条路：回到「我的」，而不是直接退出应用。
        BackHandler { showLogin = false }
        SubPageScaffold("平台授权", onBack = { showLogin = false }) { LoginScreen() }
        return
    }
    if (showAgent) {
        // 系统返回键与左上角箭头走同一条路：回到「我的」，而不是直接退出应用。
        BackHandler { showAgent = false }
        SubPageScaffold("视频助手", onBack = { showAgent = false }) { AgentScreen() }
        return
    }
    if (showAppearance) {
        // 系统返回键与左上角箭头走同一条路：回到「我的」，而不是直接退出应用。
        BackHandler { showAppearance = false }
        SubPageScaffold("背景图片", onBack = { showAppearance = false }) { AppearanceScreen() }
        return
    }
    PageColumn(Modifier.verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(14.dp))
        Text("我的", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "账号、存储与诊断",
            style = MaterialTheme.typography.bodySmall,
            color = uiMuted(),
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        ProfileHeader(account?.maskedPhone, onClick = { showAccount = true })
        Spacer(Modifier.height(4.dp))

        SectionTitle("账号与同步")
        SectionCard {
            SettingRow(Icons.Rounded.Settings, "服务器设置", onClick = { dialog = "server" })
            RowDivider()
            SettingRow(Icons.Rounded.VerifiedUser, "平台授权", subtitle = "B站 / 抖音提取凭证", onClick = { showLogin = true })
            RowDivider()
            SettingRow(Icons.Rounded.GraphicEq, "视频助手", onClick = { showAgent = true })
        }

        SectionTitle("外观")
        SectionCard {
            SettingRow(
                Icons.Rounded.Image,
                "背景图片",
                subtitle = if (background.enabled) "已启用" else "用喜欢的图片当页面背景",
                value = if (background.enabled) "已开启" else "默认",
                onClick = { showAppearance = true },
            )
        }

        SectionTitle("存储")
        SectionCard {
            SettingRow(
                Icons.Rounded.Storage,
                "存储空间",
                value = "${bytes / 1024 / 1024} MB",
                subtitle = "${tracks.size} 首视频保存在本机",
                onClick = { dialog = "storage" },
            )
        }

        SectionTitle("诊断与反馈")
        SectionCard {
            SettingRow(Icons.Rounded.Description, "导出诊断日志", onClick = { diagnosticExport.launch("GetVideo-diagnostics.txt") })
            RowDivider()
            SettingRow(Icons.Rounded.ContentCopy, "复制诊断日志", onClick = { scope.launch {
                runCatching {
                    val text = DiagnosticsStore.exportFailures().ifBlank { "暂无失败诊断日志" }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("GetVideo 诊断日志", text))
                }.onSuccess { Toast.makeText(context, "已复制诊断日志", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, "复制失败，请重试", Toast.LENGTH_SHORT).show() }
            } })
            RowDivider()
            SettingRow(Icons.Rounded.Feedback, "意见反馈", onClick = { dialog = "feedback" })
        }

        SectionTitle("关于")
        SectionCard {
            SettingRow(Icons.Rounded.BugReport, "版本 $version", subtitle = "分享链接提取视频", showChevron = true, onClick = { dialog = "about" })
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "GetVideo · 本机处理优先",
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
            color = uiMuted().copy(alpha = 0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
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
private fun ProfileHeader(maskedPhone: String?, onClick: () -> Unit) {
    HeroCard(Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Person, null, Modifier.size(28.dp), tint = Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    maskedPhone ?: "未登录",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                if (maskedPhone == null) {
                    Text("登录后可跨设备同步与云端提取", style = MaterialTheme.typography.bodySmall, color = uiMuted())
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip("已登录")
                        Spacer(Modifier.width(8.dp))
                        Text("账号管理", style = MaterialTheme.typography.bodySmall, color = uiMuted())
                    }
                }
            }
            Icon(
                Icons.Rounded.ChevronRight,
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun InfoDialog(title: String, text: String, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Text(text) }, confirmButton = { AreaButton(onClick = close) { Text("关闭") } })
}
