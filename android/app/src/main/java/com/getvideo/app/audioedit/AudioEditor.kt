package com.getvideo.app.audioedit

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

object AudioEditor {
    fun duration(file: File): Long = MediaMetadataRetriever().let { reader ->
        try { reader.setDataSource(file.absolutePath); reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("无法读取视频时长") }
        finally { reader.release() }
    }

    /** Decode actual PCM into bounded peak buckets; no complete PCM file is held in memory. */
    suspend fun waveform(file: File, durationMs: Long, buckets: Int = 2400): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return@withContext FloatArray(buckets)
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val peaks = FloatArray(buckets)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastOutput = System.nanoTime()
            while (!outputDone) {
                coroutineContext.ensureActive()
                check(System.nanoTime() - lastOutput < 30_000_000_000L) { "视频解码超时" }
                if (!inputDone) {
                    val input = codec.dequeueInputBuffer(10000)
                    if (input >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(input)!!, 0)
                        if (size < 0) { codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = codec.outputFormat
                    sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2
                    require(encoding == 2 || encoding == 4) { "不支持此视频的 PCM 格式" }
                    floatPcm = encoding == 4
                } else if (output >= 0) {
                    lastOutput = System.nanoTime()
                    val buffer = codec.getOutputBuffer(output)!!
                    buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.order(ByteOrder.LITTLE_ENDIAN)
                    var sample = 0L
                    while (buffer.remaining() >= if (floatPcm) 4 else 2) {
                        val amplitude = if (floatPcm) abs(buffer.float) else abs(buffer.short.toInt()) / 32768f
                        val timeUs = info.presentationTimeUs + sample / channels * 1_000_000L / sampleRate
                        val bucket = (timeUs.toDouble() / (durationMs * 1000.0) * buckets).toInt().coerceIn(0, buckets - 1)
                        if (amplitude.isFinite()) peaks[bucket] = maxOf(peaks[bucket], amplitude)
                        sample++
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(output, false)
                }
            }
            val max = peaks.maxOrNull()?.coerceAtLeast(0.001f) ?: 1f
            FloatArray(buckets) { peaks[it] / max }
        } finally { decoder?.release(); extractor.release() }
    }

    /** Export clips in original order to AAC/M4A, using the same clip definitions as preview. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun export(context: Context, file: File, segments: List<AudioSegment>, durationMs: Long, output: File) = withContext(Dispatchers.Main) {
        val clips = validatedSegments(segments, durationMs).map {
            EditedMediaItem.Builder(clippedItem(file, it)).setRemoveVideo(false).build()
        }
        output.delete()
        suspendCancellableCoroutine<Unit> { continuation ->
            val transformer = Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC).setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                }).build()
            continuation.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { transformer.cancel(); output.delete() } }
            try { transformer.start(Composition.Builder(EditedMediaItemSequence(clips)).build(), output.absolutePath) }
            catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        }
    }

    fun clippedItem(file: File, segment: AudioSegment): MediaItem = MediaItem.Builder().setUri(Uri.fromFile(file))
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(segment.startMs).setEndPositionMs(segment.endMs).build()).build()
}
