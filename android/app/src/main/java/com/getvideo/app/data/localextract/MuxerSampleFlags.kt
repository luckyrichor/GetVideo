package com.getvideo.app.data.localextract

import android.media.MediaCodec
import android.media.MediaExtractor

/** MediaExtractor 与 MediaCodec 的位值含义不同，不能直接透传给封装器。 */
internal fun muxerSampleFlags(sampleFlags: Int): Int {
    require(sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
        "手机不支持提取加密视频"
    }
    require(sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME == 0) {
        "视频包含不完整帧，暂不支持直接无损封装"
    }
    require(sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC.inv() == 0) {
        "视频包含不支持的帧标志"
    }
    return if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
        MediaCodec.BUFFER_FLAG_KEY_FRAME
    } else 0
}
