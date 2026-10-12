package com.getvideo.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRulesTest {
    @Test fun singleTrackRepeatsInsteadOfEnding() {
        assertEquals(1, playbackRepeatMode(PlayerHolder.Mode.SINGLE, false))
    }
    @Test fun sleepTimerOverridesLoopToFinishCurrentTrack() {
        assertEquals(0, playbackRepeatMode(PlayerHolder.Mode.SINGLE, true))
        assertEquals(0, playbackRepeatMode(PlayerHolder.Mode.LOOP, true))
    }
    @Test fun cancellingSleepTimerRestoresSelectedLoop() {
        assertEquals(0, playbackRepeatMode(PlayerHolder.Mode.LOOP, true))
        assertEquals(2, playbackRepeatMode(PlayerHolder.Mode.LOOP, false))
    }
    @Test fun orderedAndShuffleStopAtEndOfList() {
        assertEquals(0, playbackRepeatMode(PlayerHolder.Mode.ORDERED, false))
        assertEquals(0, playbackRepeatMode(PlayerHolder.Mode.SHUFFLE, false))
    }
}
