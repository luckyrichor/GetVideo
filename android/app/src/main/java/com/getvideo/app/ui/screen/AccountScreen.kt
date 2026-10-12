package com.getvideo.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.getvideo.app.data.remote.AccountCredentials
import com.getvideo.app.data.repo.AccountStore
import com.getvideo.app.data.repo.ApiProvider
import com.getvideo.app.data.repo.Settings
import kotlinx.coroutines.launch

@Composable
fun AccountScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by AccountStore.current.collectAsState()
    var server by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { server = Settings(context).serverUrlNow() }
    val account = saved?.takeIf { it.serverUrl == server }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GetVideo 账号", style = MaterialTheme.typography.titleLarge)
        Text("登录后可使用服务器提取、云端同步和视频助手。首次必须手机号注册；登录后可绑定抖音和 B站，之后通过已绑定的平台登录同一个手机号账号。", style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it) }
        if (account != null) {
            Text("已登录：${account.maskedPhone}")
            Button(onClick = {
                busy = true
                scope.launch {
                    runCatching { ApiProvider.api(server, account).accountMe() }
                        .onSuccess { message = "账号登录正常" }
                        .onFailure { message = ApiProvider.humanMessage(it) }
                    busy = false
                }
            }, enabled = !busy) { Text("检查登录状态") }
            OutlinedButton(onClick = {
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
            }, enabled = !busy) { Text("退出登录") }
        } else {
            Text("暂支持中国大陆手机号。首次使用请注册，密码至少 8 位。此版本暂不验证手机号归属，请只注册自己的手机号。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(phone, { phone = it.take(20) }, label = { Text("手机号") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            OutlinedTextField(password, { password = it.take(128) }, label = { Text("密码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (register) OutlinedTextField(confirmation, { confirmation = it.take(128) }, label = { Text("确认密码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Button(onClick = {
                if (register && confirmation != password) { message = "两次密码不一致"; return@Button }
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
            }, enabled = !busy && phone.isNotBlank() && password.length >= 8) { Text(if (busy) "处理中…" else if (register) "注册并登录" else "登录") }
            TextButton(onClick = { register = !register; message = null }, enabled = !busy) { Text(if (register) "已有账号，去登录" else "首次使用，注册账号") }
        }
        HorizontalDivider()
        PlatformAccountPanel(server, account, saved)
    }
}
