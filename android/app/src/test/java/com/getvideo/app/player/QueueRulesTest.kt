package com.getvideo.app.player

import com.getvideo.app.data.local.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueRulesTest {
    // 用命名参数：TrackEntity 以后再插字段也不会把这里的位置参数挤错位。
    private fun track(id: Long, video: String = "$id", hash: String? = null) = TrackEntity(
        id = id,
        platform = "douyin",
        videoId = video,
        title = "视频$id",
        uploader = null,
        durationSec = 30.0,
        audioBytes = 100L,
        audioFormat = "m4a",
        sourceUrl = "",
        downloadUrl = "",
        updatedSeq = 0,
        audioSha256 = hash,
    )
    @Test fun sameIdCannotBeAddedAgainAfterRename() {
        val old = track(1)
        assertEquals(emptyList<TrackEntity>(), newQueueTracks(listOf(old), listOf(old.copy(title = "新名称"))))
    }
    @Test fun repeatedItemsInOneBatchAreDeduplicated() {
        assertEquals(listOf(track(1), track(2)), newQueueTracks(emptyList(), listOf(track(1), track(1), track(2))))
    }
    @Test fun sameFileHashWithDifferentIdsIsDeduplicated() {
        assertEquals(emptyList<TrackEntity>(), newQueueTracks(listOf(track(1, hash = "abc")), listOf(track(2, hash = "ABC"))))
    }
    @Test fun samePlatformSourceIsDeduplicatedWithoutHash() {
        assertEquals(emptyList<TrackEntity>(), newQueueTracks(listOf(track(1, "video")), listOf(track(2, "video"))))
    }
    @Test fun differentFilesWithSameTitleAreKept() {
        val other = track(2).copy(title = "视频1")
        assertEquals(listOf(other), newQueueTracks(listOf(track(1)), listOf(other)))
    }
}
