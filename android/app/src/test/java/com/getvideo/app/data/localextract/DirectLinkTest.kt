package com.getvideo.app.data.localextract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirectLinkTest {
    @Test fun sharedDouyinText() {
        val raw = DirectLinkParser.find("复制打开抖音 https://www.douyin.com/video/7234567890123456789 复制此链接")
        val link = DirectLinkParser.parse(raw)
        assertEquals("douyin", link.platform)
        assertEquals("7234567890123456789", link.videoId)
        assertEquals("https://www.douyin.com/video/7234567890123456789", link.url)
    }

    @Test fun biliPartsHaveIndependentIdentity() {
        val first = DirectLinkParser.parse("https://www.bilibili.com/video/BV1GJ411x7h7?p=1")
        val second = DirectLinkParser.parse("https://www.bilibili.com/video/BV1GJ411x7h7?p=2")
        assertEquals("BV1GJ411x7h7", first.key)
        assertEquals("BV1GJ411x7h7-p2", second.key)
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7?p=2", second.url)
    }

    @Test fun invalidPageIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DirectLinkParser.parse("https://www.bilibili.com/video/BV1GJ411x7h7?p=0")
        }
    }
}
