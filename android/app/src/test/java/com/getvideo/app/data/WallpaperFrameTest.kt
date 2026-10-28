package com.getvideo.app.data

import com.getvideo.app.data.repo.WallpaperFrame
import com.getvideo.app.data.repo.wallpaperGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 壁纸「选显示区域」的几何。
 *
 * 真机反馈：13 张原图都是 16:9 横图，直接铺满竖屏只剩中间一条，人物只露一点点、
 * 有的看着像倒的。用例锁住三件事：铺满确实会裁掉大半、旋转 + 缩放能救回来、平移量算得对。
 */
class WallpaperFrameTest {

    private val screenW = 1080f
    private val screenH = 2340f
    private val srcW = 2560f
    private val srcH = 1440f

    @Test
    fun coverOnPortraitScreenCropsMostOfALandscapeWallpaper() {
        val geo = wallpaperGeometry(srcW, srcH, screenW, screenH, WallpaperFrame())
        // 按高度铺满：2560 * (2340/1440) = 4160 宽，只露出 1080/4160 ≈ 26%。
        assertEquals(4160f, geo.drawWidth, 1f)
        assertEquals(2340f, geo.drawHeight, 1f)
        assertEquals(0f, geo.maxPanY, 0.5f)
        assertTrue("横向还有很大余量可以挪", geo.maxPanX > 1500f)
    }

    @Test
    fun rotatingNinetyDegreesStillCoversTheScreen() {
        val geo = wallpaperGeometry(srcW, srcH, screenW, screenH, WallpaperFrame(rotation = 90))
        // 转 90° 后宽高互换：画的是 2340x1316，转完正好是 1316x2340。
        assertEquals(2340f, geo.drawWidth, 1f)
        assertEquals(1316.25f, geo.drawHeight, 1f)
        assertTrue("转完不能露黑边：宽 ${geo.drawHeight} 应 >= $screenW", geo.drawHeight >= screenW - 0.5f)
        assertTrue("转完不能露黑边：高 ${geo.drawWidth} 应 >= $screenH", geo.drawWidth >= screenH - 0.5f)
    }

    @Test
    fun fitModeIsOnlyReachableThroughMinZoom() {
        val geo = wallpaperGeometry(srcW, srcH, screenW, screenH, WallpaperFrame())
        assertEquals(0.2596f, geo.minZoom, 0.001f)
        // 整张放进来：缩放正好等于 minZoom 时，两个方向都没有余量，图片居中留边。
        val fit = wallpaperGeometry(srcW, srcH, screenW, screenH, WallpaperFrame(zoom = geo.minZoom))
        assertEquals(0f, fit.maxPanX, 0.5f)
        assertEquals(0f, fit.maxPanY, 0.5f)
        assertEquals(screenW, fit.drawWidth, 1.5f)
    }

    @Test
    fun zoomBelowMinIsClampedInsteadOfShrinkingIntoTinyImage() {
        val geo = wallpaperGeometry(srcW, srcH, screenW, screenH, WallpaperFrame(zoom = 0.01f))
        assertEquals(0.2596f, geo.minZoom, 0.001f)
        assertEquals(1080f, geo.drawWidth, 1.5f)
    }

    @Test
    fun zoomingInGivesMoreRoomToPan() {
        val one = wallpaperGeometry(srcW, srcH, screenW, screenH, WallpaperFrame())
        val two = wallpaperGeometry(srcW, srcH, screenW, screenH, WallpaperFrame(zoom = 2f))
        assertTrue("放大后可移动范围必须变大", two.maxPanX > one.maxPanX)
        assertEquals(one.drawWidth * 2f, two.drawWidth, 2f)
    }

    @Test
    fun squarePhotoFitsWithoutCropping() {
        // 1:1 的图在竖屏上：按高度铺满 1200→2340，横向还剩 630 可以左右挪。
        val geo = wallpaperGeometry(1200f, 1200f, screenW, screenH, WallpaperFrame())
        assertEquals(2340f, geo.drawWidth, 1f)
        assertEquals(2340f, geo.drawHeight, 1f)
        assertEquals(0f, geo.maxPanY, 0.5f)
        assertTrue(geo.maxPanX > 600f)
    }

    @Test
    fun frameSurvivesEncodeDecode() {
        val frame = WallpaperFrame(rotation = 270, zoom = 1.8f, offsetX = -0.4f, offsetY = 0.25f)
        val back = WallpaperFrame.decode(frame.encode())
        assertEquals(270, back.degrees)
        assertEquals(1.8f, back.zoom, 0.001f)
        assertEquals(-0.4f, back.offsetX, 0.001f)
        assertEquals(0.25f, back.offsetY, 0.001f)
    }

    @Test
    fun brokenOrMissingFrameFallsBackToDefault() {
        assertEquals(WallpaperFrame.DEFAULT, WallpaperFrame.decode(null))
        assertEquals(WallpaperFrame.DEFAULT, WallpaperFrame.decode(""))
        assertEquals(WallpaperFrame.DEFAULT, WallpaperFrame.decode("oops"))
        // 手滑存了越界值也不能把画面甩出屏幕。
        val clamped = WallpaperFrame.decode("-90|99|9|-9")
        assertEquals(270, clamped.degrees)
        assertEquals(WallpaperFrame.MAX_ZOOM, clamped.zoom, 0.001f)
        assertEquals(1f, clamped.offsetX, 0.001f)
        assertEquals(-1f, clamped.offsetY, 0.001f)
    }

    @Test
    fun rotationIsNormalizedToQuarterTurns() {
        assertEquals(90, WallpaperFrame(rotation = -270).degrees)
        assertEquals(0, WallpaperFrame(rotation = 360).degrees)
        assertEquals(270, WallpaperFrame(rotation = 180).rotated().degrees)
        assertEquals(0, WallpaperFrame(rotation = 270).rotated().degrees)
    }
}
