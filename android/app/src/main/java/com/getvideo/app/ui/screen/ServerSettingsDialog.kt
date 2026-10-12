package com.getvideo.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.getvideo.app.data.repo.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

@Composable
internal fun ServerSettingsDialog(close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(Settings.DEFAULT_SERVER_URL) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { address = Settings(context).serverUrlNow() }
    fun check(save: Boolean) {
        busy = true
        scope.launch {
            try {
                message = runCatching {
                    AccountStore.requireSecureServer(address)
                    val url = address.trim().trimEnd('/').toHttpUrl()
                    require(url.encodedPath == "/" && url.query == null && url.fragment == null) { "请输入服务器根地址" }
                    withContext(Dispatchers.IO) {
                        ApiProvider.okHttp().newBuilder().followRedirects(false).callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
                            .newCall(Request.Builder().url(url.newBuilder().addPathSegment("health").build()).build()).execute().use {
                                check(it.isSuccessful && JSONObject(it.body?.string().orEmpty()).optBoolean("ok")) { "服务器连接检查失败" }
                            }
                    }
                    if (save) Settings(context).setServerUrl(address)
                    if (AccountStore.forServer(address.trim().trimEnd('/') + "/") == null) "服务器连接正常，请登录 GetVideo 账号" else {
                        ApiProvider.api(address.trim().trimEnd('/') + "/").accountMe()
                        "服务器连接及账号登录正常"
                    }
                }.getOrElse { ApiProvider.humanMessage(it) }
            } finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("服务器设置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(address, { address = it }, label = { Text("服务器地址") }, enabled = !busy, singleLine = true)
            Text("服务器提取和同步使用你的 GetVideo 账号，无需共享服务器密码。", style = MaterialTheme.typography.bodySmall)
            AreaButton(onClick = { address = Settings.DEFAULT_SERVER_URL }, enabled = !busy) { Text("恢复默认服务器") }
            AreaButton(onClick = { check(false) }, enabled = !busy) { Text("测试连接") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { AreaButton(onClick = { check(true) }, enabled = !busy) { Text("检查并保存") } },
        dismissButton = { AreaButton(onClick = close, enabled = !busy) { Text("关闭") } })
}
