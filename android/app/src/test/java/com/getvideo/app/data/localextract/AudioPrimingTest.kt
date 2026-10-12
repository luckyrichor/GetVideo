package com.getvideo.app.data.localextract

import org.junit.Assert.*
import org.junit.Test

class AudioPrimingTest {
    @Test fun zeroOrPositiveStartNeverDropsFrames() {
        assertFalse(hasDeclaredAudioPreroll(0, 2048))
        assertFalse(hasDeclaredAudioPreroll(23000, 2048))
        assertFalse(hasDeclaredAudioPreroll(0, 0))
    }
    @Test fun negativeTimestampAloneIsNotEvidenceOfPriming() {
        assertFalse(hasDeclaredAudioPreroll(-46439, 0))
        assertTrue(hasDeclaredAudioPreroll(-46439, 2048))
    }
}
