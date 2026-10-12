package com.getvideo.app.ui.screen

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.getvideo.app.data.repo.Settings
import kotlinx.coroutines.launch

class SettingsViewModel(private val settings: Settings) : ViewModel() {

    var serverUrl by mutableStateOf("")
    var userId by mutableStateOf("")
        private set
    var saved by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            serverUrl = settings.serverUrlNow()
            userId = settings.userId()
        }
    }

    fun save() = viewModelScope.launch {
        settings.setServerUrl(serverUrl)
        serverUrl = settings.serverUrlNow()
        saved = true
    }

    fun ack() { saved = false }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(Settings(context)) as T
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(context))

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = vm.serverUrl,
            onValueChange = { vm.serverUrl = it; vm.ack() },
            label = { Text("可选服务器地址") },
            supportingText = { Text("手机直提取不需要服务器；也可随时手动选择服务器提取。10.0.2.2 仅供模拟器使用，真机不可用") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = vm::save) { Text(if (vm.saved) "已保存" else "保存") }

        HorizontalDivider()

        Text("本机用户 id", style = MaterialTheme.typography.titleSmall)
        Text(
            vm.userId,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "仅同步服务器功能使用；手机直接提取不需要此 ID。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val version = remember(context) {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
        }
        Text("版本 $version", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
