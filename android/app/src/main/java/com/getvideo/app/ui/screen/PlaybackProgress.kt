package com.getvideo.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import com.getvideo.app.player.PlayerHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackProgress(trackId: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var position by remember(trackId) { mutableLongStateOf(0) }
    var duration by remember(trackId) { mutableLongStateOf(0) }
    var dragging by remember(trackId) { mutableStateOf(false) }
    var fraction by remember(trackId) { mutableFloatStateOf(0f) }
    var hint by remember(trackId) { mutableStateOf<String?>(null) }
    var hintVersion by remember(trackId) { mutableIntStateOf(0) }
    LaunchedEffect(trackId) {
        val player = PlayerHolder.get(context)
        while (true) {
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
            if (!dragging) { position = player.currentPosition.coerceAtLeast(0); fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f }
            delay(200)
        }
    }
    LaunchedEffect(hintVersion) { if (hint != null) { delay(1200); hint = null } }
    Column(Modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
        Slider(value = fraction, onValueChange = { dragging = true; fraction = it }, enabled = duration > 0,
            onValueChangeFinished = {
                val target = (fraction * duration).toLong().coerceIn(0, duration)
                hint = playbackTime(target); hintVersion++
                scope.launch { try { PlayerHolder.get(context).seekTo(target); position = target } finally { dragging = false } }
            }, modifier = Modifier.fillMaxWidth().height(28.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(hint ?: playbackTime(position), style = MaterialTheme.typography.labelSmall)
            Text(playbackTime(duration), style = MaterialTheme.typography.labelSmall)
        }
    }
}
private fun playbackTime(ms: Long) = "%02d:%02d".format(java.util.Locale.ROOT, ms / 60000, ms / 1000 % 60)
