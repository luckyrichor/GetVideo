package com.getvideo.app.data.repo

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellable, bounded health probe. Never shares the extraction request's long timeout. */
internal suspend fun probeServerHealth(baseUrl: String, client: OkHttpClient, timeoutMillis: Long = 10_000) {
    val url = baseUrl.toHttpUrl()
    require(url.encodedPath == "/" && url.query == null && url.fragment == null && url.username.isEmpty() && url.password.isEmpty()) { "请输入不含路径或凭据的服务器根地址" }
    withTimeout(timeoutMillis) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val call = client.newCall(Request.Builder().url(url.newBuilder().addPathSegment("health").build()).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        check(it.isSuccessful) { "服务器健康检查失败" }
                        val health = JSONObject(it.body?.string().orEmpty())
                        check(health.optBoolean("ok")) { "服务器健康检查失败" }
                        check(health.optString("app") == "GetVideo") { "此地址不是 GetVideo 服务，请使用 GetVideo 专用服务器地址" }
                    } }
                    if (continuation.isActive) result.fold({ continuation.resume(Unit) }, { continuation.resumeWithException(it) })
                }
            })
        }
    }
}
