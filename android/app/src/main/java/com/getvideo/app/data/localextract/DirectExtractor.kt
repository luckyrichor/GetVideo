package com.getvideo.app.data.localextract

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import com.getvideo.app.data.local.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Public-link extraction on the phone. No GetVideo server is involved. */
class DirectExtractor(
    private val context: Context,
    private val report: suspend (String) -> Unit = {},
) {
    private val cookies = MemoryCookies()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookies)
        .followRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun extract(text: String): TrackEntity = withContext(Dispatchers.IO) {
        report("开始解析分享内容")
        val link = resolve(text)
        report("已识别平台：${if (link.platform == "douyin") "抖音" else "B 站"}")
        val id = localId(link.platform, link.key)
        val audioDir = File(context.filesDir, "video").apply { mkdirs() }
        val dest = File(audioDir, "$id.mp4")
        val media = when (link.platform) {
            "douyin" -> douyin(link)
            else -> bilibili(link)
        }
        report("已取得媒体信息，可尝试地址 ${media.urls.size} 个")
        val work = File.createTempFile("getvideo-", ".source", context.cacheDir)
        val output = File.createTempFile("getvideo-", ".mp4", context.cacheDir)
        val staged = File(audioDir, "$id.mp4.part")
        val sound = File.createTempFile("getvideo-", ".sound", context.cacheDir)
        try {
            download(media.urls, work, media.referer)
            report("媒体下载完成：${work.length()} 字节")
            if (media.audioUrls.isNotEmpty()) download(media.audioUrls, sound, media.referer)
            VideoMuxer.remux(work, output, sound.takeIf { media.audioUrls.isNotEmpty() })
            val format = "mp4"
            report("视频封装完成：$format")
            val ready = output
            val hash = sha256(ready)
            // Replace only after the complete video has been downloaded and validated.
            ready.copyTo(staged, overwrite = true)
            if (!staged.renameTo(dest)) {
                staged.delete()
                error("无法保存视频")
            }
            report("视频文件已保存：${dest.length()} 字节")
            TrackEntity(
                id = id, platform = link.platform, videoId = link.key,
                title = media.title, uploader = media.uploader,
                durationSec = duration(dest), audioBytes = dest.length(),
                coverUrl = media.coverUrl,
                audioFormat = format,
                sourceUrl = link.url, downloadUrl = "", updatedSeq = System.currentTimeMillis(),
                audioSha256 = hash, localPath = dest.absolutePath,
            )
        } finally {
            work.delete()
            output.delete()
            staged.delete()
            sound.delete()
        }
    }

    private data class Media(
        val title: String, val uploader: String?, val urls: List<String>,
        val referer: String, val audioUrls: List<String> = emptyList(),
        val coverUrl: String? = null,
    )

    private suspend fun resolve(text: String): DirectLink {
        val raw = DirectLinkParser.find(text)
        val rawHost = raw.toHttpUrlOrNull()?.host ?: error("分享链接无效")
        val isShort = rawHost in setOf("b23.tv", "bili2233.cn", "v.douyin.com", "v.iesdouyin.com")
        report("分享地址域名：$rawHost")
        val url = if (isShort) client.newCall(Request.Builder().url(raw)
            .header("User-Agent", BROWSER_UA).get().build()).execute().use {
            report("短链展开：HTTP ${it.code}，最终域名 ${it.request.url.host}")
            if (!it.isSuccessful) throw PlatformHttpException("短链展开", it.code)
            it.request.url.toString()
        } else raw
        return DirectLinkParser.parse(url)
    }

    private suspend fun douyin(link: DirectLink): Media {
        val shareItem = fetchSharePageItem(link)
        if (shareItem != null) {
            report("公开分享页：取得目标作品数据")
            return douyinMedia(shareItem, link, "https://www.iesdouyin.com/")
        }
        report("公开分享页：未取得作品数据，尝试作品详情接口")
        val ua = BROWSER_UA
        // The home page can be blocked even when the detail API is available.
        try {
            get("https://www.douyin.com/", ua = ua, stage = "抖音首页")
            report("抖音首页：请求成功")
        } catch (e: Exception) {
            report("抖音首页：${failureKind(e)}（继续尝试）")
        }
        val token = ByteArray(81).also(SecureRandom()::nextBytes)
        val msToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(token)
        cookies.put("www.douyin.com", "msToken", msToken)
        val body = """{"region":"cn","aid":1768,"needFid":false,"service":"www.ixigua.com","migrate_info":{"ticket":"","source":"node"},"cbUrlProtocol":"https","union":true}"""
        try {
            val request = Request.Builder().url("https://ttwid.bytedance.com/ttwid/union/register/")
                .header("User-Agent", ua).post(body.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                Cookie.parseAll(response.request.url, response.headers)
                    .firstOrNull { it.name == "ttwid" }?.let { cookies.put("www.douyin.com", "ttwid", it.value) }
                report("访客凭证：HTTP ${response.code}，ttwid ${if (cookies.has("ttwid")) "已取得" else "未取得"}")
            }
        } catch (e: Exception) {
            report("访客凭证：${failureKind(e)}（继续尝试）")
        }
        val url = "https://www.douyin.com/aweme/v1/web/aweme/detail/?device_platform=webapp&aid=6383" +
            "&channel=channel_pc_web&pc_client_type=1&version_code=170400&version_name=17.4.0" +
            "&cookie_enabled=true&platform=PC&downlink=10&aweme_id=${link.videoId}"
        val response = try {
            val json = get(url, ua = ua, referer = link.url, stage = "抖音作品详情")
            report("作品详情直连：请求成功，响应 ${json.length} 字符")
            JSONObject(json)
        } catch (e: Exception) {
            report("作品详情直连：${failureKind(e)}")
            if (e is PlatformHttpException && e.code != 403) throw e
            if (e !is PlatformHttpException && e !is org.json.JSONException) throw e
            val browserCookies = cookies.loadForRequest("https://www.douyin.com/".toHttpUrlOrNull()!!)
            val credentialHint = if (browserCookies.none { it.name == "ttwid" }) "；未取得抖音访客凭证" else ""
            report("浏览器重试：开始，ttwid ${if (browserCookies.any { it.name == "ttwid" }) "有" else "无"}")
            val browser = runCatching {
                DouyinBrowserDetail.fetch(context, link.videoId, url, ua, browserCookies, report)
            }
            if (browser.isSuccess) browser.getOrThrow() else {
                report("浏览器重试：${failureKind(browser.exceptionOrNull()!!)}")
                error("抖音作品详情请求失败（${e.message}$credentialHint），浏览器重试也失败：${browser.exceptionOrNull()?.message}")
            }
        }
        report("作品详情内容：status_code=${response.optInt("status_code", -1)}，作品数据${if (response.optJSONObject("aweme_detail") == null) "缺失" else "存在"}")
        val detail = response.optJSONObject("aweme_detail")
            ?: error("抖音没有返回作品信息；可能需要登录，或该作品不可见")
        return douyinMedia(detail, link, "https://www.douyin.com/")
    }

    private suspend fun fetchSharePageItem(link: DirectLink): JSONObject? {
        val url = "https://www.iesdouyin.com/share/video/${link.videoId}/"
        repeat(5) { attempt ->
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build()
                val item = client.newCall(request).execute().use { response ->
                    val html = response.body?.string().orEmpty()
                    val found = if (response.isSuccessful) DouyinSharePageParser.findItem(html, link.videoId) else null
                    report("公开分享页 ${attempt + 1}/5：HTTP ${response.code}，访客 Cookie ${if (cookies.has("ttwid")) "有" else "无"}，作品数据${if (found == null) "无" else "有"}")
                    found
                }
                if (item != null) return item
            } catch (e: Exception) {
                report("公开分享页 ${attempt + 1}/5：${failureKind(e)}")
            }
            if (attempt < 4) delay(300)
        }
        return null
    }

    private suspend fun douyinMedia(detail: JSONObject, link: DirectLink, referer: String): Media {
        val selected = VideoSources.douyin(detail)
        report("视频候选：${selected.size} 个；保留完整画面和声音")
        val title = detail.optString("desc").take(60).ifBlank { "抖音 ${link.videoId}" }
        val video = detail.optJSONObject("video")
        val cover = video?.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0)?.takeIf(String::isNotBlank)
            ?: video?.optJSONObject("origin_cover")?.optJSONArray("url_list")?.optString(0)?.takeIf(String::isNotBlank)
        return Media(title, detail.optJSONObject("author")?.optString("nickname"),
            selected.distinct(), referer, coverUrl = cover)
    }

    private suspend fun bilibili(link: DirectLink): Media {
        // 首页在部分网络出口会直接被风控拦成 412；它只用于攒 Cookie，失败不影响后续接口。
        val warmed = runCatching {
            client.newCall(
                Request.Builder().url("https://www.bilibili.com/")
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .build()
            ).execute().use { it.code }
        }.getOrNull()
        report("B 站首页：${warmed?.let { "HTTP $it" } ?: "请求失败（继续）"}，访客 Cookie ${if (cookies.has("buvid3")) "有" else "无"}")
        report("B 站：访客凭证 ${if (refreshBiliCredentials(link.url)) "已取得" else "未取得"}")
        report("B 站：开始请求作品、画面与声音信息")
        val param = if (link.videoId.startsWith("av", true)) "aid=${link.videoId.drop(2)}" else "bvid=${link.videoId}"
        val view = JSONObject(biliGet("https://api.bilibili.com/x/web-interface/view?$param", "B 站作品信息", link.url))
        require(view.optInt("code") == 0) { "B 站作品不可访问：${view.optString("message")}" }
        val item = view.getJSONObject("data")
        val pages = item.optJSONArray("pages")
        require(link.page == 1 || (pages != null && link.page <= pages.length())) { "作品没有 P${link.page}" }
        val cid = pages?.optJSONObject(link.page - 1)?.optLong("cid")?.takeIf { it > 0 }
            ?: item.optLong("cid")
        require(cid > 0) { "B 站没有返回视频分 P 编号" }
        val nav = JSONObject(biliGet("https://api.bilibili.com/x/web-interface/nav", "B 站签名密钥", link.url))
        val wbi = nav.getJSONObject("data").getJSONObject("wbi_img")
        val key = mixinKey(wbi.getString("img_url"), wbi.getString("sub_url"))
        val params = sortedMapOf(
            "bvid" to item.optString("bvid", link.videoId), "cid" to cid.toString(),
            "qn" to "80", "fnval" to "4048", "fnver" to "0", "fourk" to "1", "platform" to "pc",
            "wts" to (System.currentTimeMillis() / 1000).toString(),
        )
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val signature = MessageDigest.getInstance("MD5").digest((query + key).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val play = JSONObject(
            biliGet("https://api.bilibili.com/x/player/wbi/playurl?$query&w_rid=$signature", "B 站播放地址", link.url)
        )
        require(play.optInt("code") == 0) { "B 站没有返回播放地址：${play.optString("message")}" }
        val streams = VideoSources.bilibili(play.getJSONObject("data").getJSONObject("dash"))
        val selected = streams.video
        report("B 站：画面 ${selected.size} 个地址，声音 ${streams.audio.size} 个地址")
        val part = pages?.optJSONObject(link.page - 1)?.optString("part").orEmpty()
        val title = item.optString("title").ifBlank { "B 站 ${link.videoId}" } +
            if (link.page > 1) " - P${link.page} $part" else ""
        // 封面可能是协议相对或 http 地址，统一转 https 以便直接加载。
        val cover = item.optString("pic").takeIf(String::isNotBlank)?.let {
            when {
                it.startsWith("//") -> "https:$it"
                it.startsWith("http://") -> "https://" + it.removePrefix("http://")
                else -> it
            }
        }
        return Media(title, item.optJSONObject("owner")?.optString("name"), selected,
            "https://www.bilibili.com/", streams.audio, cover)
    }

    /**
     * 取 buvid3/buvid4 写进 Cookie。缺这两个值时 api.bilibili.com 极易返回 412，
     * 而 spi 接口本身几乎不会被拦，所以它是 B 站链路最可靠的起点。
     */
    private fun refreshBiliCredentials(referer: String?): Boolean {
        val body = runCatching {
            get(
                "https://api.bilibili.com/x/frontend/finger/spi",
                referer = referer, origin = "https://www.bilibili.com", stage = "B 站访客凭证",
            )
        }.getOrNull() ?: return cookies.has("buvid3")
        val data = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull() ?: return cookies.has("buvid3")
        val buvid3 = data.optString("b_3").takeIf(String::isNotBlank)
        val buvid4 = data.optString("b_4").takeIf(String::isNotBlank)
        listOf("api.bilibili.com", "www.bilibili.com").forEach { host ->
            buvid3?.let { cookies.put(host, "buvid3", it) }
            buvid4?.let { cookies.put(host, "buvid4", it) }
        }
        return cookies.has("buvid3")
    }

    /** B 站接口对凭证敏感，412 时刷新一次访客凭证再重试，避免一次风控就整条链路失败。 */
    private suspend fun biliGet(url: String, stage: String, referer: String): String = try {
        get(url, referer = referer, origin = "https://www.bilibili.com", stage = stage)
    } catch (e: PlatformHttpException) {
        if (e.code != 412) throw e
        report("$stage：HTTP 412，刷新访客凭证后重试")
        delay(500)
        refreshBiliCredentials(referer)
        get(url, referer = referer, origin = "https://www.bilibili.com", stage = stage)
    }

    private fun get(
        url: String, ua: String = BROWSER_UA, referer: String? = null, origin: String? = null,
        stage: String = "平台",
    ): String {
        val builder = Request.Builder().url(url).header("User-Agent", ua)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", ACCEPT_LANGUAGE)
        if (referer != null) builder.header("Referer", referer)
        if (origin != null) {
            builder.header("Origin", origin)
                .header("Sec-Fetch-Site", "same-site")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw PlatformHttpException(stage, response.code)
            return response.body?.string() ?: error("平台返回空内容")
        }
    }

    private class PlatformHttpException(stage: String, val code: Int) :
        IllegalStateException("$stage 请求失败：HTTP $code")

    private suspend fun download(urls: List<String>, dest: File, referer: String) {
        var last: Exception? = null
        for ((index, url) in urls.withIndex()) {
            try {
                client.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA)
                    .header("Referer", referer).build()).execute().use { response ->
                    report("媒体下载 ${index + 1}/${urls.size}：HTTP ${response.code}")
                    require(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
                    dest.outputStream().use { out ->
                        response.body?.byteStream()?.copyTo(out) ?: error("下载内容为空")
                    }
                }
                require(dest.length() > 1024) { "下载内容过小" }
                return
            } catch (e: Exception) {
                last = e
                report("媒体下载 ${index + 1}/${urls.size}：${failureKind(e)}")
            }
        }
        throw IllegalStateException("所有下载地址均失败：${last?.message}", last)
    }

    private fun duration(file: File): Double? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDouble()?.div(1000)
        } finally { retriever.release() }
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun urls(obj: JSONObject?): List<String> = strings(obj?.optJSONArray("url_list"))
    private fun failureKind(error: Throwable): String = when (error) {
        is PlatformHttpException -> "HTTP ${error.code}"
        else -> Regex("HTTP (\\d{3})").find(error.message.orEmpty())?.value
            ?: error.javaClass.simpleName
    }
    private fun strings(arr: org.json.JSONArray?): List<String> = if (arr == null) emptyList() else
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.startsWith("http") } }

    private fun mixinKey(img: String, sub: String): String {
        val raw = listOf(img, sub).joinToString("") { it.substringAfterLast('/').substringBefore('.') }
        return MIXIN_TABLE.map { raw[it] }.joinToString("").take(32)
    }

    private class MemoryCookies : CookieJar {
        private val jar = mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { fresh ->
                jar.removeAll { it.name == fresh.name && it.domain == fresh.domain }
                jar.add(fresh)
            }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = jar.filter { it.matches(url) }
        fun put(domain: String, name: String, value: String) {
            // OkHttp 不接受 ".bilibili.com" 这种带点前缀的域，逐个具体 host 写入即可覆盖子域。
            val host = domain.trimStart('.')
            val cookie = Cookie.Builder().name(name).value(value).domain(host).build()
            saveFromResponse("https://$host".toHttpUrlOrNull()!!, listOf(cookie))
        }
        @Synchronized fun has(name: String): Boolean = jar.any { it.name == name }
    }

    companion object {
        private const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8"
        private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private val MIXIN_TABLE = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
        )

        private fun localId(platform: String, key: String): Long {
            val bytes = MessageDigest.getInstance("SHA-256").digest("$platform:$key".toByteArray())
            val positive = ByteBuffer.wrap(bytes).long and Long.MAX_VALUE
            return -(positive.takeIf { it > 0 } ?: 1L)
        }
    }
}
