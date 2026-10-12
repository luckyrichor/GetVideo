package com.getvideo.app.data.localextract

import android.media.MediaCodec
import android.media.MediaExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class MuxerSampleFlagsTest {
    @Test fun ordinarySampleDoesNotSignalEndOfStream() {
        assertEquals(0, muxerSampleFlags(0))
    }
    @Test fun syncSampleIsWrittenAsKeyFrame() {
        assertEquals(MediaCodec.BUFFER_FLAG_KEY_FRAME, muxerSampleFlags(MediaExtractor.SAMPLE_FLAG_SYNC))
    }
    @Test(expected = IllegalArgumentException::class)
    fun encryptedSampleIsNotMisidentifiedAsCodecConfig() {
        muxerSampleFlags(MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_SYNC)
    }
    @Test(expected = IllegalArgumentException::class)
    fun partialFrameIsNotMisidentifiedAsEndOfStream() {
        muxerSampleFlags(MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)
    }
    @Test(expected = IllegalArgumentException::class)
    fun unknownFlagsAreNotSilentlyWritten() {
        muxerSampleFlags(8)
    }
    @Test(expected = IllegalArgumentException::class)
    fun syncFlagDoesNotHideIncompleteFrame() {
        muxerSampleFlags(MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME or MediaExtractor.SAMPLE_FLAG_SYNC)
    }
}
