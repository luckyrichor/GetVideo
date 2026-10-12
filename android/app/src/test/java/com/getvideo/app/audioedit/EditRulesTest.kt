package com.getvideo.app.audioedit

import org.junit.Assert.*
import org.junit.Test

class EditRulesTest {
    @Test fun sortsSeparatedClipsAndCalculatesRetainedDuration() {
        val clips = validatedSegments(listOf(AudioSegment(120000, 160000), AudioSegment(20000, 50000)), 300000)
        assertEquals(AudioSegment(20000, 50000), clips.first())
        assertEquals(70000L, clips.sumOf { it.endMs - it.startMs })
    }
    @Test fun allowsAdjacentClips() { assertEquals(2, validatedSegments(listOf(AudioSegment(0, 10), AudioSegment(10, 20)), 20).size) }
    @Test fun rejectsOverlapsAndInvalidBounds() {
        listOf(emptyList(), listOf(AudioSegment(-1, 5)), listOf(AudioSegment(5, 5)), listOf(AudioSegment(0, 21)),
            listOf(AudioSegment(0, 15), AudioSegment(10, 20))).forEach {
            assertTrue(runCatching { validatedSegments(it, 20) }.isFailure)
        }
    }
    @Test fun acceptsMillisecondsAndLongAudio() {
        assertEquals(125025L, parseEditTime("02:05.025"))
        assertEquals(125025L, parseEditTime("125.025"))
        assertEquals(3600000L, parseEditTime("60:00"))
        assertEquals("02:05.025", editTime(125025))
    }
    @Test fun rejectsNonFiniteAndInvalidTime() {
        listOf("NaN", "Infinity", "-1", "02:60", "-2:01", "1:2:3", "bad").forEach { assertNull(parseEditTime(it)) }
    }
}
