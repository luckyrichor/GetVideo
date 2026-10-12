package com.getvideo.app.audioedit

/** Times are in the original audio, sorted chronologically before preview/export. */
data class AudioSegment(val startMs: Long, val endMs: Long)

fun validatedSegments(segments: List<AudioSegment>, durationMs: Long): List<AudioSegment> {
    require(durationMs > 0 && segments.isNotEmpty()) { "请至少保留一个片段" }
    val sorted = segments.sortedBy { it.startMs }
    sorted.forEach { require(it.startMs >= 0 && it.endMs <= durationMs && it.endMs > it.startMs) { "片段时间超出范围或结束不晚于开始" } }
    sorted.zipWithNext().forEach { (a, b) -> require(a.endMs <= b.startMs) { "片段不能重叠" } }
    return sorted
}

fun editTime(ms: Long): String = "%02d:%02d.%03d".format(java.util.Locale.ROOT, ms / 60000, ms / 1000 % 60, ms % 1000)
fun parseEditTime(text: String): Long? {
    val parts = text.trim().split(':')
    if (parts.size !in 1..2) return null
    val seconds = parts.last().toDoubleOrNull() ?: return null
    val minutes = if (parts.size == 2) parts.first().toLongOrNull() ?: return null else 0
    if (!seconds.isFinite() || seconds < 0 || minutes < 0 || (parts.size == 2 && seconds >= 60)) return null
    val value = minutes.toDouble() * 60000 + seconds * 1000
    return value.takeIf { it < Long.MAX_VALUE.toDouble() }?.toLong()
}
