package com.getvideo.app.data.localextract

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Copy compressed picture and soundtrack samples into one MP4, including silent videos. */
internal object VideoMuxer {
    fun remux(video: File, output: File, audio: File? = null) {
        val readers = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            data class Track(val reader: MediaExtractor, val input: Int, val format: MediaFormat, var output: Int = -1)
            fun open(file: File): MediaExtractor = MediaExtractor().also { readers += it; it.setDataSource(file.absolutePath) }
            val picture = open(video)
            val tracks = mutableListOf<Track>()
            val videoIndex = (0 until picture.trackCount).firstOrNull {
                picture.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("下载内容没有视频画面")
            tracks += Track(picture, videoIndex, picture.getTrackFormat(videoIndex))
            val sound = audio?.let(::open) ?: picture
            val audioIndex = (0 until sound.trackCount).firstOrNull {
                sound.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            require(audio == null || audioIndex != null) { "B 站声音流中没有音轨" }
            if (audioIndex != null) tracks += Track(sound, audioIndex, sound.getTrackFormat(audioIndex))
            var firstTime = 0L
            tracks.forEach {
                it.reader.selectTrack(it.input)
                require(it.reader.sampleTime != -1L) { "视频或声音流为空" }
                firstTime = minOf(firstTime, it.reader.sampleTime)
                it.reader.unselectTrack(it.input)
            }
            val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val rotation = MediaMetadataRetriever().let { metadata ->
                try { metadata.setDataSource(video.absolutePath); metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0 }
                finally { metadata.release() }
            }
            if (rotation in listOf(0, 90, 180, 270)) writer.setOrientationHint(rotation)
            tracks.forEach { it.output = writer.addTrack(it.format); it.reader.selectTrack(it.input) }
            writer.start(); started = true
            val bufferSize = maxOf(8 * 1024 * 1024, tracks.maxOf { if (it.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0 })
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()
            val byReader = tracks.groupBy { it.reader }
            val counts = IntArray(tracks.size)
            // Interleave the separate DASH sources by timestamp, preserving one common clock offset.
            while (true) {
                val reader = byReader.keys.filter { it.sampleTrackIndex >= 0 }.minByOrNull { it.sampleTime } ?: break
                val track = byReader.getValue(reader).first { it.input == reader.sampleTrackIndex }
                buffer.clear()
                val size = reader.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, reader.sampleTime - firstTime, muxerSampleFlags(reader.sampleFlags))
                writer.writeSampleData(track.output, buffer, info)
                counts[tracks.indexOf(track)]++
                reader.advance()
            }
            require(counts.all { it > 0 }) { "视频或声音轨没有有效样本" }
            writer.stop(); started = false
            require(output.length() > 1024) { "视频封装失败" }
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally {
            if (started) runCatching { muxer?.stop() }
            muxer?.release()
            readers.forEach { it.release() }
        }
    }
}
