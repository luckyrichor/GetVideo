package com.getvideo.app.data.repo

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 内置背景的种类：静态图、动图（动画 WebP）、视频。 */
enum class BuiltinKind { STILL, MOTION, VIDEO }

/**
 * 内置壁纸：素材随 APK 打包在 assets/backgrounds 下，选中即用。
 * 不需要网络，也不需要相册授权；视频和动图一律无声。
 */
data class BuiltinBackground(
    val id: String,
    val label: String,
    val kind: BuiltinKind,
) {
    /**
     * 唯一键：同一个角色既有静态图又有动图，只靠 id 分不开，
     * 所以键里带上种类，存偏好和比对选中都用它。
     */
    val key: String get() = if (kind == BuiltinKind.STILL) "still_$id" else "${kind.name.lowercase()}_$id"

    /** 真正加载的文件。 */
    val asset: String
        get() = when (kind) {
            BuiltinKind.STILL -> "backgrounds/static/$id.jpg"
            BuiltinKind.MOTION -> "backgrounds/motion/$id.webp"
            BuiltinKind.VIDEO -> "backgrounds/video/$id.mp4"
        }

    /** 选择器里的缩略图（320px 宽的 jpg，避免一进页面就解码十几张大图）。 */
    val thumb: String
        get() = when (kind) {
            BuiltinKind.STILL -> "backgrounds/thumb/$id.jpg"
            BuiltinKind.MOTION -> "backgrounds/thumb/m_$id.jpg"
            BuiltinKind.VIDEO -> "backgrounds/thumb/v_$id.jpg"
        }

    val kindLabel: String
        get() = when (kind) {
            BuiltinKind.STILL -> "静态"
            BuiltinKind.MOTION -> "动图"
            BuiltinKind.VIDEO -> "视频"
        }
}

/** 背景外观设置：静态图或动态背景（视频/动图）的来源，以及卡片不透明度。 */
data class AppearanceBackground(
    /** 用户自选的静态图（content:// 或 file://）。 */
    val uri: String? = null,
    /** 用户自选的动态背景（视频或动图）。 */
    val motionUri: String? = null,
    val motionIsVideo: Boolean = false,
    /** 内置背景的 id；设置后以它为准，和上面两个互斥。 */
    val builtinId: String? = null,
    val cardAlpha: Float = 0.58f,
    /** 这张背景要旋转/缩放/平移成什么样（「外观 → 调整显示区域」）。 */
    val frame: WallpaperFrame = WallpaperFrame(),
) {
    val builtin: BuiltinBackground? get() = builtinId?.let { BackgroundStore.builtinOf(it) }

    private val builtinMotion: Boolean
        get() = builtin != null && builtin?.kind != BuiltinKind.STILL

    /** 当前是否是动态背景（内置动图/视频，或用户自选动图/视频）。 */
    val isMotion: Boolean get() = builtinMotion || !motionUri.isNullOrBlank()

    /** 动态背景是不是视频（决定用播放器还是动图解码）。 */
    val motionVideo: Boolean get() = if (builtinMotion) builtin?.kind == BuiltinKind.VIDEO else motionIsVideo

    /** 动态背景要加载的地址：内置素材名或用户文件路径。 */
    val motionSource: String? get() = if (builtinMotion) builtin?.asset else motionUri?.takeIf { it.isNotBlank() }

    /** 动态背景来自 assets（内置）还是用户文件。 */
    val motionIsAsset: Boolean get() = builtinMotion

    /** 静态背景要加载的地址：内置素材名或用户文件路径。 */
    val stillSource: String? get() = if (builtin?.kind == BuiltinKind.STILL) builtin?.asset else uri?.takeIf { it.isNotBlank() }

    val stillIsAsset: Boolean get() = builtin?.kind == BuiltinKind.STILL

    val enabled: Boolean get() = isMotion || stillSource != null
}

/**
 * 背景偏好。用户自选的图片/视频只存地址（content:// 通过持久化授权读取），
 * 内置壁纸只存 id，素材本身在 APK 里。
 *
 * 三种来源互斥：选内置会清掉用户文件，选用户文件会清掉内置，静态与动态也互斥。
 */
object BackgroundStore {
    /** 压暗程度固定为最低值，不再开放调节（2026-10-28 按用户要求移除）。 */
    const val MIN_SCRIM = 0.04f
    private const val PREFS = "appearance"
    private const val KEY_URI = "background_uri"
    private const val KEY_BUILTIN = "background_builtin_id"
    private const val KEY_CARD_ALPHA = "card_alpha"
    private const val KEY_MOTION_URI = "background_motion_uri"
    private const val KEY_MOTION_VIDEO = "background_motion_video"
    private const val KEY_FRAME_PREFIX = "frame_"

    /** 13 张静态壁纸。 */
    val stills = listOf(
        BuiltinBackground("furina", "芙宁娜", BuiltinKind.STILL),
        BuiltinBackground("raiden", "雷电将军", BuiltinKind.STILL),
        BuiltinBackground("yae", "八重神子", BuiltinKind.STILL),
        BuiltinBackground("hutao", "胡桃", BuiltinKind.STILL),
        BuiltinBackground("nahida", "纳西妲", BuiltinKind.STILL),
        BuiltinBackground("ineffa", "伊涅芙", BuiltinKind.STILL),
        BuiltinBackground("lauma", "菈乌玛", BuiltinKind.STILL),
        BuiltinBackground("nefer", "奈芙尔", BuiltinKind.STILL),
        BuiltinBackground("nicole", "妮可", BuiltinKind.STILL),
        BuiltinBackground("sandrone", "桑多涅", BuiltinKind.STILL),
        BuiltinBackground("odette", "奥黛塔", BuiltinKind.STILL),
        BuiltinBackground("vesna", "薇斯纳", BuiltinKind.STILL),
        BuiltinBackground("vodyanitsa", "沃雅妮莎", BuiltinKind.STILL),
    )

    /** 3 张动图 + 3 段视频，都是慢推镜循环、无声。 */
    val motions = listOf(
        BuiltinBackground("furina", "芙宁娜 · 动图", BuiltinKind.MOTION),
        BuiltinBackground("hutao", "胡桃 · 动图", BuiltinKind.MOTION),
        BuiltinBackground("lauma", "菈乌玛 · 动图", BuiltinKind.MOTION),
        BuiltinBackground("raiden", "雷电将军 · 视频", BuiltinKind.VIDEO),
        BuiltinBackground("sandrone", "桑多涅 · 视频", BuiltinKind.VIDEO),
        BuiltinBackground("nicole", "妮可 · 视频", BuiltinKind.VIDEO),
    )

    val builtins: List<BuiltinBackground> = stills + motions

    fun builtinOf(key: String?): BuiltinBackground? = builtins.firstOrNull { it.key == key }

    private val _state = MutableStateFlow(AppearanceBackground())
    val state: StateFlow<AppearanceBackground> = _state

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        val p = prefs(context)
        // 旧版本存的是 asset 文件名（furina.jpg），顺手迁移成新键。
        val stored = p.getString(KEY_BUILTIN, null)
        val loaded = AppearanceBackground(
            uri = p.getString(KEY_URI, null),
            motionUri = p.getString(KEY_MOTION_URI, null),
            motionIsVideo = p.getBoolean(KEY_MOTION_VIDEO, false),
            builtinId = builtinOf(stored)?.key ?: builtinOf("still_" + stored?.removeSuffix(".jpg"))?.key,
            cardAlpha = p.getFloat(KEY_CARD_ALPHA, 0.58f),
        )
        _state.value = loaded.copy(frame = loadFrame(context, frameKeyOf(loaded)))
    }

    /**
     * 每张背景各自记一份「显示区域」，换回来还在。
     * 内置壁纸用 key（still_furina），自选文件用 uri 的哈希。
     */
    fun frameKeyOf(background: AppearanceBackground): String? = when {
        background.builtinId != null -> background.builtinId
        background.motionUri != null -> "mov:" + background.motionUri.hashCode()
        background.uri != null -> "img:" + background.uri.hashCode()
        else -> null
    }

    private fun loadFrame(context: Context, key: String?): WallpaperFrame =
        if (key == null) WallpaperFrame.DEFAULT
        else WallpaperFrame.decode(prefs(context).getString(KEY_FRAME_PREFIX + key, null))

    /** 保存当前背景的旋转/缩放/平移。[] = 恢复默认（正放、铺满、居中）。 */
    fun setFrame(context: Context, frame: WallpaperFrame) {
        val key = frameKeyOf(_state.value) ?: return
        prefs(context).edit().putString(KEY_FRAME_PREFIX + key, frame.encode()).apply()
        _state.value = _state.value.copy(frame = frame)
    }

    fun setImage(context: Context, uri: Uri) {
        prefs(context).edit()
            .putString(KEY_URI, uri.toString())
            .remove(KEY_BUILTIN)
            .remove(KEY_MOTION_URI)
            .apply()
        val next = _state.value.copy(
            uri = uri.toString(), builtinId = null, motionUri = null, motionIsVideo = false,
        )
        _state.value = next.copy(frame = loadFrame(context, frameKeyOf(next)))
    }

    /**
     * 用户自选的动态背景：视频（mp4/webm）或动图（gif/webp）。
     * 一次只生效一个背景，设置动态背景会清掉静态图和内置壁纸。
     */
    fun setMotion(context: Context, uri: Uri, isVideo: Boolean) {
        prefs(context).edit()
            .putString(KEY_MOTION_URI, uri.toString())
            .putBoolean(KEY_MOTION_VIDEO, isVideo)
            .remove(KEY_URI)
            .remove(KEY_BUILTIN)
            .apply()
        val next = _state.value.copy(
            motionUri = uri.toString(), motionIsVideo = isVideo, uri = null, builtinId = null,
        )
        _state.value = next.copy(frame = loadFrame(context, frameKeyOf(next)))
    }

    fun clearMotion(context: Context) {
        prefs(context).edit().remove(KEY_MOTION_URI).remove(KEY_MOTION_VIDEO).apply()
        _state.value = _state.value.copy(motionUri = null, motionIsVideo = false)
    }

    /** 选内置壁纸（静态/动图/视频都走这里），参数是 [BuiltinBackground.key]。 */
    fun setBuiltin(context: Context, key: String?) {
        if (key != null && builtinOf(key) == null) return
        prefs(context).edit()
            .putString(KEY_BUILTIN, key)
            .remove(KEY_URI)
            .remove(KEY_MOTION_URI)
            .remove(KEY_MOTION_VIDEO)
            .apply()
        val next = _state.value.copy(
            builtinId = key, uri = null, motionUri = null, motionIsVideo = false,
        )
        _state.value = next.copy(frame = loadFrame(context, frameKeyOf(next)))
    }

    /** 卡片不透明度：1.0 是纯白不透明，越小背景透得越多。 */
    fun setCardAlpha(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_CARD_ALPHA, value).apply()
        _state.value = _state.value.copy(cardAlpha = value)
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_URI).remove(KEY_BUILTIN)
            .remove(KEY_MOTION_URI).remove(KEY_MOTION_VIDEO)
            .apply()
        _state.value = _state.value.copy(uri = null, builtinId = null, motionUri = null, motionIsVideo = false)
    }
}
