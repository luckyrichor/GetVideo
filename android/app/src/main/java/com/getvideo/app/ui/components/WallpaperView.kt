package com.getvideo.app.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.getvideo.app.data.repo.BuiltinKind
import com.getvideo.app.data.repo.WallpaperFrame
import coil.compose.rememberAsyncImagePainter
import com.getvideo.app.data.repo.wallpaperGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 壁纸画布：把 [content] 按 [frame]（旋转 / 缩放 / 平移）摆好并裁到容器里。
 *
 * 编辑页和真正的背景层都用它，所以「选的时候看到什么」就是「回到页面看到什么」。
 * 容器尺寸由调用方决定（整屏或那个 9:19.5 的选框）。
 */
@Composable
fun WallpaperSurface(
    srcW: Float,
    srcH: Float,
    frame: WallpaperFrame,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        if (w <= 0f || h <= 0f) return@BoxWithConstraints
        val geometry = wallpaperGeometry(srcW, srcH, w, h, frame)
        val density = LocalDensity.current
        val drawW = with(density) { geometry.drawWidth.toDp() }
        val drawH = with(density) { geometry.drawHeight.toDp() }
        val panX = frame.offsetX.coerceIn(-1f, 1f) * geometry.maxPanX
        val panY = frame.offsetY.coerceIn(-1f, 1f) * geometry.maxPanY
        Box(
            Modifier
                .requiredSize(drawW, drawH)
                .align(Alignment.Center)
                .graphicsLayer {
                    rotationZ = frame.degrees.toFloat()
                    translationX = panX
                    translationY = panY
                },
        ) {
            content(Modifier.fillMaxSize())
        }
    }
}

/**
 * 背景图/动图/视频，按 [frame] 摆好铺满 [modifier] 给的空间。
 * 读不到尺寸时先留空，读到之后自然出现，不会闪一下错误的裁切。
 */
@Composable
fun WallpaperView(
    source: String,
    isAsset: Boolean,
    media: BuiltinKind,
    frame: WallpaperFrame,
    modifier: Modifier = Modifier,
) {
    val size = rememberMediaSize(source, isAsset, media)
    if (size == null) {
        Box(modifier)
        return
    }
    WallpaperSurface(size.width.toFloat(), size.height.toFloat(), frame, modifier) { fill ->
        when (media) {
            BuiltinKind.STILL -> Image(
                painter = wallpaperPainter(source, isAsset),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = fill,
            )
            BuiltinKind.MOTION -> AnimatedImageSurface(source, isAsset, fill)
            BuiltinKind.VIDEO -> VideoSurface(source, isAsset, fill)
        }
    }
}

/**
 * 静态壁纸统一走 Coil：解码在后台线程，并且按**画布尺寸**采样。
 *
 * 旋转后的画布比屏幕还宽（2400 宽的原图转到竖屏要画到 2340px），
 * 自己 `BitmapFactory` 解整张再缩会卡主线程，Coil 会按 Image 的实际约束解到合适的尺寸。
 */
@Composable
fun wallpaperPainter(source: String, isAsset: Boolean): Painter =
    rememberAsyncImagePainter(model = if (isAsset) "file:///android_asset/$source" else Uri.parse(source))

/** 记住素材的像素尺寸（旋转前），拿不到返回 null。 */
@Composable
fun rememberMediaSize(source: String, isAsset: Boolean, media: BuiltinKind): IntSize? {
    val context = LocalContext.current
    var size by remember(source, isAsset, media) { mutableStateOf<IntSize?>(null) }
    LaunchedEffect(source, isAsset, media) {
        if (size == null) {
            size = withContext(Dispatchers.IO) { readMediaSize(context, source, isAsset, media) }
        }
    }
    return size
}

private fun readMediaSize(
    context: Context, source: String, isAsset: Boolean, media: BuiltinKind,
): IntSize? = runCatching {
    if (media == BuiltinKind.VIDEO) {
        readVideoSize(context, source, isAsset)
    } else {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (isAsset) {
            context.assets.open(source).use { BitmapFactory.decodeStream(it, null, options) }
        } else {
            context.contentResolver.openInputStream(Uri.parse(source))
                ?.use { BitmapFactory.decodeStream(it, null, options) }
        }
        if (options.outWidth > 0 && options.outHeight > 0) IntSize(options.outWidth, options.outHeight) else null
    }
}.getOrNull()

/** 视频的显示尺寸要带上旋转元数据，否则横拍的视频会被压扁。 */
private fun readVideoSize(context: Context, source: String, isAsset: Boolean): IntSize? {
    val retriever = MediaMetadataRetriever()
    return try {
        if (isAsset) {
            // assets 里的 mp4 有可能被 aapt 压过，openFd 会抛异常，那就退到内存数据源。
            val fd = runCatching { context.assets.openFd(source) }.getOrNull()
            if (fd != null) {
                fd.use { retriever.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            } else {
                val bytes = context.assets.open(source).use { it.readBytes() }
                retriever.setDataSource(ByteArraySource(bytes))
            }
        } else {
            retriever.setDataSource(context, Uri.parse(source))
        }
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        when {
            w == null || h == null || w <= 0 || h <= 0 -> null
            rotation % 180 == 90 -> IntSize(h, w)
            else -> IntSize(w, h)
        }
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/** 给 MediaMetadataRetriever 用的内存数据源（只在 asset 被压缩时才走这里）。 */
private class ByteArraySource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(size, bytes.size - position.toInt())
        System.arraycopy(bytes, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() = Unit
}
