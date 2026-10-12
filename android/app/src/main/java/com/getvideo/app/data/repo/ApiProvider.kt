package com.getvideo.app.data.repo

import android.content.Context
import java.io.File
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.getvideo.app.data.remote.ApiError
import com.getvideo.app.data.remote.GetVideoApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * 按服务器地址缓存 Retrofit 实例。
 *
 * 地址可以在设置里改，所以不能只建一次；但也不该每次调用都新建 —— 那会
 * 丢掉连接池。
 */
object ApiProvider {

    private val json = Json {
        ignoreUnknownKeys = true   // 服务端加字段不该让客户端崩
        coerceInputValues = true
    }

    fun initialize(context: Context) { AccountStore.initialize(context) }

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            // Never record URL paths, query strings, headers, bodies or account identifiers.
            try {
                chain.proceed(chain.request()).also { response ->
                    DiagnosticsStore.event("网络请求", "${chain.request().method} · HTTP ${response.code}", response.isSuccessful)
                }
            } catch (error: java.io.IOException) {
                DiagnosticsStore.event("网络请求", error.javaClass.simpleName, false)
                throw error
            }
        }
        // 提取是同步阻塞的，服务器那边要下载加转码，给足时间
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            // Evaluate every hop, including redirects and audio downloads.
            builder.removeHeader("Authorization")
            builder.removeHeader("X-GetVideo-Session")
            AccountStore.sessionFor(request.url)?.let { builder.header("X-GetVideo-Session", it) }
            chain.proceed(builder.build())
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val cache = mutableMapOf<String, GetVideoApi>()

    @Synchronized
    fun api(baseUrl: String): GetVideoApi = cache.getOrPut(baseUrl) {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GetVideoApi::class.java)
    }

    fun api(baseUrl: String, account: AccountStore.Account): GetVideoApi = Retrofit.Builder()
        .baseUrl(baseUrl).client(okHttp(account))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
        .create(GetVideoApi::class.java)

    fun okHttp(account: AccountStore.Account): OkHttpClient {
        val builder = client.newBuilder()
        builder.networkInterceptors().clear()
        return builder.addNetworkInterceptor { chain ->
            val request = chain.request()
            val updated = request.newBuilder().removeHeader("Authorization").removeHeader("X-GetVideo-Session")
            if (isCredentialOrigin(request.url, account.serverUrl.toHttpUrl())) {
                updated.header("X-GetVideo-Session", account.token)
            }
            chain.proceed(updated.build())
        }.build()
    }

    fun okHttp(): OkHttpClient = client

    /**
     * 把 HTTP 错误翻成能直接显示的中文。
     *
     * 服务端的状态码是有含义的（401 = 要登录），别在这里丢掉。
     */
    fun humanMessage(t: Throwable): String = when (t) {
        is HttpException -> {
            val body = runCatching {
                t.response()?.errorBody()?.string()?.let { json.decodeFromString<ApiError>(it).detail }
            }.getOrNull()
            when (t.code()) {
                400 -> body ?: "链接解析失败"
                401 -> body ?: "请先登录 GetVideo 账号，或重新登录"
                428 -> body ?: "平台需要重新授权，请到「我的 → 平台授权」操作"
                404 -> body ?: "没找到"
                501 -> body ?: "该平台暂不支持这种登录方式"
                502 -> body ?: "平台侧提取失败，稍后再试"
                else -> body ?: "服务器错误（${t.code()}）"
            }
        }
        is java.net.SocketTimeoutException -> "请求超时，请检查网络或稍后重试"
        is java.net.ConnectException -> if (t.message.orEmpty().contains("127.0.0.1"))
            "tx 调试通道未连接，请连接 Mac 并恢复 USB 转发，或在服务器设置中填写手机可访问的地址"
        else "无法连接服务器，请检查服务器地址和服务状态"
        else -> t.message ?: "未知错误"
    }
}
