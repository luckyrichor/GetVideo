package com.getvideo.app.data.repo

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 波形缓存。
 *
 * 波形靠解码整条音轨算出来，长文件要几秒。第一次进剪辑页是「边解边画」，
 * 算完存一份到 `files/waveforms/<id>.bin`，之后再打开同一个文件直接读缓存，
 * 不用再等一次全曲解码。
 *
 * 文件格式：4 字节 bucket 数量（小端）+ 每个 bucket 一个 float。
 * bucket 数量对不上就当缓存失效，重新算。
 */
object WaveformCache {

    private fun dir(context: Context): File = File(context.filesDir, "waveforms")

    private fun file(context: Context, trackId: Long): File = File(dir(context), "$trackId.bin")

    fun read(context: Context, trackId: Long, buckets: Int): FloatArray? = runCatching {
        val target = file(context, trackId)
        if (!target.exists() || target.length() <= 0) return null
        val bytes = target.readBytes()
        if (bytes.size != 4 + buckets * 4) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != buckets) return null
        FloatArray(buckets) { buf.float }
    }.getOrNull()

    fun write(context: Context, trackId: Long, peaks: FloatArray) = runCatching {
        val target = file(context, trackId)
        target.parentFile?.mkdirs()
        val buf = ByteBuffer.allocate(4 + peaks.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(peaks.size)
        peaks.forEach { buf.putFloat(it) }
        // 先写临时文件再改名，避免写一半被杀留下半个坏缓存。
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(buf.array())
        if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
        tmp.delete()
    }

    /** 文件被覆盖或删除时清掉缓存：内容变了，旧波形不能再用。 */
    fun forget(context: Context, trackId: Long) {
        runCatching { file(context, trackId).delete() }
    }
}
