package com.getvideo.app.data.localextract

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class DirectLink(
    val platform: String, val videoId: String, val url: String, val page: Int = 1,
) {
    val key: String get() = if (platform == "bilibili" && page > 1) "$videoId-p$page" else videoId
}

internal object DirectLinkParser {
    fun find(text: String): String =
        Regex("https?://[^\\s\\u4e00-\\u9fff]+", RegexOption.IGNORE_CASE)
            .find(text)?.value?.trimEnd('.', ',', ';', '!', ')', '）')
            ?: Regex("BV[0-9A-Za-z]{10}").find(text)?.value?.let {
                "https://www.bilibili.com/video/$it"
            } ?: error("没有找到抖音或 B 站链接")

    fun parse(url: String): DirectLink {
        val parsed = url.toHttpUrlOrNull() ?: error("分享链接无效")
        val host = parsed.host
        if (host == "douyin.com" || host.endsWith(".douyin.com") || host.endsWith(".iesdouyin.com")) {
            val id = Regex("/(?:video|note|share/video)/(\\d+)").find(parsed.encodedPath)?.groupValues?.get(1)
                ?: parsed.queryParameter("modal_id")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?: error("抖音链接里没有作品编号")
            return DirectLink("douyin", id, "https://www.douyin.com/video/$id")
        }
        if (host == "bilibili.com" || host.endsWith(".bilibili.com")) {
            val id = Regex("BV[0-9A-Za-z]{10}").find(parsed.encodedPath)?.value
                ?: Regex("av\\d+", RegexOption.IGNORE_CASE).find(parsed.encodedPath)?.value
                ?: error("B 站链接里没有视频编号")
            val pageText = parsed.queryParameter("p")
            val page = if (pageText == null) 1 else pageText.toIntOrNull()
                ?: error("分 P 页码必须是正整数")
            require(page > 0) { "分 P 页码必须是正整数" }
            val canonical = "https://www.bilibili.com/video/$id" + if (page > 1) "?p=$page" else ""
            return DirectLink("bilibili", id, canonical, page)
        }
        error("目前只支持抖音和 B 站分享链接")
    }
}
