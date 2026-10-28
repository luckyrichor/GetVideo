package com.getvideo.app.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.getvideo.app.data.repo.AppearanceBackground
import com.getvideo.app.data.repo.BackgroundStore
import com.getvideo.app.data.repo.BuiltinKind

/** 让所有页面知道当前是否用了自定义背景：用了就把页面底色让出来。 */
val LocalBackgroundEnabled = compositionLocalOf { false }

/**
 * 有背景图时卡片的不透明度，由「我的 → 外观」里的滑杆控制。
 * 1.0 表示纯白不透明，越小背景透得越多。
 */
val LocalCardAlpha = compositionLocalOf { 0.58f }

/**
 * 全局背景层：铺在页面内容之下。
 *
 * 只在最底层画一次图片/动图/视频，再加一层遮罩（浅色主题用白色、深色主题用深色），
 * 页面里的卡片仍然是不透明的，所以按钮和文字不会被图片糊住。
 * 旋转/缩放/平移由「外观 → 调整显示区域」决定，编辑页与这里用同一套几何。
 */
@Composable
fun BackgroundLayer() {
    val state by BackgroundStore.state.collectAsState()
    val motion = state.motionSource
    val still = state.stillSource
    val source = motion ?: still
    if (source == null) {
        Box(Modifier.fillMaxSize().background(uiPageBackground()))
        return
    }
    val media = if (motion != null) {
        if (state.motionVideo) BuiltinKind.VIDEO else BuiltinKind.MOTION
    } else {
        BuiltinKind.STILL
    }
    val isAsset = if (motion != null) state.motionIsAsset else state.stillIsAsset
    Box(Modifier.fillMaxSize()) {
        // 不模糊：背景要能一眼认出是那张图，遮罩只负责压一点亮度保证可读。
        WallpaperView(source, isAsset, media, state.frame, Modifier.fillMaxSize())
        BackgroundScrim(BackgroundStore.MIN_SCRIM)
    }
}

/**
 * 遮罩：同一色调的竖向渐变。
 * 顶部略重一点保证状态栏和页面大标题可读，往下逐渐放轻，让图片主体清楚露出来。
 */
@Composable
fun BackgroundScrim(scrim: Float) {
    val base = if (uiIsDark()) Color(0xFF0C0A12) else Color(0xFFF5F3FA)
    val a = scrim.coerceIn(0.04f, 0.92f)
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to base.copy(alpha = (a + 0.10f).coerceAtMost(0.9f)),
                    0.2f to base.copy(alpha = a),
                    1f to base.copy(alpha = (a * 0.82f).coerceAtLeast(0.05f)),
                ),
            ),
    )
}

/** 内置背景随 APK 打包在 assets 根目录，选中即用，不需要网络。 */
@Composable
fun assetPainter(name: String): Painter {
    val context = LocalContext.current
    val painter = remember(name) {
        runCatching {
            context.assets.open(name).use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        }.getOrNull()?.let { BitmapPainter(it) }
    }
    return painter ?: rememberAsyncImagePainter(model = "file:///android_asset/$name")
}

/** 供设置页预览用：把当前背景的图与遮罩一起画出来。 */
@Composable
fun BackgroundPreview(background: AppearanceBackground, modifier: Modifier = Modifier) {
    Box(modifier.background(uiAccentContainer())) {
        val motion = background.motionSource
        val source = motion ?: background.stillSource
        if (source != null) {
            val media = if (motion != null) {
                if (background.motionVideo) BuiltinKind.VIDEO else BuiltinKind.MOTION
            } else {
                BuiltinKind.STILL
            }
            val isAsset = if (motion != null) background.motionIsAsset else background.stillIsAsset
            WallpaperView(source, isAsset, media, background.frame, Modifier.fillMaxSize())
            BackgroundScrim(BackgroundStore.MIN_SCRIM)
            // 预览里放一张真卡片，拖动「卡片不透明度」滑杆时这里立刻跟着变。
            androidx.compose.material3.Surface(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
                color = uiCardColor(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            ) {
                androidx.compose.foundation.layout.Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text("示例卡片", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    Text("卡片后面的图看得清吗", color = uiMuted(), fontSize = 11.sp)
                }
            }
        } else {
            Text("未设置背景", Modifier.align(Alignment.Center), color = uiMuted())
        }
    }
}
