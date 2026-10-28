package com.getvideo.app.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.getvideo.app.data.local.TrackEntity
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地封面缓存。
 *
 * 封面本来用平台返回的 `coverUrl`，但它有两个问题：老版本入库的视频没有这个地址，
 * 而且平台地址会过期。本地有视频文件时直接抽一帧存成封面，
 * 封面就一直在，也不吃流量。
 */
object CoverCache {

    fun file(context: Context, trackId: Long): File = File(context.filesDir, "covers/$trackId.jpg")

    /** 已缓存就直接用；否则从本地视频抽一帧。抽不出来返回 null，界面走占位图。 */
    suspend fun poster(context: Context, track: TrackEntity): File? = withContext(Dispatchers.IO) {
        val target = file(context, track.id)
        if (target.exists() && target.length() > 0) return@withContext target
        val path = track.localPath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
            ?: return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path.absolutePath)
            // 取第 1 秒的帧：避开片头黑场，也不会抽到片尾。
            val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return@withContext null
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out -> frame.compress(Bitmap.CompressFormat.JPEG, 82, out) }
            frame.recycle()
            target.takeIf { it.length() > 0 }
        } catch (_: Exception) {
            target.delete()
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 视频被删除时清掉对应封面，别在私有目录里堆垃圾。 */
    fun forget(context: Context, trackId: Long) {
        runCatching { file(context, trackId).delete() }
    }
}
