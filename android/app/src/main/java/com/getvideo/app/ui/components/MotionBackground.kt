package com.getvideo.app.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Movie
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 动态背景：视频（mp4/webm）或动图（GIF/WebP），铺满并裁切，静音循环。
 *
 * 视频用自己的 ExoPlayer 实例，和音频播放器完全分离：不抢音频焦点、音量为 0，
 * 退到后台（ON_STOP）暂停、回到前台（ON_START）继续，避免在后台空耗电。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MotionBackground(uri: Uri, isVideo: Boolean, modifier: Modifier = Modifier) {
    if (isVideo) VideoBackground(uri, modifier) else AnimatedImageBackground(uri, modifier)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoBackground(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = androidx.compose.runtime.remember(uri, context) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            // handleAudioFocus = false：背景视频不参与音频焦点，不会打断正在播的音频。
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false,
            )
            playWhenReady = true
            prepare()
        }
    }
    androidx.compose.runtime.DisposableEffect(player) {
        onDispose { player.release() }
    }
    androidx.compose.runtime.DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.play()
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                this.player = player
            }
        },
        update = { it.player = player },
    )
}

@Composable
private fun AnimatedImageBackground(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { loadAnimatedDrawable(context, uri) }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(AndroidColor.TRANSPARENT)
            }
        },
        update = { it.setImageDrawable(drawable) },
    )
}

/**
 * GIF/WebP 动图：API 28+ 用 ImageDecoder（原生支持循环与 WebP），
 * 26/27 退回 Movie 解 GIF；都失败时只显示第一帧，至少不留白屏。
 */
private fun loadAnimatedDrawable(context: Context, uri: Uri): Drawable? = runCatching {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = android.graphics.ImageDecoder.createSource(resolver, uri)
        android.graphics.ImageDecoder.decodeDrawable(source).also { drawable ->
            if (drawable is AnimatedImageDrawable) {
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                drawable.start()
            }
        }
    } else {
        resolver.openInputStream(uri)?.use { input ->
            Movie.decodeStream(input)?.let { MovieDrawable(it) }
        } ?: resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)?.let { BitmapDrawable(context.resources, it) }
        }
    }
}.getOrNull()

/** API 26/27 的 GIF 播放器：按 uptime 推进帧，每帧重绘一次。 */
private class MovieDrawable(private val movie: Movie) : Drawable() {
    private val startedAt = SystemClock.uptimeMillis()

    override fun draw(canvas: Canvas) {
        if (movie.width() <= 0 || movie.height() <= 0) return
        val duration = movie.duration().takeIf { it > 0 } ?: 1000
        movie.setTime(((SystemClock.uptimeMillis() - startedAt) % duration).toInt())
        val scale = maxOf(
            bounds.width().toFloat() / movie.width(),
            bounds.height().toFloat() / movie.height(),
        )
        canvas.save()
        canvas.translate(
            bounds.left + (bounds.width() - movie.width() * scale) / 2f,
            bounds.top + (bounds.height() - movie.height() * scale) / 2f,
        )
        canvas.scale(scale, scale)
        movie.draw(canvas, 0f, 0f)
        canvas.restore()
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
