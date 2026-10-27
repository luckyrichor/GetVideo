package com.getvideo.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getvideo.app.player.PlayerHolder
import com.getvideo.app.ui.components.uiCardColor
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
    val colors = MaterialTheme.colorScheme
    MusicSheet(
        title = "播放方式",
        onDismiss = onClose,
        subtitle = "选择列表播完之后怎么继续",
        confirmText = "完成",
        onConfirm = onClose,
    ) {
        SheetGroup {
            listOf(PlayerHolder.Mode.SINGLE, PlayerHolder.Mode.SHUFFLE, PlayerHolder.Mode.ORDERED, PlayerHolder.Mode.LOOP)
                .forEachIndexed { index, mode ->
                    val selected = mode == selectedMode
                    if (index > 0) SheetDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) colors.primary.copy(alpha = 0.10f) else Color.Transparent)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = {
                                    scope.launch {
                                        runCatching { PlayerHolder.setMode(context, mode) }
                                            .onSuccess { onClose() }
                                            .onFailure { error = "切换失败，请重试" }
                                    }
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp))
                                .background(if (selected) colors.primary else uiCardColor()),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                playbackModeIcon(mode), null, Modifier.size(19.dp),
                                tint = if (selected) colors.onPrimary else colors.primary,
                            )
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                mode.label, fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selected) colors.primary else colors.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                mode.description, fontSize = 11.sp, lineHeight = 16.sp,
                                color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                            )
                        }
                        RadioButton(selected = selected, onClick = null)
                    }
                }
        }
        error?.let { Text(it, color = colors.error, fontSize = 12.sp) }
    }
}
