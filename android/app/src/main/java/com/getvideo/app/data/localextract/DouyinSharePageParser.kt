package com.getvideo.app.data.localextract

import org.json.JSONObject

/** Extract public item data embedded in the mobile share page's server-rendered router state. */
internal object DouyinSharePageParser {
    fun findItem(html: String, videoId: String): JSONObject? {
        val marker = "window._ROUTER_DATA"
        val start = html.indexOf(marker)
        if (start < 0) return null
        val jsonStart = html.indexOf('{', start + marker.length)
        if (jsonStart < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in jsonStart until html.length) {
            val char = html[index]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
                continue
            }
            when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val root = JSONObject(html.substring(jsonStart, index + 1))
                        val loaders = root.optJSONObject("loaderData") ?: return null
                        val keys = loaders.keys()
                        while (keys.hasNext()) {
                            val data = loaders.optJSONObject(keys.next()) ?: continue
                            val items = data.optJSONObject("videoInfoRes")?.optJSONArray("item_list") ?: continue
                            for (itemIndex in 0 until items.length()) {
                                val item = items.optJSONObject(itemIndex) ?: continue
                                if (item.optString("aweme_id") == videoId) return item
                            }
                        }
                        return null
                    }
                }
            }
        }
        return null
    }
}
