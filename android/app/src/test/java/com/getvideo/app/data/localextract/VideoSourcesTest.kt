package com.getvideo.app.data.localextract

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VideoSourcesTest {
    @Test fun `share page watermark endpoint uses clean playback before download fallback`() {
        val detail = JSONObject("""{"video":{"play_addr":{"url_list":["https://aweme.snssdk.com/aweme/v1/playwm/?video_id=abc&ratio=720p"]},"download_addr":{"url_list":["https://example.com/download.mp4"]},"bit_rate":[{"play_addr":{"url_list":["https://example.com/clean.mp4"]}}]}}""")
        assertEquals(listOf("https://aweme.snssdk.com/aweme/v1/play/?video_id=abc&ratio=720p", "https://example.com/clean.mp4", "https://example.com/download.mp4"), VideoSources.douyin(detail))
    }
    @Test fun `music never substitutes video even when longer`() {
        val detail = JSONObject("""{"music":{"duration":300,"play_url":{"url_list":["https://example.com/music.mp3"]}},"video":{"play_addr":{"url_list":["https://example.com/video.mp4"]}}}""")
        assertEquals(listOf("https://example.com/video.mp4"), VideoSources.douyin(detail))
    }
    @Test(expected = IllegalArgumentException::class) fun `music only is rejected`() {
        VideoSources.douyin(JSONObject("""{"music":{"play_url":{"url_list":["https://example.com/music.mp3"]}}}"""))
    }
    @Test fun `DASH picks compatible picture and separate soundtrack`() {
        val dash = JSONObject("""{"video":[{"id":120,"codecid":12,"baseUrl":"https://example.com/hevc"},{"id":80,"codecid":7,"baseUrl":"https://example.com/avc","backupUrl":["https://example.com/backup"]}],"audio":[{"bandwidth":64,"baseUrl":"https://example.com/a1"},{"bandwidth":192,"baseUrl":"https://example.com/a2"}]}""")
        val sources = VideoSources.bilibili(dash)
        assertEquals(listOf("https://example.com/avc", "https://example.com/backup"), sources.video)
        assertEquals(listOf("https://example.com/a2"), sources.audio)
    }
    @Test fun `silent picture is allowed`() {
        assertTrue(VideoSources.bilibili(JSONObject("""{"video":[{"base_url":"https://example.com/silent"}]}""")).audio.isEmpty())
    }
    @Test(expected = IllegalStateException::class) fun `DASH without picture is rejected`() {
        VideoSources.bilibili(JSONObject("""{"audio":[{"baseUrl":"https://example.com/music"}]}"""))
    }
}
