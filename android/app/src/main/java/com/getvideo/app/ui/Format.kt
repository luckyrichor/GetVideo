package com.getvideo.app.ui

/** 秒 → m:ss。时长可能缺失，缺就显示占位。 */
fun formatDuration(sec: Double?): String {
    if (sec == null || sec <= 0) return "--:--"
    val total = sec.toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

fun formatSize(bytes: Long?): String = when {
    bytes == null || bytes <= 0 -> "-"
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%d KB".format(bytes / 1024)
}

fun platformLabel(platform: String): String = when (platform) {
    "bilibili" -> "B站"
    "douyin" -> "抖音"
    else -> platform
}
