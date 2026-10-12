package com.getvideo.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.getvideo.app.player.PlayerHolder
import kotlinx.coroutines.launch

internal fun playbackModeIcon(mode: PlayerHolder.Mode): ImageVector = when (mode) {
    PlayerHolder.Mode.SINGLE -> Icons.Default.RepeatOne
    PlayerHolder.Mode.LOOP -> Icons.Default.Repeat
    PlayerHolder.Mode.ORDERED -> Icons.AutoMirrored.Filled.PlaylistPlay
    PlayerHolder.Mode.SHUFFLE -> Icons.Default.Shuffle
}

@Composable
fun PlaybackModeDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedMode by PlayerHolder.mode.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("播放方式") },
        text = {
            Column {
                listOf(PlayerHolder.Mode.SINGLE, PlayerHolder.Mode.SHUFFLE, PlayerHolder.Mode.ORDERED, PlayerHolder.Mode.LOOP).forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = mode == selectedMode,
                            role = Role.RadioButton,
                            onClick = { scope.launch {
                                runCatching { PlayerHolder.setMode(context, mode) }
                                    .onSuccess { onClose() }
                                    .onFailure { error = "切换失败，请重试" }
                            } },
                        ).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(playbackModeIcon(mode), null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(mode.label, style = MaterialTheme.typography.titleSmall)
                            Text(mode.description, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RadioButton(selected = mode == selectedMode, onClick = null)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}
