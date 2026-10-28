package com.getvideo.app.data.repo

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 背景外观设置：静态图或动态背景（视频/动图）的地址，以及卡片不透明度。 */
data class AppearanceBackground(
    val uri: String? = null,
    val motionUri: String? = null,
    val motionIsVideo: Boolean = false,
    val scrim: Float = BackgroundStore.MIN_SCRIM,
    val builtin: String? = null,
    val cardAlpha: Float = 0.58f,
) {
    val enabled: Boolean get() = isMotion || !uri.isNullOrBlank() || !builtin.isNullOrBlank()

    /** 动态背景（视频或动图）优先于静态图片。 */
    val isMotion: Boolean get() = !motionUri.isNullOrBlank()

    /** 静态背景实际要加载的地址：自选图片优先，其次是内置图（asset 名）。 */
    val source: String? get() = uri?.takeIf { it.isNotBlank() } ?: builtin?.takeIf { it.isNotBlank() }
    val isAsset: Boolean get() = uri.isNullOrBlank() && !builtin.isNullOrBlank()
}

/**
 * 背景偏好。只存地址与遮罩强度，图片/视频本身留在原处（content:// 通过持久化授权读取）。
 */
object BackgroundStore {
    /** 压暗程度固定为最低值，不再开放调节（2026-10-28 按用户要求移除）。 */
    const val MIN_SCRIM = 0.04f
    private const val PREFS = "appearance"
    private const val KEY_URI = "background_uri"
    private const val KEY_BUILTIN = "background_builtin"
    private const val KEY_CARD_ALPHA = "card_alpha"
    private const val KEY_MOTION_URI = "background_motion_uri"
    private const val KEY_MOTION_VIDEO = "background_motion_video"

    /** 内置背景，随 APK 打包，选中即用。 */
    val builtins = listOf(
        "内置 · 芙宁娜" to "furina.jpg",
    )

    private val _state = MutableStateFlow(AppearanceBackground())
    val state: StateFlow<AppearanceBackground> = _state

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        val p = prefs(context)
        _state.value = AppearanceBackground(
            uri = p.getString(KEY_URI, null),
            motionUri = p.getString(KEY_MOTION_URI, null),
            motionIsVideo = p.getBoolean(KEY_MOTION_VIDEO, false),
            scrim = MIN_SCRIM,
            builtin = p.getString(KEY_BUILTIN, null),
            cardAlpha = p.getFloat(KEY_CARD_ALPHA, 0.58f),
        )
    }

    fun setImage(context: Context, uri: Uri) {
        prefs(context).edit()
            .putString(KEY_URI, uri.toString())
            .remove(KEY_BUILTIN)
            .remove(KEY_MOTION_URI)
            .apply()
        _state.value = _state.value.copy(
            uri = uri.toString(), builtin = null, motionUri = null, motionIsVideo = false,
        )
    }

    /**
     * 动态背景：视频（mp4/webm）或动图（gif/webp）。
     * 一次只生效一个背景，设置动态背景会清掉静态图，反之亦然。
     */
    fun setMotion(context: Context, uri: Uri, isVideo: Boolean) {
        prefs(context).edit()
            .putString(KEY_MOTION_URI, uri.toString())
            .putBoolean(KEY_MOTION_VIDEO, isVideo)
            .remove(KEY_URI)
            .remove(KEY_BUILTIN)
            .apply()
        _state.value = _state.value.copy(
            motionUri = uri.toString(), motionIsVideo = isVideo, uri = null, builtin = null,
        )
    }

    fun clearMotion(context: Context) {
        prefs(context).edit().remove(KEY_MOTION_URI).remove(KEY_MOTION_VIDEO).apply()
        _state.value = _state.value.copy(motionUri = null, motionIsVideo = false)
    }

    fun setBuiltin(context: Context, asset: String?) {
        prefs(context).edit()
            .putString(KEY_BUILTIN, asset)
            .remove(KEY_URI)
            .remove(KEY_MOTION_URI)
            .apply()
        _state.value = _state.value.copy(
            builtin = asset, uri = null, motionUri = null, motionIsVideo = false,
        )
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
        _state.value = _state.value.copy(uri = null, builtin = null, motionUri = null, motionIsVideo = false)
    }
}
