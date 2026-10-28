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
import kotlin.math.floor

object AudioEditor {
    /** 波形采样精度。屏幕上一列约 3dp，放大到 64 倍时也能看清细节。 */
    const val WAVEFORM_BUCKETS = 2400

    fun duration(file: File): Long = MediaMetadataRetriever().let { reader ->
        try { reader.setDataSource(file.absolutePath); reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("无法读取视频时长") }
        finally { reader.release() }
    }

    /**
     * Decode actual PCM into bounded peak buckets; no complete PCM file is held in memory.
     *
     * 长文件的整条音轨要解几秒，不能让它解完才出图，所以这里一边解一边通过 [onProgress]
     * 把当前已经算出来的峰值抛出去（约每 100ms 一次，参数为「归一化后的快照」和「进度 0..1」），
     * 波形因此是从左往右长出来的，不会出现「空白等好几秒」。
     *
     * [onProgress] 在 IO 线程回调，只应该用来更新界面状态，别在里面做重活。
     */
    suspend fun waveform(
        file: File,
        durationMs: Long,
        buckets: Int = WAVEFORM_BUCKETS,
        onProgress: ((FloatArray, Float) -> Unit)? = null,
    ): FloatArray = withContext(Dispatchers.IO) {
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
            var runningMax = 0.001f
            var lastEmit = 0L
            // 内层循环的常量全部提前算好：原来每个样本都要做两次 long 除法（算时间、算 bucket），
            // 一条 5 分钟的音频光这一项就要十几秒。
            var bucketOfUs = buckets / (durationMs * 1000.0)
            var usPerFrame = 1_000_000.0 / sampleRate
            // 一个 bucket 里最多取 400 个采样点求峰值就够画了，长文件靠跳采样把解码后的处理量压下来。
            var stride = ((durationMs * sampleRate / 1000L) / buckets / 400L).toInt().coerceAtLeast(1)
            var usPerStride = usPerFrame * stride
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
                    bucketOfUs = buckets / (durationMs * 1000.0)
                    usPerFrame = 1_000_000.0 / sampleRate
                    stride = ((durationMs * sampleRate / 1000L) / buckets / 400L).toInt().coerceAtLeast(1)
                    usPerStride = usPerFrame * stride
                } else if (output >= 0) {
                    lastOutput = System.nanoTime()
                    val buffer = codec.getOutputBuffer(output)!!
                    buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val bytesPerFrame = (if (floatPcm) 4 else 2) * channels
                    val frames = info.size / bytesPerFrame
                    var frameUs = info.presentationTimeUs.toDouble()
                    if (floatPcm) {
                        val samples = buffer.asFloatBuffer()
                        var frame = 0
                        while (frame < frames) {
                            val base = frame * channels
                            var amplitude = 0f
                            for (c in 0 until channels) {
                                val value = abs(samples.get(base + c))
                                if (value > amplitude) amplitude = value
                            }
                            val bucket = (frameUs * bucketOfUs).toInt()
                            if (bucket in 0 until buckets && amplitude.isFinite() && amplitude > peaks[bucket]) {
                                peaks[bucket] = amplitude
                                if (amplitude > runningMax) runningMax = amplitude
                            }
                            frame += stride
                            frameUs += usPerStride
                        }
                    } else {
                        val samples = buffer.asShortBuffer()
                        var frame = 0
                        while (frame < frames) {
                            val base = frame * channels
                            var peakValue = 0
                            for (c in 0 until channels) {
                                val value = samples.get(base + c).toInt()
                                val magnitude = if (value < 0) -value else value
                                if (magnitude > peakValue) peakValue = magnitude
                            }
                            val amplitude = peakValue * (1f / 32768f)
                            val bucket = (frameUs * bucketOfUs).toInt()
                            if (bucket in 0 until buckets && amplitude > peaks[bucket]) {
                                peaks[bucket] = amplitude
                                if (amplitude > runningMax) runningMax = amplitude
                            }
                            frame += stride
                            frameUs += usPerStride
                        }
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(output, false)
                    if (onProgress != null) {
                        val now = System.currentTimeMillis()
                        if (outputDone || now - lastEmit >= 100) {
                            lastEmit = now
                            val progress = (info.presentationTimeUs.toDouble() / (durationMs * 1000.0)).coerceIn(0.0, 1.0).toFloat()
                            onProgress(normalize(peaks, runningMax), progress)
                        }
                    }
                }
            }
            normalize(peaks, peaks.maxOrNull() ?: 1f)
        } finally { decoder?.release(); extractor.release() }
    }

    /** 把峰值压到 0..1。渐进阶段用「目前见到的最大值」，最终用全局最大值，画面不会乱跳。 */
    private fun normalize(peaks: FloatArray, max: Float): FloatArray {
        val divisor = max.coerceAtLeast(0.001f)
        return FloatArray(peaks.size) { peaks[it] / divisor }
    }

    /**
     * 粗波形：不解整条音轨，只在时间轴上等距跳 [probes] 次，每次 seek 过去解一小段取峰值。
     *
     * 为什么需要它：这台机器上只有软件 AAC 解码器（c2.android.aac.decoder），
     * 解一整条 5 分钟的音轨要十秒左右，这是硬下限，优化循环也压不下来。
     * 所以进剪辑页先用这个把整条形状铺满（5 分钟的文件约 1.8 秒），精确波形再在后面慢慢补。
     *
     * 返回的数组已经归一化，采样点之间做线性插值，看起来是连续的一条包络。
     */
    suspend fun coarseWaveform(
        file: File,
        durationMs: Long,
        buckets: Int = WAVEFORM_BUCKETS,
        probes: Int = 240,
    ): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext FloatArray(buckets)
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val peaks = FloatArray(buckets)
            val probePeaks = FloatArray(probes)
            val info = MediaCodec.BufferInfo()
            for (probe in 0 until probes) {
                coroutineContext.ensureActive()
                val targetUs = ((probe + 0.5) / probes * durationMs * 1000.0).toLong()
                extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()
                var amplitude = 0f
                var got = false
                var guard = 0
                // 每次 seek 之后解一两帧就够取峰值了；解多了又变成整条解码。
                while (!got && guard < 400) {
                    guard++
                    val input = codec.dequeueInputBuffer(0)
                    if (input >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(input)!!, 0)
                        if (size < 0) codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        else {
                            codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    val output = codec.dequeueOutputBuffer(info, 0)
                    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val out = codec.outputFormat
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2
                        floatPcm = encoding == 4
                    } else if (output >= 0) {
                        val buffer = codec.getOutputBuffer(output)!!
                        buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.order(ByteOrder.LITTLE_ENDIAN)
                        val bytesPerFrame = (if (floatPcm) 4 else 2) * channels
                        val frames = info.size / bytesPerFrame
                        if (floatPcm) {
                            val samples = buffer.asFloatBuffer()
                            for (frame in 0 until frames) for (c in 0 until channels) {
                                val value = abs(samples.get(frame * channels + c))
                                if (value.isFinite() && value > amplitude) amplitude = value
                            }
                        } else {
                            val samples = buffer.asShortBuffer()
                            for (frame in 0 until frames) for (c in 0 until channels) {
                                val value = samples.get(frame * channels + c).toInt()
                                val magnitude = if (value < 0) -value else value
                                if (magnitude / 32768f > amplitude) amplitude = magnitude / 32768f
                            }
                        }
                        codec.releaseOutputBuffer(output, false)
                        if (amplitude > 0f) got = true
                    }
                }
                probePeaks[probe] = amplitude
            }
            // 采样点之间线性插值，整条才是连续的包络，不会出现一格一格的空洞。
            for (i in 0 until buckets) {
                val pos = (i + 0.5) / buckets * probes - 0.5
                val left = floor(pos).toInt()
                val frac = (pos - left).toFloat()
                val a = probePeaks[left.coerceIn(0, probes - 1)]
                val b = probePeaks[(left + 1).coerceIn(0, probes - 1)]
                peaks[i] = a + (b - a) * frac
            }
            normalize(peaks, peaks.maxOrNull() ?: 1f)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FloatArray(buckets)
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
