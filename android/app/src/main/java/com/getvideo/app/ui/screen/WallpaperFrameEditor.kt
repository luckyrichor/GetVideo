package com.getvideo.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.getvideo.app.data.repo.BuiltinKind
import com.getvideo.app.data.repo.WallpaperFrame
import com.getvideo.app.data.repo.wallpaperGeometry
import com.getvideo.app.ui.components.WallpaperSurface
import com.getvideo.app.ui.components.AnimatedImageSurface
import com.getvideo.app.ui.components.VideoSurface
import com.getvideo.app.ui.components.wallpaperPainter
import com.getvideo.app.ui.components.rememberMediaSize
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale

/**
 * 壁纸「选显示区域」：中间是手机的竖长选框，图可以旋转、双指缩放、单指拖动。
 *
 * 原图多是 16:9 的桌面壁纸，竖屏铺满只剩中间一条，人物经常只露一点点或者看着是倒的，
 * 所以先让用户把图转正，再把想露出的部分拖进选框里。选框比例按手机屏幕来（9:19.5）。
 */
@Composable
internal fun WallpaperFrameEditor(
    source: String,
    isAsset: Boolean,
    media: BuiltinKind,
    initial: WallpaperFrame,
    onDismiss: () -> Unit,
    onSave: (WallpaperFrame) -> Unit,
) {
    val mediaSize = rememberMediaSize(source, isAsset, media)
    var frame by remember { mutableStateOf(initial) }
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    val srcW = mediaSize?.width?.toFloat() ?: 0f
    val srcH = mediaSize?.height?.toFloat() ?: 0f

    /** 缩放/平移都要按当前几何夹一遍，免得转完 90° 后平移量越界露出空白。 */
    fun apply(zoom: Float = frame.zoom, panX: Float = 0f, panY: Float = 0f) {
        if (srcW <= 0f || srcH <= 0f || canvas.width <= 0) return
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val geo = wallpaperGeometry(srcW, srcH, w, h, frame)
        val z = zoom.coerceIn(geo.minZoom, WallpaperFrame.MAX_ZOOM)
        val next = wallpaperGeometry(srcW, srcH, w, h, frame.copy(zoom = z))
        frame = frame.copy(
            zoom = z,
            offsetX = if (next.maxPanX > 1f) (frame.offsetX + panX / next.maxPanX).coerceIn(-1f, 1f) else 0f,
            offsetY = if (next.maxPanY > 1f) (frame.offsetY + panY / next.maxPanY).coerceIn(-1f, 1f) else 0f,
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0B10)) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, "取消", tint = Color.White.copy(alpha = 0.85f))
                    }
                    Text(
                        "调整显示区域",
                        Modifier.weight(1f),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    TextButton(onClick = { onSave(frame) }) {
                        Text("完成", color = Color(0xFF7FD4FF), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (mediaSize == null) {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
                    } else {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .aspectRatio(9f / 19.5f, matchHeightConstraintsFirst = true)
                                .clip(RoundedCornerShape(20.dp))
                                .onSizeChanged { canvas = it }
                                .pointerInput(srcW, srcH) {
                                    detectTransformGestures(panZoomLock = true) { _, pan, zoom, _ ->
                                        apply(zoom = frame.zoom * zoom, panX = pan.x, panY = pan.y)
                                    }
                                },
                        ) {
                            WallpaperSurface(srcW, srcH, frame, Modifier.fillMaxSize()) { fill ->
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
                            // 选框本身：描边 + 四角，提示这就是手机上能看到的范围。
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(20.dp)),
                            )
                        }
                    }
                }
                Text(
                    "框里就是手机实际显示的部分：先「旋转」把图转正，再双指缩放、单指拖动，把人放到框里。",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    EditorButton("旋转 90°", Icons.Rounded.RotateRight, Modifier.weight(1f)) {
                        frame = frame.rotated()
                        apply()
                    }
                    EditorButton("整张 / 铺满", Icons.Rounded.FitScreen, Modifier.weight(1f)) {
                        val cover = wallpaperGeometry(
                            srcW, srcH, canvas.width.toFloat(), canvas.height.toFloat(), frame,
                        )
                        frame = frame.copy(offsetX = 0f, offsetY = 0f)
                        apply(zoom = if (frame.zoom > cover.minZoom + 0.001f) cover.minZoom else 1f)
                    }
                    EditorButton("复位", Icons.Rounded.RestartAlt, Modifier.weight(1f)) {
                        frame = WallpaperFrame()
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.9f))
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
    }
}
