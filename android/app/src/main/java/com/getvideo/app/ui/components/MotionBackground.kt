package com.getvideo.app.ui.components

import android.content.Context
import android.content.res.AssetManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.getvideo.app.data.repo.BuiltinKind
import com.getvideo.app.data.repo.WallpaperFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 动态背景：视频（mp4/webm）或动图（GIF/WebP），**始终静音**，可带旋转/缩放/平移。
 *
 * 视频用自己的 ExoPlayer 实例，和音频播放器完全分离：不抢音频焦点、音量为 0，
 * 退到后台（ON_STOP）暂停、回到前台（ON_START）继续，避免在后台空耗电。
 * 内置素材走 assets（asset:///…），用户自选的走 content://。
 */
@Composable
fun MotionBackground(
    source: String,
    isVideo: Boolean,
    isAsset: Boolean,
    frame: WallpaperFrame = WallpaperFrame(),
    modifier: Modifier = Modifier,
) {
    WallpaperView(
        source = source,
        isAsset = isAsset,
        media = if (isVideo) BuiltinKind.VIDEO else BuiltinKind.MOTION,
        frame = frame,
        modifier = modifier,
    )
}

/**
 * 视频画面本身：铺满传进来的盒子（盒子已经是视频的比例，所以拉伸等于不裁切）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoSurface(source: String, isAsset: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(source, isAsset, context) {
        val uri = if (isAsset) Uri.parse("asset:///$source") else Uri.parse(source)
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            // 背景永远不出声：音量归零，也不申请音频焦点。
            volume = 0f
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
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    DisposableEffect(lifecycle, player) {
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
                // 盒子已经是视频比例，FILL 不会变形，也不会二次裁切。
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                this.player = player
            }
        },
        update = { it.player = player },
    )
}

/** 动图（GIF / 动画 WebP）画面本身。 */
@Composable
internal fun AnimatedImageSurface(source: String, isAsset: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(initialValue = null, source, isAsset) {
        value = withContext(Dispatchers.IO) { loadAnimatedDrawable(context, source, isAsset) }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(AndroidColor.TRANSPARENT)
            }
        },
        update = { it.setImageDrawable(drawable) },
    )
}

/**
 * GIF/WebP 动图：API 28+ 用 ImageDecoder（原生支持循环与动画 WebP），
 * 26/27 退回 Movie 解 GIF；都失败时只显示第一帧，至少不留白屏。
 */
private fun loadAnimatedDrawable(context: Context, source: String, isAsset: Boolean): Drawable? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val src = if (isAsset) {
            android.graphics.ImageDecoder.createSource(context.assets, source)
        } else {
            android.graphics.ImageDecoder.createSource(context.contentResolver, Uri.parse(source))
        }
        android.graphics.ImageDecoder.decodeDrawable(src).also { drawable ->
            if (drawable is AnimatedImageDrawable) {
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                drawable.start()
            }
        }
    } else {
        val assets: AssetManager? = if (isAsset) context.assets else null
        val stream = { if (assets != null) assets.open(source) else context.contentResolver.openInputStream(Uri.parse(source)) }
        stream()?.use { input -> Movie.decodeStream(input)?.let { MovieDrawable(it) } }
            ?: stream()?.use { input -> BitmapFactory.decodeStream(input)?.let { BitmapDrawable(context.resources, it) } }
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
