package com.getvideo.app.ui.screen

import android.os.SystemClock
import androidx.compose.runtime.*
import com.getvideo.app.player.PlayerHolder
import kotlinx.coroutines.delay

@Composable
internal fun timerStatus(): String? {
    val deadline by PlayerHolder.timerDeadline.collectAsState()
    val waiting by PlayerHolder.waitingForTrackEnd.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadline) {
        now = SystemClock.elapsedRealtime()
        while (deadline != null) { delay(1000); now = SystemClock.elapsedRealtime() }
    }
    return if (waiting) "播完后停止" else deadline?.let {
        val seconds = ((it - now).coerceAtLeast(0) + 999) / 1000
        "%02d:%02d".format(seconds / 60, seconds % 60)
    }
}
