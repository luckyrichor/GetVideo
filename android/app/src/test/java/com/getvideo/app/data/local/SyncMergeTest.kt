package com.getvideo.app.data.local

import org.junit.Assert.*
import org.junit.Test

class SyncMergeTest {
    // 用命名参数：TrackEntity 以后再插字段也不会把这里的位置参数挤错位。
    private val server = TrackEntity(
        id = 1,
        platform = "bilibili",
        videoId = "BV1",
        title = "原版",
        uploader = null,
        durationSec = 300.0,
        audioBytes = 1000L,
        audioFormat = "m4a",
        sourceUrl = "",
        downloadUrl = "/audio/1",
        updatedSeq = 10,
        audioSha256 = "original",
    )
    @Test fun serverChangesCannotOverwriteLocalEdit() {
        val edited = server.copy(title = "剪辑版", durationSec = 70.0, audioBytes = 250, audioSha256 = "edit", localPath = "/local/edit.m4a", locallyEdited = true, favorite = true, localCategoryId = 4)
        assertEquals(edited.copy(updatedSeq = 11), mergeServerTrack(server.copy(updatedSeq = 11, audioSha256 = "newServerFile"), edited, 100))
    }
    @Test fun normalUpdatesInvalidateChangedFileButKeepPersonalState() {
        val local = server.copy(localPath = "/old", favorite = true, localTitleOverride = "自定义", title = "自定义", addedAtMs = 20)
        val merged = mergeServerTrack(server.copy(audioSha256 = "changed"), local, 100)
        assertNull(merged.localPath); assertTrue(merged.favorite); assertEquals("自定义", merged.title); assertEquals(20L, merged.addedAtMs)
    }
    @Test fun unchangedServerFilePreservesDownload() {
        assertEquals("/old", mergeServerTrack(server, server.copy(localPath = "/old"), 100).localPath)
    }
}
