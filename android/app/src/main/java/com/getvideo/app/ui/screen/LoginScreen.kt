package com.getvideo.app.ui.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoveDown
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.ImageLoader
import com.getvideo.app.data.repo.AccountStore
import com.getvideo.app.data.remote.CredentialDto
import com.getvideo.app.data.repo.ApiProvider
import com.getvideo.app.data.repo.LoginRepository
import com.getvideo.app.ui.components.Footnote
import com.getvideo.app.ui.components.HeroCard
import com.getvideo.app.ui.components.InlineBanner
import com.getvideo.app.ui.components.PlatformColors
import com.getvideo.app.ui.components.RoundBadge
import com.getvideo.app.ui.components.SectionCard
import com.getvideo.app.ui.components.StatusChip
import com.getvideo.app.ui.components.uiAccentContainer
import com.getvideo.app.ui.components.uiMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException

class LoginViewModel(private val repo: LoginRepository) : ViewModel() {

    var qrImageUrl by mutableStateOf<String?>(null)
        private set
    var state by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var credentials by mutableStateOf<List<CredentialDto>>(emptyList())
        private set
    /** 扫码不可用时展示 cookie 导入表单 */
    var showImport by mutableStateOf(false)
    var cookieInput by mutableStateOf("")
    var importPlatform by mutableStateOf("douyin")

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        repo.credentials().onSuccess { credentials = it }
    }

    fun startQr(platform: String) = viewModelScope.launch {
        busy = true
        message = null
        qrImageUrl = null
        state = null
        showImport = false

        repo.start(platform)
            .onSuccess { resp ->
                qrImageUrl = repo.qrImageUrl(resp.qrImageUrl)
                message = "用 ${platformName(platform)} App 扫码"
                pollUntilDone(resp.sessionId, resp.expiresIn)
            }
            .onFailure { error ->
                message = ApiProvider.humanMessage(error)
                // 只有服务器明确返回 501 才提示导入 Cookie；连接失败不是平台登录限制。
                if (error is HttpException && error.code() == 501) {
                    showImport = true
                    importPlatform = platform
                }
            }
        busy = false
    }

    private suspend fun pollUntilDone(sessionId: String, expiresIn: Int) {
        val deadline = System.currentTimeMillis() + (expiresIn + 10) * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(2000)
            val resp = repo.poll(sessionId).getOrNull() ?: continue
            state = resp.state
            message = resp.message
            when (resp.state) {
                "confirmed" -> { qrImageUrl = null; refresh(); return }
                "expired", "failed" -> { qrImageUrl = null; return }
            }
        }
        message = "二维码已超时，请重新获取"
        qrImageUrl = null
    }

    fun submitCookies() = viewModelScope.launch {
        if (cookieInput.isBlank()) return@launch
        busy = true
        repo.importCookies(importPlatform, cookieInput)
            .onSuccess {
                message = "凭证已保存"
                cookieInput = ""
                showImport = false
                refresh()
            }
            .onFailure { message = ApiProvider.humanMessage(it) }
        busy = false
    }

    fun revoke(platform: String) = viewModelScope.launch {
        repo.revoke(platform).onSuccess { refresh() }
    }

    class Factory(private val context: Context, private val owner: AccountStore.Account) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LoginViewModel(LoginRepository(context, owner)) as T
    }
}

private fun platformName(p: String) = if (p == "bilibili") "哔哩哔哩" else "抖音"

@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val account by AccountStore.current.collectAsState()
    val server by remember(context) { com.getvideo.app.data.repo.Settings(context).serverUrl }.collectAsState(initial = "")
    val owner = account?.takeIf { it.serverUrl == server }
    if (owner == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("请先登录 GetVideo 账号，再授权抖音或 B站。", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            AccountScreen()
        }
        return
    }
    val migration = remember { com.getvideo.app.data.repo.CredentialMigrationStore(context) }
    var ticket by remember(owner) { mutableStateOf(runCatching { migration.ticket() }.getOrNull()) }
    var migrationMessage by remember { mutableStateOf<String?>(null) }
    val migrationScope = rememberCoroutineScope()
    val loader = remember(owner) { ImageLoader.Builder(context).okHttpClient { ApiProvider.okHttp(owner) }.build() }
    val vm: LoginViewModel = viewModel(key = "platform-${owner.userId}-${System.identityHashCode(owner)}-$server", factory = LoginViewModel.Factory(context, owner))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Link, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("当前账号", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(owner.maskedPhone, style = MaterialTheme.typography.bodyMedium, color = uiMuted())
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "抖音和 B站授权均属于当前 GetVideo 账号，可同时保留。服务器按需使用对应平台授权；手机直提取不读取其他 App 的登录状态。",
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp,
                color = uiMuted(),
            )
        }

        ticket?.let { value ->
            SectionCard(padding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.MoveDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("发现本机旧授权", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "此设备有旧平台授权，可迁移到 ${owner.maskedPhone}。已有授权不会被覆盖。",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp,
                    color = uiMuted(),
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = { migrationScope.launch {
                    runCatching { ApiProvider.api(owner.serverUrl, owner).claimMigration(com.getvideo.app.data.remote.MigrationClaim(value)) }
                        .onSuccess { migration.clear(); ticket = null; vm.refresh(); migrationMessage = "旧授权已迁移" }
                        .onFailure { migrationMessage = ApiProvider.humanMessage(it) }
                } }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) { Text("确认迁移到当前账号") }
                TextButton(onClick = { migration.clear(); ticket = null }, modifier = Modifier.fillMaxWidth()) { Text("暂不迁移，重新授权") }
            }
        }
        migrationMessage?.let { InlineBanner(it, tone = MaterialTheme.colorScheme.primary, icon = Icons.Rounded.Info) }

        if (vm.credentials.isNotEmpty()) {
            SectionCard(padding = PaddingValues(16.dp)) {
                Text("已有凭证", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("服务器提取时使用", style = MaterialTheme.typography.bodySmall, color = uiMuted())
                Spacer(Modifier.height(10.dp))
                vm.credentials.forEachIndexed { index, c ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(com.getvideo.app.ui.components.uiHairline()))
                    }
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundBadge(
                            size = 32.dp,
                            background2 = if (c.platform == "bilibili") PlatformColors.bilibili else PlatformColors.douyin,
                        ) {
                            Text(
                                if (c.platform == "bilibili") "B" else "抖",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            platformName(c.platform),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        StatusChip(
                            if (c.status == "active") "有效" else "需重新登录",
                            container = if (c.status == "active") com.getvideo.app.ui.components.uiSuccessContainer() else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            content = if (c.status == "active") com.getvideo.app.ui.components.uiSuccess() else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = { vm.revoke(c.platform) }) {
                            Text("解除授权", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { vm.startQr("bilibili") },
                enabled = !vm.busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(46.dp),
            ) { Text("B站扫码授权") }
            OutlinedButton(
                onClick = { vm.startQr("douyin") },
                enabled = !vm.busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(46.dp),
            ) { Text("抖音授权") }
        }

        vm.qrImageUrl?.let { url ->
            SectionCard(padding = PaddingValues(16.dp)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White)
                            .padding(12.dp),
                    ) {
                        AsyncImage(
                            model = url,
                            imageLoader = loader,
                            contentDescription = "登录二维码",
                            modifier = Modifier.size(220.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    StatusChip(
                        stateLabel(vm.state),
                        container = uiAccentContainer(),
                        content = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        vm.message?.let {
            InlineBanner(it, tone = MaterialTheme.colorScheme.primary, icon = Icons.Rounded.Info)
        }

        if (vm.showImport) {
            SectionCard(padding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("导入浏览器 Cookie", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "抖音的扫码接口被平台风控拦截。在电脑浏览器里登录抖音，" +
                        "按 F12 → Application → Cookies，复制 douyin.com 下的全部 cookie 贴到这里。",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp,
                    color = uiMuted(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = vm.cookieInput,
                    onValueChange = { vm.cookieInput = it },
                    label = { Text("sessionid=xxx; ttwid=yyy") },
                    minLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = vm::submitCookies,
                    enabled = vm.cookieInput.isNotBlank() && !vm.busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) { Text("保存凭证") }
            }
        }
    }
}

private fun stateLabel(state: String?) = when (state) {
    "waiting", null -> "等待扫码…"
    "scanned" -> "已扫码，请在手机上确认"
    "confirmed" -> "登录成功"
    "expired" -> "二维码已失效"
    else -> "登录失败"
}
