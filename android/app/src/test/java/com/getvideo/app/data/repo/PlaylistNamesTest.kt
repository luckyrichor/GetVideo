package com.getvideo.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistNamesTest {
    @Test fun emptyNameGetsFirstDefault() {
        assertEquals("列表1", playlistName("  ", emptyList()))
    }
    @Test fun defaultContinuesAfterLargestNumber() {
        assertEquals("列表4", playlistName("", listOf("列表1", "列表3", "收藏")))
    }
    @Test fun explicitNameIsTrimmedAndPreserved() {
        assertEquals("睡前", playlistName(" 睡前 ", listOf("列表1")))
    }
}
