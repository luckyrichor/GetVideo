package com.getvideo.app.data.localextract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DouyinSharePageParserTest {
    private val videoId = "7596662118952473983"

    @Test fun findsTargetInsideNestedRouterState() {
        val html = """
            <script>window._ROUTER_DATA = {"loaderData":{"video_layout":null,
            "video_(id)/page":{"videoInfoRes":{"item_list":[
            {"aweme_id":"other","desc":"wrong"},
            {"aweme_id":"$videoId","desc":"a } and \" quoted title",
             "video":{"play_addr":{"url_list":["https://media.example/video.mp4"]}}}
            ]}}},"errors":null}</script>
        """.trimIndent()
        val item = DouyinSharePageParser.findItem(html, videoId)
        assertEquals(videoId, item?.optString("aweme_id"))
        assertEquals("https://media.example/video.mp4",
            item?.getJSONObject("video")?.getJSONObject("play_addr")
                ?.getJSONArray("url_list")?.getString(0))
    }

    @Test fun emptySharePageDoesNotReturnRecommendedVideo() {
        val html = """<script>window._ROUTER_DATA = {"loaderData":{"video_(id)/page":{"itemId":"$videoId"}}}</script>"""
        assertNull(DouyinSharePageParser.findItem(html, videoId))
    }
}
