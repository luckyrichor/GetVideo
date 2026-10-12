package com.getvideo.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
internal fun AccountLoginDialog(close: () -> Unit) {
    Dialog(onDismissRequest = close) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text("登录后使用服务器功能", style = MaterialTheme.typography.titleMedium)
                AccountScreen()
                AreaButton(onClick = close) { Text("返回") }
            }
        }
    }
}
