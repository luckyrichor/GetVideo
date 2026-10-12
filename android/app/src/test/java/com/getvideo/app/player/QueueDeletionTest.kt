package com.getvideo.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueDeletionTest {
    @Test fun removesAdjacentAndRepeatedEntriesWithoutRemovingSurvivors() {
        val queue = mutableListOf(10L, 11L, 12L, 10L, 13L)
        queueRemovalIndices(queue, setOf(10L, 11L)).forEach { queue.removeAt(it) }
        assertEquals(listOf(12L, 13L), queue)
    }
    @Test fun deletingCurrentLastEntryEmptiesQueue() {
        val queue = mutableListOf(10L)
        queueRemovalIndices(queue, setOf(10L)).forEach { queue.removeAt(it) }
        assertEquals(emptyList<Long>(), queue)
    }
    @Test fun unrelatedDeletionKeepsAllEntries() {
        val queue = mutableListOf(10L, 11L)
        queueRemovalIndices(queue, setOf(99L)).forEach { queue.removeAt(it) }
        assertEquals(listOf(10L, 11L), queue)
    }
}
