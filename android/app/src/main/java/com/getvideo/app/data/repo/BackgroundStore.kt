package com.getvideo.app.data.repo

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 背景外观设置：用户自选图片或内置图的地址，以及压暗程度。 */
data class AppearanceBackground(
    val uri: String? = null,
    val scrim: Float = 0.22f,
    val builtin: String? = null,
) {
    val enabled: Boolean get() = !uri.isNullOrBlank() || !builtin.isNullOrBlank()

    /** 实际要加载的地址：自选图片优先，其次是内置图（asset 名）。 */
    val source: String? get() = uri?.takeIf { it.isNotBlank() } ?: builtin?.takeIf { it.isNotBlank() }
    val isAsset: Boolean get() = uri.isNullOrBlank() && !builtin.isNullOrBlank()
}

/**
 * 背景图偏好。只存地址与遮罩强度，图片本身留在原处（content:// 通过持久化授权读取）。
 */
object BackgroundStore {
    private const val PREFS = "appearance"
    private const val KEY_URI = "background_uri"
    private const val KEY_SCRIM = "background_scrim"
    private const val KEY_BUILTIN = "background_builtin"

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
            scrim = p.getFloat(KEY_SCRIM, 0.22f),
            builtin = p.getString(KEY_BUILTIN, null),
        )
    }

    fun setImage(context: Context, uri: Uri) {
        prefs(context).edit().putString(KEY_URI, uri.toString()).remove(KEY_BUILTIN).apply()
        _state.value = _state.value.copy(uri = uri.toString(), builtin = null)
    }

    fun setBuiltin(context: Context, asset: String?) {
        prefs(context).edit().putString(KEY_BUILTIN, asset).remove(KEY_URI).apply()
        _state.value = _state.value.copy(builtin = asset, uri = null)
    }

    fun setScrim(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_SCRIM, value).apply()
        _state.value = _state.value.copy(scrim = value)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_URI).remove(KEY_BUILTIN).apply()
        _state.value = _state.value.copy(uri = null, builtin = null)
    }
}
