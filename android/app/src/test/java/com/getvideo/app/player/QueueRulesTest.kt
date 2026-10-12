package com.getvideo.app.player

import com.getvideo.app.data.local.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueRulesTest {
    private fun track(id: Long, video: String = "$id", hash: String? = null) = TrackEntity(
        id, "douyin", video, "视频$id", null, 30.0, 100L, "m4a", "", "", 0, audioSha256 = hash)
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
