package com.getvideo.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.getvideo.app.data.repo.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

@Composable
internal fun ServerSettingsDialog(close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(Settings.DEFAULT_SERVER_URL) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) { address = Settings(context).serverUrlNow() }
    fun check(save: Boolean) {
        val requestedAddress = address.trim().trimEnd('/') + "/"
        busy = true; message = null
        job = scope.launch {
            try {
                require(address.isNotBlank()) { "尚未配置服务器，请填写独立的 HTTPS 服务器地址" }
                AccountStore.requireSecureServer(requestedAddress)
                val result = withTimeout(10_000) {
                    probeServerHealth(requestedAddress, ApiProvider.okHttp().newBuilder().followRedirects(false).build())
                    val result = if (AccountStore.forServer(requestedAddress) == null) "服务器连接正常，请登录 GetVideo 账号" else {
                        ApiProvider.api(requestedAddress).accountMe()
                        "服务器连接及账号登录正常"
                    }
                    result
                }
                if (save) Settings(context).setServerUrl(requestedAddress)
                message = result
            } catch (_: TimeoutCancellationException) {
                message = "连接检查超时（10 秒），请检查服务器地址、网络和服务状态"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = ApiProvider.humanMessage(e)
            } finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { job?.cancel(); close() }, title = { Text("服务器设置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(address, { address = it }, label = { Text("服务器地址") }, enabled = !busy, singleLine = true)
            Text("TX GetVideo 服务用于账号、平台授权和云同步；与 GetAudio 独立。手机直接提取视频仍可离线于服务器使用。", style = MaterialTheme.typography.bodySmall)
            AreaButton(onClick = { address = Settings.DEFAULT_SERVER_URL; message = null }, enabled = !busy) { Text("恢复 TX GetVideo 地址") }
            AreaButton(onClick = { scope.launch { Settings(context).setServerUrl(""); address = ""; message = "已停用服务器，手机直提取仍可使用" } }, enabled = !busy) { Text("仅使用本机（停用服务器）") }
            AreaButton(onClick = { check(false) }, enabled = !busy) { Text(if (busy) "检查中（最多 10 秒）…" else "测试连接") }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { AreaButton(onClick = { check(true) }, enabled = !busy) { Text("检查并保存") } },
        dismissButton = { AreaButton(onClick = { job?.cancel(); close() }) { Text(if (busy) "取消并关闭" else "关闭") } })
}
