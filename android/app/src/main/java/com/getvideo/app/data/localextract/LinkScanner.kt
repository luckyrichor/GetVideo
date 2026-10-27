package com.getvideo.app.data.localextract

/**
 * 从一段分享文本里扫出全部链接。
 *
 * 分享文案长这样，链接前后通常紧贴中文、全角标点、emoji：
 * 「3.58 复制打开抖音，看看【某某】https://v.douyin.com/iRxxxx/ 复制此链接…」
 * 「【标题】 https://www.bilibili.com/video/BV1xx411c7mD?p=2&t=3，快来看」
 *
 * 规则：
 * 1. 起点可以是带协议的 http(s)://，也可以是没有协议的短链域名（b23.tv、v.douyin.com），
 *    以及裸的 BV 号。
 * 2. 从起点开始按“最长匹配”向后吃，只接受 URL 合法字符；一旦遇到空白、中文、全角标点、
 *    引号、emoji 立刻停止——这样既不会把后面的中文吃进来，也不会把紧邻的下一个链接粘在一起。
 * 3. 尾部再修剪一次标点（英文逗号句号、右括号等），但保留短链结尾的斜杠。
 * 4. 按规范化后的地址去重，保留第一次出现的顺序。
 */
object LinkScanner {

    data class Found(
        val url: String,
        val platform: String,
        val key: String,
    ) {
        val label: String get() = if (platform == "bilibili") "B站" else "抖音"
    }

    /** URL 主体允许的 ASCII 字符；出现其它字符即视为链接结束。 */
    private fun isUrlChar(c: Char): Boolean = when {
        c.code > 0x7F -> false
        c.isLetterOrDigit() -> true
        c in "-._~:/?#[]@!$&'()*+,;=%" -> true
        else -> false
    }

    private val bareHosts = listOf(
        "b23.tv/", "bili2233.cn/", "v.douyin.com/", "v.iesdouyin.com/",
        "www.bilibili.com/video/", "bilibili.com/video/",
        "m.bilibili.com/video/", "www.douyin.com/video/", "douyin.com/video/",
        "www.iesdouyin.com/share/video/", "www.douyin.com/note/",
    )

    fun scan(text: String): List<Found> {
        val found = mutableListOf<Found>()
        val lowered = text.lowercase()
        var index = 0
        while (index < text.length) {
            val start = nextStart(lowered, text, index) ?: break
            val (urlStart, hasScheme) = start
            var end = urlStart
            while (end < text.length && isUrlChar(text[end])) end++
            var raw = text.substring(urlStart, end).trimEnd('.', ',', ';', ':', '!', '?', '\'', '"', ')', ']', '}')
            if (!hasScheme) raw = "https://$raw"
            // 太短的片段（比如文本里只出现了域名）忽略，避免误识别。
            if (raw.length > 12) {
                val normalized = normalize(raw)
                val info = identify(normalized)
                if (info != null) {
                    val entry = Found(normalized, info.first, info.second)
                    if (found.none { it.url == entry.url || (it.platform == entry.platform && it.key == entry.key) }) {
                        found.add(entry)
                    }
                }
            }
            index = end.coerceAtLeast(urlStart + 1)
        }
        // 纯 BV 号（没有链接）也接受，放在最后补充。
        Regex("BV[0-9A-Za-z]{10}").findAll(text).forEach { match ->
            val key = match.value
            if (found.none { it.platform == "bilibili" && it.key == key }) {
                found.add(Found("https://www.bilibili.com/video/$key", "bilibili", key))
            }
        }
        return found
    }

    /** 返回 (起点下标, 是否自带协议)；找不到返回 null。 */
    private fun nextStart(lowered: String, text: String, from: Int): Pair<Int, Boolean>? {
        val scheme = lowered.indexOf("http://", from).takeIf { it >= 0 }?.let { it to true }
        val secure = lowered.indexOf("https://", from).takeIf { it >= 0 }?.let { it to true }
        val bare = bareHosts.mapNotNull { host ->
            val at = lowered.indexOf(host, from)
            if (at >= 0) at to false else null
        }.minByOrNull { it.first }
        return listOfNotNull(secure, scheme, bare).minByOrNull { it.first }
    }

    /** 去掉 fragment，统一 https，去掉末尾多余的斜杠（根路径除外）。 */
    private fun normalize(url: String): String {
        var value = url.trim()
        if (value.startsWith("http://")) value = "https://" + value.removePrefix("http://")
        value = value.substringBefore('#')
        while (value.length > 8 && value.endsWith("//")) value = value.dropLast(1)
        return value.trimEnd('.', ',', ';', ':', '!', '?')
    }

    /** 判断平台并取出作品编号；不是支持的平台就返回 null。 */
    private fun identify(url: String): Pair<String, String>? {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return null
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
        val query = runCatching { java.net.URI(url).query.orEmpty() }.getOrDefault("")
        return when {
            host.endsWith("bilibili.com") || host == "b23.tv" || host == "bili2233.cn" -> {
                val key = Regex("BV[0-9A-Za-z]{10}").find(path)?.value
                    ?: Regex("av\\d+", RegexOption.IGNORE_CASE).find(path)?.value
                    ?: shortKey(path)
                Pair("bilibili", key)
            }
            host.endsWith("douyin.com") || host.endsWith("iesdouyin.com") -> {
                val key = Regex("(video|note)/(\\d+)").find(path)?.groupValues?.get(2)
                    ?: Regex("modal_id=(\\d+)").find(query)?.groupValues?.get(1)
                    ?: shortKey(path)
                Pair("douyin", key)
            }
            else -> null
        }
    }

    /**
     * 短链（b23.tv/xxxx、v.douyin.com/xxxx）没有作品号，用路径末段当编号。
     * 不能用固定的「短链」二字，否则同平台的多条短链会被当成同一条去重掉。
     */
    private fun shortKey(path: String): String {
        val segment = path.trim('/').substringAfterLast('/').ifBlank { "root" }
        return "短链:$segment"
    }
}
