package com.getvideo.app.ui.screen

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.getvideo.app.data.remote.*
import com.getvideo.app.data.repo.AccountStore
import com.getvideo.app.data.repo.ApiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PlatformAccountPanel(server: String, account: AccountStore.Account?, expectedSaved: AccountStore.Account?) {
    key(server, account?.token) { PlatformAccountPanelContent(server, account, expectedSaved) }
}

@Composable
private fun PlatformAccountPanelContent(server: String, account: AccountStore.Account?, expectedSaved: AccountStore.Account?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(server, account) { if (account != null) ApiProvider.api(server, account) else ApiProvider.api(server) }
    var capabilities by remember { mutableStateOf<List<PlatformLoginCapability>>(emptyList()) }
    var bindings by remember { mutableStateOf<List<PlatformAccountBinding>>(emptyList()) }
    var ticket by remember { mutableStateOf<PlatformAuthTicket?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf<PlatformAuthStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var unbind by remember { mutableStateOf<PlatformAccountBinding?>(null) }
    LaunchedEffect(server, account) {
        if (server.isBlank()) return@LaunchedEffect
        runCatching {
            capabilities = api.platformLoginCapabilities()
            if (account != null) bindings = api.platformAccountBindings()
        }.onFailure { message = ApiProvider.humanMessage(it) }
    }
    LaunchedEffect(ticket) {
        val current = ticket ?: return@LaunchedEffect
        val proof = PlatformAuthProof(current.attemptId, current.pollToken)
        qr = null; status = null
        try {
            if (current.method == "qr") {
                qr = withContext(Dispatchers.IO) { api.platformAuthImage(proof).use { BitmapFactory.decodeStream(it.byteStream()) } }
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + current.expiresIn * 1000L
            while (ticket == current && android.os.SystemClock.elapsedRealtime() < deadline) {
                val result = api.platformAuthStatus(proof)
                if (ticket != current) break
                status = result
                if (result.status in listOf("verified", "failed", "consumed")) break
                delay(2500)
            }
            if (ticket == current && status?.status in listOf("waiting", "checking")) message = "授权超时，请重新发起"
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { if (ticket == current) message = ApiProvider.humanMessage(e) }
    }
    Text(if (account == null) "已绑定平台账号登录" else "关联平台账号", style = MaterialTheme.typography.titleMedium)
    Text(if (account == null) "新用户必须先使用手机号＋密码注册，并登录后绑定平台。这里仅用于登录已经绑定的账号。" else "平台账号关联到当前手机号；不会创建另一个 GetVideo 账号。", style = MaterialTheme.typography.bodySmall)
    message?.let { Text(it) }
    capabilities.forEach { provider ->
        val linked = bindings.firstOrNull { it.platform == provider.platform }
        val name = if (provider.platform == "bilibili") "B站" else "抖音"
        Text(provider.note, style = MaterialTheme.typography.bodySmall)
        if (linked != null) {
            Text("$name 已绑定：${linked.nickname}")
            TextButton(onClick = { unbind = linked }, enabled = !busy && ticket == null) { Text("解除${name}登录绑定") }
        } else OutlinedButton(onClick = {
            busy = true; message = null
            scope.launch {
                runCatching {
                    AccountStore.requireSecureServer(server)
                    check(AccountStore.current.value == expectedSaved) { "账号已切换，请重新打开页面" }
                    api.platformAuthStart(PlatformAuthStart(provider.platform, if (account == null) "login" else "bind"))
                }.onSuccess { if (AccountStore.current.value == expectedSaved) ticket = it }
                    .onFailure { message = ApiProvider.humanMessage(it) }
                busy = false
            }
        }, enabled = provider.available && !busy && ticket == null) { Text(if (account == null) "$name 授权登录" else "绑定$name") }
    }
    ticket?.let { current ->
        val proof = PlatformAuthProof(current.attemptId, current.pollToken)
        HorizontalDivider()
        Text(if (current.platform == "bilibili") "请用 B站 App 扫码并确认" else "在浏览器中完成抖音授权，再返回此页")
        qr?.let { Image(it.asImageBitmap(), "B站账号授权二维码", Modifier.size(230.dp)) }
        current.authorizationUrl?.let { url ->
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { message = "无法打开浏览器，请检查手机浏览器设置" }
            }, enabled = !busy) { Text("打开抖音授权") }
        }
        Text(status?.message ?: "等待授权…")
        if (status?.status == "verified") {
            Text("授权账号：${status?.nickname}")
            Button(onClick = {
                busy = true
                scope.launch {
                    runCatching {
                        check(AccountStore.current.value == expectedSaved) { "账号已切换，请重新授权" }
                        val result = api.platformAuthComplete(proof)
                        if (account == null) {
                            val session = result.session ?: error("未取得账号会话")
                            AccountStore.save(session, server, expectedSaved)
                        } else {
                            check(result.session == null) { "绑定结果异常" }
                            bindings = api.platformAccountBindings()
                        }
                    }.onSuccess { ticket = null; status = null; message = if (account == null) "登录成功" else "已关联到当前手机号" }
                        .onFailure { message = ApiProvider.humanMessage(it) }
                    busy = false
                }
            }, enabled = !busy) { Text(if (account == null) "登录已绑定账号" else "确认绑定到当前手机号") }
        }
        TextButton(onClick = {
            scope.launch { runCatching { val result = api.platformAuthStatus(proof); if (ticket == current) status = result }.onFailure { message = ApiProvider.humanMessage(it) } }
        }, enabled = !busy) { Text("检查授权") }
        TextButton(onClick = {
            ticket = null; status = null; qr = null
            scope.launch { runCatching { api.platformAuthCancel(proof) } }
        }, enabled = !busy) { Text("取消／重新发起") }
    }
    unbind?.let { linked -> AlertDialog(onDismissRequest = { if (!busy) { unbind = null } },
        title = { Text("解除平台登录绑定") },
        text = { Text("确认解除此平台的登录绑定？解除后无法用此平台登录；手机号账号及视频保留。该平台账号可重新绑定到其他手机号。提取用的平台 Cookie 仍在原授权页独立管理。") },
        confirmButton = { TextButton(onClick = {
            busy = true
            scope.launch {
                runCatching { check(AccountStore.current.value == expectedSaved) { "账号已切换，请重新打开页面" }; api.platformAccountUnbind(linked.platform); bindings = api.platformAccountBindings() }
                    .onSuccess { unbind = null; message = "已解除登录绑定" }
                    .onFailure { message = ApiProvider.humanMessage(it) }
                busy = false
            }
        }, enabled = !busy) { Text("确认解除") } },
        dismissButton = { TextButton(onClick = { unbind = null }, enabled = !busy) { Text("取消") } }) }
}
