package com.getvideo.app.ui.screen

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getvideo.app.data.remote.*
import com.getvideo.app.data.repo.AccountStore
import com.getvideo.app.data.repo.ApiProvider
import com.getvideo.app.ui.components.Footnote
import com.getvideo.app.ui.components.InlineBanner
import com.getvideo.app.ui.components.PlatformColors
import com.getvideo.app.ui.components.RoundBadge
import com.getvideo.app.ui.components.SectionCard
import com.getvideo.app.ui.components.SectionTitle
import com.getvideo.app.ui.components.StatusChip
import com.getvideo.app.ui.components.uiHairline
import com.getvideo.app.ui.components.uiAccentContainer
import com.getvideo.app.ui.components.uiMuted
import com.getvideo.app.ui.components.uiSuccess
import com.getvideo.app.ui.components.uiSuccessContainer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PlatformAccountPanel(server: String, account: AccountStore.Account?, expectedSaved: AccountStore.Account?) {
    val problem = when {
        server.isBlank() -> "正在读取服务器设置，请稍候；如未配置，请到服务器设置中填写 HTTPS 地址。"
        server.toHttpUrlOrNull() == null -> "服务器地址无效，请在服务器设置中修改。"
        else -> runCatching { AccountStore.requireSecureServer(server) }.exceptionOrNull()?.message
    }
    if (problem != null) {
        Footnote(problem)
        return
    }
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

    SectionTitle(
        if (account == null) "已绑定平台账号登录" else "关联平台账号",
    )
    Footnote(
        if (account == null) "新用户必须先使用手机号＋密码注册，并登录后绑定平台。这里仅用于登录已经绑定的账号。"
        else "平台账号关联到当前手机号；不会创建另一个 GetAudio 账号。",
    )
    message?.let {
        InlineBanner(it, tone = MaterialTheme.colorScheme.error, icon = Icons.Rounded.Cancel)
        Spacer(Modifier.height(10.dp))
    }
    capabilities.forEach { provider ->
        val linked = bindings.firstOrNull { it.platform == provider.platform }
        val name = if (provider.platform == "bilibili") "B站" else "抖音"
        PlatformRow(
            platform = provider.platform,
            name = name,
            note = provider.note,
            binding = linked,
            authorizedLabel = if (account == null) "$name 授权登录" else "绑定$name",
            enabled = provider.available && !busy && ticket == null,
            onBind = {
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
            },
            onUnbindRequest = { unbind = linked },
        )
        Spacer(Modifier.height(10.dp))
    }
    ticket?.let { current ->
        val proof = PlatformAuthProof(current.attemptId, current.pollToken)
        SectionCard(padding = PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.QrCode2, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (current.platform == "bilibili") "请用 B站 App 扫码并确认" else "在浏览器中完成抖音授权，再返回此页",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(14.dp))
            qr?.let {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        it.asImageBitmap(),
                        "B站账号授权二维码",
                        Modifier.size(220.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            current.authorizationUrl?.let { url ->
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        .onFailure { message = "无法打开浏览器，请检查手机浏览器设置" }
                }, enabled = !busy, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("打开抖音授权")
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                StatusChip(
                    status?.message ?: "等待授权…",
                    container = if (status?.status == "verified") uiSuccessContainer() else uiAccentContainer(),
                    content = if (status?.status == "verified") uiSuccess() else MaterialTheme.colorScheme.primary,
                )
            }
            if (status?.status == "verified") {
                Spacer(Modifier.height(12.dp))
                Text(
                    "授权账号：${status?.nickname}",
                    Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(12.dp))
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
                }, enabled = !busy, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(46.dp)) {
                    Text(if (account == null) "登录已绑定账号" else "确认绑定到当前手机号")
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch { runCatching { val result = api.platformAuthStatus(proof); if (ticket == current) status = result }.onFailure { message = ApiProvider.humanMessage(it) } }
                }, enabled = !busy, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("检查授权") }
                TextButton(onClick = {
                    ticket = null; status = null; qr = null
                    scope.launch { runCatching { api.platformAuthCancel(proof) } }
                }, enabled = !busy, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("取消／重新发起") }
            }
        }
    }
    var unbindPassword by remember { mutableStateOf("") }
    unbind?.let { linked -> AlertDialog(onDismissRequest = { if (!busy) { unbind = null; unbindPassword = "" } },
        icon = { Icon(Icons.Rounded.LinkOff, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.error) },
        title = { Text("解除平台登录绑定") },
        text = {
            Column {
                Text("确认解除此平台的登录绑定？解除后无法用此平台登录；手机号账号及视频保留。该平台账号可重新绑定到其他手机号。提取用的平台 Cookie 仍在原授权页独立管理。")
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = unbindPassword,
                    onValueChange = { unbindPassword = it },
                    label = { Text("当前账号密码") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "输入手机号账号的登录密码，确认是本人在操作。",
                    style = MaterialTheme.typography.bodySmall,
                    color = uiMuted(),
                )
            }
        },
        confirmButton = { TextButton(onClick = {
            busy = true
            scope.launch {
                runCatching {
                    check(unbindPassword.isNotBlank()) { "请输入当前账号密码" }
                    check(AccountStore.current.value == expectedSaved) { "账号已切换，请重新打开页面" }
                    api.platformAccountUnbind(linked.platform, mapOf("password" to unbindPassword))
                    bindings = api.platformAccountBindings()
                }
                    .onSuccess { unbind = null; unbindPassword = ""; message = "已解除登录绑定" }
                    .onFailure { message = ApiProvider.humanMessage(it) }
                busy = false
            }
        }, enabled = !busy && unbindPassword.isNotBlank()) { Text("确认解除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { unbind = null; unbindPassword = "" }, enabled = !busy) { Text("取消") } }) }
}

/** 平台行：徽标 + 名称 + 绑定昵称 + 状态 + 操作。 */
@Composable
private fun PlatformRow(
    platform: String,
    name: String,
    note: String,
    binding: PlatformAccountBinding?,
    authorizedLabel: String,
    enabled: Boolean,
    onBind: () -> Unit,
    onUnbindRequest: () -> Unit,
) {
    val accent = if (platform == "bilibili") PlatformColors.bilibili else PlatformColors.douyin
    SectionCard(padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundBadge(size = 44.dp, background2 = accent) {
                if (platform == "bilibili") {
                    Text("B", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                } else {
                    Icon(Icons.Rounded.MusicNote, null, Modifier.size(22.dp), tint = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    if (binding != null) {
                        StatusChip("已绑定")
                    } else {
                        StatusChip(
                            "未绑定",
                            container = uiAccentContainer(),
                            content = uiMuted(),
                            dot = false,
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    binding?.nickname ?: note,
                    style = MaterialTheme.typography.bodySmall,
                    color = uiMuted(),
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (binding != null) {
                TextButton(onClick = onUnbindRequest, enabled = enabled) {
                    Text("解除绑定", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                }
            } else {
                FilledTonalButton(onClick = onBind, enabled = enabled, shape = RoundedCornerShape(10.dp)) {
                    Text(authorizedLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
