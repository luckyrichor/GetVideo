package com.getvideo.app.ui.screen

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
        Column(Modifier.fillMaxSize()) {
            Text("请先登录 GetVideo 账号，再授权抖音或 B站。", Modifier.padding(16.dp))
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("平台授权 · ${owner.maskedPhone}", style = MaterialTheme.typography.titleMedium)
        Text(
            "抖音和 B站授权均属于当前 GetVideo 账号，可同时保留。服务器按需使用对应平台授权；手机直提取不读取其他 App 的登录状态。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ticket?.let { value ->
            OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("此设备有旧平台授权，可迁移到 ${owner.maskedPhone}。已有授权不会被覆盖。")
                AreaButton(onClick = { migrationScope.launch {
                    runCatching { ApiProvider.api(owner.serverUrl, owner).claimMigration(com.getvideo.app.data.remote.MigrationClaim(value)) }
                        .onSuccess { migration.clear(); ticket = null; vm.refresh(); migrationMessage = "旧授权已迁移" }
                        .onFailure { migrationMessage = ApiProvider.humanMessage(it) }
                } }) { Text("确认迁移到当前账号") }
                AreaButton(onClick = { migration.clear(); ticket = null }) { Text("暂不迁移，重新授权") }
            } }
        }
        migrationMessage?.let { Text(it) }
        if (vm.credentials.isNotEmpty()) {
            OutlinedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已有凭证", style = MaterialTheme.typography.titleSmall)
                    vm.credentials.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${platformName(c.platform)} · ${if (c.status == "active") "有效" else "需重新登录"}",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            AreaButton(onClick = { vm.revoke(c.platform) }, tone = ActionTone.DESTRUCTIVE) { Text("解除授权") }
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.startQr("bilibili") }, enabled = !vm.busy) { Text("B站扫码授权") }
            OutlinedButton(onClick = { vm.startQr("douyin") }, enabled = !vm.busy) { Text("抖音授权") }
        }

        vm.qrImageUrl?.let { url ->
            OutlinedCard {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AsyncImage(model = url, imageLoader = loader, contentDescription = "登录二维码", modifier = Modifier.size(240.dp))
                    Text(stateLabel(vm.state), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        vm.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (vm.showImport) {
            OutlinedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("导入浏览器 Cookie", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "抖音的扫码接口被平台风控拦截。在电脑浏览器里登录抖音，" +
                            "按 F12 → Application → Cookies，复制 douyin.com 下的全部 cookie 贴到这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = vm.cookieInput,
                        onValueChange = { vm.cookieInput = it },
                        label = { Text("sessionid=xxx; ttwid=yyy") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = vm::submitCookies, enabled = vm.cookieInput.isNotBlank() && !vm.busy) {
                        Text("保存凭证")
                    }
                }
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
