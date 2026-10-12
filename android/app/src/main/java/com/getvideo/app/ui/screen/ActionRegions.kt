package com.getvideo.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

internal enum class ActionTone { NORMAL, SYNC, CONFIRM, TIMER, DESTRUCTIVE }

/** Visible boundaries for actions, shared by menus and management pages. */
@Composable
internal fun AreaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: ActionTone = ActionTone.NORMAL,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    val dark = palette.surface.luminance() < 0.5f
    val (background, foreground) = when (tone) {
        ActionTone.NORMAL -> palette.secondaryContainer.copy(alpha = 0.55f) to palette.onSecondaryContainer
        ActionTone.CONFIRM -> palette.primaryContainer to palette.onPrimaryContainer
        ActionTone.DESTRUCTIVE -> palette.errorContainer.copy(alpha = 0.65f) to palette.onErrorContainer
        ActionTone.SYNC -> (if (dark) Color(0xFF18364F) else Color(0xFFE4F0FB)) to
            (if (dark) Color(0xFFB5D8FF) else Color(0xFF205A91))
        ActionTone.TIMER -> (if (dark) Color(0xFF49381B) else Color(0xFFFFF0D6)) to
            (if (dark) Color(0xFFF9D68B) else Color(0xFF79551C))
    }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, foreground.copy(alpha = if (enabled) 0.35f else 0.12f)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = background, contentColor = foreground),
        content = content,
    )
}

/** Full-width action with a left-aligned name and optional right-hand status. */
@Composable
internal fun AreaAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: ActionTone = ActionTone.NORMAL,
    content: @Composable RowScope.() -> Unit,
) {
    AreaButton(onClick, modifier.fillMaxWidth(), enabled, tone) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
internal fun Modifier.regionFrame(selected: Boolean = false): Modifier {
    val shape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme
    return clip(shape)
        .background(if (selected) colors.primaryContainer.copy(alpha = 0.4f) else colors.surface)
        .border(1.dp, if (selected) colors.primary else colors.outline.copy(alpha = 0.7f), shape)
}
