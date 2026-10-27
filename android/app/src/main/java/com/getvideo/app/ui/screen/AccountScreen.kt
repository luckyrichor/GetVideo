package com.getvideo.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Person
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getvideo.app.data.remote.AccountCredentials
import com.getvideo.app.data.repo.AccountStore
import com.getvideo.app.data.repo.ApiProvider
import com.getvideo.app.data.repo.Settings
import com.getvideo.app.ui.components.Footnote
import com.getvideo.app.ui.components.HeroCard
import com.getvideo.app.ui.components.InlineBanner
import com.getvideo.app.ui.components.SectionCard
import com.getvideo.app.ui.components.StatusChip
import com.getvideo.app.ui.components.uiAccentContainer
import com.getvideo.app.ui.components.uiMuted
import kotlinx.coroutines.launch

@Composable
fun AccountScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by AccountStore.current.collectAsState()
    val server by remember(context) { Settings(context).serverUrl }.collectAsState(initial = "")
    val account = saved?.takeIf { it.serverUrl == server }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showServerSettings by remember { mutableStateOf(false) }
    if (showServerSettings) ServerSettingsDialog { showServerSettings = false }
    if (server.isBlank() || runCatching { AccountStore.requireSecureServer(server) }.isFailure) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(padding = PaddingValues(16.dp)) {
                Text("账号登录需要服务器地址", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "手机直接提取视频无需服务器；账号登录和平台授权需先配置独立的 HTTPS 服务器地址。",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp,
                    color = uiMuted(),
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = { showServerSettings = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(46.dp)) {
                    Text("配置服务器")
                }
            }
            PlatformAccountPanel(server, null, saved)
        }
        return
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (account != null) {
            SignedInCard(account.maskedPhone, busy, onCheck = {
                busy = true
                scope.launch {
                    runCatching { ApiProvider.api(server, account).accountMe() }
                        .onSuccess { message = "账号登录正常" }
                        .onFailure { message = ApiProvider.humanMessage(it) }
                    busy = false
                }
            }, onLogout = {
                busy = true
                scope.launch {
                    runCatching {
                        ApiProvider.api(server, account).accountLogout()
                        AccountStore.clear(account)
                    }
                        .onSuccess { message = "已退出登录" }
                        .onFailure { message = "退出未完成：${ApiProvider.humanMessage(it)}，请联网后重试" }
                    busy = false
                }
            })
        } else {
            SignInCard(
                phone = phone,
                password = password,
                confirmation = confirmation,
                register = register,
                busy = busy,
                onPhone = { phone = it.take(20) },
                onPassword = { password = it.take(128) },
                onConfirmation = { confirmation = it.take(128) },
                onSubmit = submit@{
                    if (register && confirmation != password) { message = "两次密码不一致"; return@submit }
                    val expectedSaved = saved
                    val requestServer = server
                    val requestBody = AccountCredentials(phone.trim(), password)
                    val requestRegister = register
                    busy = true
                    message = null
                    scope.launch {
                        runCatching {
                            AccountStore.requireSecureServer(requestServer)
                            val api = ApiProvider.api(requestServer)
                            val session = if (requestRegister) api.accountRegister(requestBody) else api.accountLogin(requestBody)
                            AccountStore.save(session, requestServer, expectedSaved)
                        }.onSuccess { password = ""; confirmation = ""; message = "登录成功" }
                            .onFailure { message = ApiProvider.humanMessage(it) }
                        busy = false
                    }
                },
                onToggleRegister = { register = !register; message = null },
            )
        }
        message?.let {
            InlineBanner(
                it,
                tone = if (it.contains("成功") || it.contains("正常")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                icon = if (it.contains("成功") || it.contains("正常")) Icons.Rounded.VerifiedUser else Icons.Rounded.ErrorOutline,
            )
        }
        PlatformAccountPanel(server, account, saved)
        Footnote("首次必须手机号注册；登录后可绑定抖音和 B站，之后通过已绑定的平台登录同一个手机号账号。")
    }
}

@Composable
private fun SignedInCard(maskedPhone: String, busy: Boolean, onCheck: () -> Unit, onLogout: () -> Unit) {
    HeroCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Person, null, Modifier.size(26.dp), tint = Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(maskedPhone, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                StatusChip("已登录")
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onCheck,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) { Text(if (busy) "检查中…" else "检查登录状态") }
            OutlinedButton(
                onClick = onLogout,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) { Text("退出登录") }
        }
    }
}

@Composable
private fun SignInCard(
    phone: String,
    password: String,
    confirmation: String,
    register: Boolean,
    busy: Boolean,
    onPhone: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConfirmation: (String) -> Unit,
    onSubmit: () -> Unit,
    onToggleRegister: () -> Unit,
) {
    SectionCard(padding = PaddingValues(16.dp)) {
        Text(
            if (register) "注册 GetVideo 账号" else "登录 GetVideo 账号",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "暂支持中国大陆手机号。首次使用请注册，密码至少 8 位。此版本暂不验证手机号归属，请只注册自己的手机号。",
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 18.sp,
            color = uiMuted(),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            phone,
            onPhone,
            label = { Text("手机号") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.PhoneIphone, null, Modifier.size(19.dp), tint = uiMuted()) },
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            password,
            onPassword,
            label = { Text("密码") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Lock, null, Modifier.size(19.dp), tint = uiMuted()) },
            shape = RoundedCornerShape(12.dp),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (register) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                confirmation,
                onConfirmation,
                label = { Text("确认密码") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Lock, null, Modifier.size(19.dp), tint = uiMuted()) },
                shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onSubmit,
            enabled = !busy && phone.isNotBlank() && password.length >= 8,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text(if (busy) "处理中…" else if (register) "注册并登录" else "登录") }
        TextButton(onClick = onToggleRegister, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (register) "已有账号，去登录" else "首次使用，注册账号")
        }
    }
}
