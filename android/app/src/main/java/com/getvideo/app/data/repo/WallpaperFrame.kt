package com.getvideo.app.data.repo

import kotlin.math.max
import kotlin.math.min

/**
 * 壁纸的显示位置：先旋转，再缩放，最后在手机屏幕那个长方形里平移。
 *
 * 桌面壁纸大多是 16:9 的横图，直接「铺满」竖屏手机只剩中间不到三分之一，
 * 人物经常只露半个身子、甚至看着像倒的。所以这里把「选哪一块显示」交给用户：
 *
 *  - [rotation] 90 度一档，解决横图要立起来看的问题；
 *  - [zoom] 相对「铺满」的倍数，1f = 铺满（会裁边），[WallpaperGeometry.minZoom] = 整张塞进来（留边）；
 *  - [offsetX]/[offsetY] 归一化到「可平移余量」，-1..1，与屏幕分辨率无关。
 */
data class WallpaperFrame(
    val rotation: Int = 0,
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    /** 归一到 0/90/180/270。 */
    val degrees: Int get() = ((rotation % 360) + 360) % 360

    val isDefault: Boolean
        get() = degrees == 0 && zoom == 1f && offsetX == 0f && offsetY == 0f

    fun rotated(): WallpaperFrame = copy(rotation = (degrees + 90) % 360)

    fun encode(): String = "$degrees|$zoom|$offsetX|$offsetY"

    companion object {
        /** 最大放大倍数：再大地图也只会糊，没必要。 */
        const val MAX_ZOOM = 5f
        val DEFAULT = WallpaperFrame()

        fun decode(raw: String?): WallpaperFrame {
            if (raw.isNullOrBlank()) return DEFAULT
            val parts = raw.split('|')
            if (parts.size != 4) return DEFAULT
            val rotation = parts[0].toIntOrNull() ?: 0
            val zoom = parts[1].toFloatOrNull() ?: 1f
            val ox = parts[2].toFloatOrNull() ?: 0f
            val oy = parts[3].toFloatOrNull() ?: 0f
            return WallpaperFrame(
                rotation = rotation,
                zoom = zoom.coerceIn(0.02f, MAX_ZOOM),
                offsetX = ox.coerceIn(-1f, 1f),
                offsetY = oy.coerceIn(-1f, 1f),
            )
        }
    }
}

/**
 * 一帧的几何结果，全部是像素。
 *
 * [drawWidth]/[drawHeight] 是**旋转前**要画的尺寸（旋转绕中心，包围盒正好等于屏幕）；
 * [maxPanX]/[maxPanY] 是该方向上还能移动多少（0 表示刚好铺满，不能移）。
 */
data class WallpaperGeometry(
    val drawWidth: Float,
    val drawHeight: Float,
    val maxPanX: Float,
    val maxPanY: Float,
    val minZoom: Float,
)

/**
 * 算出「源图 srcW×srcH 放在 dstW×dstH 的屏幕里」应该画多大、能移多远。
 *
 * 纯函数，单测覆盖；编辑页和背景层用的是同一份，所见即所得。
 */
fun wallpaperGeometry(
    srcW: Float, srcH: Float,
    dstW: Float, dstH: Float,
    frame: WallpaperFrame,
): WallpaperGeometry {
    if (srcW <= 0f || srcH <= 0f || dstW <= 0f || dstH <= 0f) {
        return WallpaperGeometry(dstW.coerceAtLeast(1f), dstH.coerceAtLeast(1f), 0f, 0f, 1f)
    }
    val swap = frame.degrees % 180 == 90
    // 旋转后图片的朝向尺寸，用它来判断「铺满」和「整张」各自需要多大倍数。
    val rw = if (swap) srcH else srcW
    val rh = if (swap) srcW else srcH
    val cover = max(dstW / rw, dstH / rh)
    val fit = min(dstW / rw, dstH / rh)
    val minZoom = (fit / cover).coerceIn(0.02f, 1f)
    val zoom = frame.zoom.coerceIn(minZoom, WallpaperFrame.MAX_ZOOM)
    val scale = cover * zoom
    return WallpaperGeometry(
        drawWidth = srcW * scale,
        drawHeight = srcH * scale,
        maxPanX = ((rw * scale - dstW) / 2f).coerceAtLeast(0f),
        maxPanY = ((rh * scale - dstH) / 2f).coerceAtLeast(0f),
        minZoom = minZoom,
    )
}
