package com.getvideo.app.data.localextract

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Keep selection independent of Android so platform response fixtures can be tested. */
internal object VideoSources {
    data class Streams(val video: List<String>, val audio: List<String>)
    private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else
        (0 until array.length()).mapNotNull { array.optString(it).takeIf { url -> url.startsWith("http") } }
    private fun urls(obj: JSONObject?): List<String> = strings(obj?.optJSONArray("url_list"))
    private fun dashUrls(obj: JSONObject): List<String> =
        (listOfNotNull(obj.optString("baseUrl").ifBlank { obj.optString("base_url") }.takeIf { it.startsWith("http") }) +
            strings(obj.optJSONArray("backupUrl") ?: obj.optJSONArray("backup_url"))).distinct()

    fun douyin(detail: JSONObject): List<String> {
        val video = detail.optJSONObject("video")
        val playback = (listOf("play_addr_h264", "play_addr", "play_addr_265")
            .flatMap { urls(video?.optJSONObject(it)) } +
            (video?.optJSONArray("bit_rate")?.let { rates ->
                (0 until rates.length()).flatMap { urls(rates.optJSONObject(it)?.optJSONObject("play_addr")) }
            } ?: emptyList()))
        val selected = (playback.map(::cleanDouyinPlayback) + urls(video?.optJSONObject("download_addr"))).distinct()
        require(selected.isNotEmpty()) { "抖音没有返回视频地址，音乐不能替代视频" }
        return selected
    }

    private fun cleanDouyinPlayback(raw: String): String {
        val url = raw.toHttpUrlOrNull() ?: return raw
        val host = url.host
        if (listOf("snssdk.com", "douyin.com", "iesdouyin.com", "amemv.com").none { host == it || host.endsWith(".$it") }) return raw
        if (url.encodedPath != "/aweme/v1/playwm/") return raw
        return url.newBuilder().encodedPath("/aweme/v1/play/").build().toString()
    }

    fun bilibili(dash: JSONObject): Streams {
        fun tracks(key: String): List<JSONObject> = dash.optJSONArray(key)?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.filter { dashUrls(it).isNotEmpty() }
        } ?: emptyList()
        val videos = tracks("video")
        val avc = videos.filter { it.optInt("codecid") == 7 || it.optString("codecs").startsWith("avc") }
        val selected = (avc.ifEmpty { videos }).maxWithOrNull(compareBy<JSONObject> { it.optInt("id") }.thenBy { it.optLong("bandwidth") })
            ?: error("B 站没有返回视频画面")
        val audio = tracks("audio").maxByOrNull { it.optLong("bandwidth") }
        return Streams(dashUrls(selected), audio?.let(::dashUrls) ?: emptyList())
    }
}
