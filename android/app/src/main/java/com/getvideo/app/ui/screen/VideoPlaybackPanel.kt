package com.getvideo.app.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.view.WindowManager
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import com.getvideo.app.player.PlayerHolder

/** Inline preview and explicit full screen share the same session and playback position. */
@Composable
internal fun VideoPlaybackPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaController?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    val queue by PlayerHolder.queue.collectAsState()
    val index by PlayerHolder.currentIndex.collectAsState()
    val title = queue.getOrNull(index)?.track?.title ?: "视频播放"
    LaunchedEffect(context) { try { player = PlayerHolder.get(context) } catch (e: Exception) { error = e.message } }
    DisposableEffect(player) {
        fun update(size: VideoSize) {
            if (size.width > 0 && size.height > 0) ratio = size.width * size.pixelWidthHeightRatio / size.height
        }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = update(videoSize)
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) { error = "播放失败：${e.errorCodeName}" }
        }
        player?.let { update(it.videoSize); it.addListener(listener) }
        onDispose { player?.removeListener(listener) }
    }
    if (fullscreen) {
        val activity = remember(context) { context.activity() }
        DisposableEffect(activity, ratio >= 1f) {
            val previous = activity?.requestedOrientation
            activity?.requestedOrientation = if (ratio >= 1f) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            onDispose { if (previous != null) activity.requestedOrientation = previous }
        }
        // Compose 1.6 Dialog caches its measured window size. Recreate the window on rotation,
        // while keeping the controller and playback position outside this key.
        key(LocalConfiguration.current.orientation) {
            Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                val view = LocalView.current
                val configuration = LocalConfiguration.current
                DisposableEffect(view, configuration.screenWidthDp, configuration.screenHeightDp) {
                    val window = (view.parent as? DialogWindowProvider)?.window
                    window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
                    window?.decorView?.setPadding(0, 0, 0, 0)
                    window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    window?.setDimAmount(1f)
                    window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    val bars = window?.let { WindowCompat.getInsetsController(it, view) }
                    bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    bars?.hide(WindowInsetsCompat.Type.systemBars())
                    onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
                }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    player?.let { VideoSurface(it, Modifier.fillMaxSize()) }
                    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().background(Color.Black.copy(alpha = .55f)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), color = Color.White, maxLines = 1)
                        TextButton(onClick = { fullscreen = false }) { Text("退出全屏", color = Color.White) }
                    }
                    error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center)) }
                }
            }
        }
    } else {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { fullscreen = true }) { Text("全屏") }
                    TextButton(onClick = onClose) { Text("收起") }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().background(Color.Black)) {
                    val height = (maxWidth / ratio).coerceIn(140.dp, 260.dp)
                    player?.let { VideoSurface(it, Modifier.fillMaxWidth().height(height)) }
                    error?.let { Text(it, color = Color.White, modifier = Modifier.padding(12.dp)) }
                }
            }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
