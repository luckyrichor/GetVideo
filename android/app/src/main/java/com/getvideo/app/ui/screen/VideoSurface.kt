package com.getvideo.app.ui.screen

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import android.graphics.Color

/** One surface at a time; detach without releasing the shared playback service. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoSurface(player: Player, modifier: Modifier = Modifier, controls: Boolean = true) {
    var view by remember { mutableStateOf<PlayerView?>(null) }
    DisposableEffect(player) { onDispose { view?.player = null } }
    AndroidView(modifier = modifier, factory = { context ->
        PlayerView(context).also {
            it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            it.setBackgroundColor(Color.BLACK)
            it.setShutterBackgroundColor(Color.BLACK)
            it.keepScreenOn = true
            it.useController = controls; it.player = player; view = it
        }
    }, update = { it.player = player; it.useController = controls })
}
