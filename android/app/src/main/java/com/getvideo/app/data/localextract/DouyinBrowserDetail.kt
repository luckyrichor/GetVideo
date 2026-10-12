package com.getvideo.app.data.localextract

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Cookie
import kotlin.coroutines.resume

/** Retry Douyin's public detail request in the phone's browser engine when HTTP is blocked. */
internal object DouyinBrowserDetail {
    suspend fun fetch(
        context: Context, videoId: String, apiUrl: String, userAgent: String,
        cookies: List<Cookie>, report: suspend (String) -> Unit,
    ): JSONObject =
        withContext(Dispatchers.Main) {
            val pageUrl = "https://www.douyin.com/video/$videoId"
            val webView = WebView(context)
            try {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookies.forEach { cookie ->
                    suspendCancellableCoroutine<Unit> { continuation ->
                        cookieManager.setCookie(pageUrl, cookie.toString()) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                }
                cookieManager.flush()
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = userAgent
                webView.settings.allowFileAccess = false
                webView.settings.allowContentAccess = false
                var loaded = false
                var pageError: Int? = null
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (url?.startsWith("https://www.douyin.com/") == true) loaded = true
                    }
                    override fun onReceivedHttpError(
                        view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) pageError = errorResponse?.statusCode
                    }
                }
                webView.loadUrl(pageUrl)
                withTimeout<JSONObject>(30_000) {
                    while (!loaded) delay(250)
                    report("浏览器页面：已加载${pageError?.let { "，主页面 HTTP $it" } ?: ""}")
                    // Let the page establish its browser cookies before asking for the detail.
                    delay(1_500)
                    webView.eval("""
                        window.__getvideo_result = null;
                        fetch(${JSONObject.quote(apiUrl)}, {credentials: 'include', headers: {Accept: 'application/json, text/plain, */*'}})
                          .then(async response => { window.__getvideo_result = {status: response.status, body: await response.text()}; })
                          .catch(error => { window.__getvideo_result = {status: 0, body: String(error)}; });
                    """.trimIndent())
                    var detail: JSONObject? = null
                    while (detail == null) {
                        delay(500)
                        val raw = webView.eval("JSON.stringify(window.__getvideo_result)")
                        if (raw == "null" || raw == "undefined") continue
                        val encoded = JSONArray("[$raw]").getString(0)
                        if (encoded == "null") continue
                        val result = JSONObject(encoded)
                        report("浏览器作品详情：HTTP ${result.optInt("status")}，响应 ${result.optString("body").length} 字符")
                        require(result.optInt("status") == 200) {
                            "抖音浏览器请求作品详情失败：HTTP ${result.optInt("status")}."
                        }
                        detail = JSONObject(result.getString("body"))
                    }
                    detail
                }
            } finally {
                webView.stopLoading()
                webView.destroy()
            }
        }

    private suspend fun WebView.eval(script: String): String = suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { result ->
            if (continuation.isActive) continuation.resume(result)
        }
    }
}
