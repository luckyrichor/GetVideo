package com.getvideo.app.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import com.getvideo.app.data.repo.AppearanceBackground
import com.getvideo.app.data.repo.BackgroundStore

/** 让所有页面知道当前是否用了自定义背景：用了就把页面底色让出来。 */
val LocalBackgroundEnabled = compositionLocalOf { false }

/**
 * 全局背景层：铺在页面内容之下。
 *
 * 只在最底层画一次图片，再加一层遮罩（浅色主题用白色、深色主题用深色），
 * 页面里的卡片仍然是不透明的，所以按钮和文字不会被图片糊住。
 */
@Composable
fun BackgroundLayer() {
    val state by BackgroundStore.state.collectAsState()
    val source = state.source
    if (source == null) {
        Box(Modifier.fillMaxSize().background(uiPageBackground()))
        return
    }
    Box(Modifier.fillMaxSize()) {
        val painter = if (state.isAsset) assetPainter(source) else rememberAsyncImagePainter(model = Uri.parse(source))
        // 不模糊：背景要能一眼认出是那张图，遮罩只负责压一点亮度保证可读。
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        BackgroundScrim(state.scrim)
    }
}

/**
 * 遮罩：同一色调的竖向渐变。
 * 顶部略重一点保证状态栏和页面大标题可读，往下逐渐放轻，让图片主体清楚露出来。
 */
@Composable
fun BackgroundScrim(scrim: Float) {
    val base = if (uiIsDark()) Color(0xFF0C0A12) else Color(0xFFF5F3FA)
    val a = scrim.coerceIn(0.06f, 0.92f)
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to base.copy(alpha = (a + 0.16f).coerceAtMost(0.95f)),
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

/** 供设置页预览用：把当前背景的图片与遮罩一起画出来。 */
@Composable
fun BackgroundPreview(background: AppearanceBackground, modifier: Modifier = Modifier) {
    val state by BackgroundStore.state.collectAsState()
    Box(modifier.background(uiAccentContainer())) {
        val source = background.source
        if (source != null) {
            val painter = if (background.isAsset) assetPainter(source) else rememberAsyncImagePainter(model = Uri.parse(source))
            Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            BackgroundScrim(state.scrim)
        }
        Text(
            if (source == null) "未设置背景" else "示例文字 · 背景预览",
            Modifier.align(Alignment.Center),
            color = uiMuted(),
        )
    }
}
