package com.getvideo.app.data.repo

import org.junit.Assert.*
import org.junit.Test

class PendingNoticeTest {
    @Test fun consumedNoticeIsNotRepeatedOnPageReturn() {
        val notice = PendingNotice<String>()
        notice.post("已加入视频库")
        assertEquals("已加入视频库", notice.take())
        assertNull(notice.take())
        notice.post("第二次提取成功")
        assertEquals("第二次提取成功", notice.take())
    }
}
