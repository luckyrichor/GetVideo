package com.getvideo.app.data.repo

internal fun playlistName(input: String, existingNames: List<String>): String {
    if (input.isNotBlank()) return input.trim()
    val largest = existingNames.mapNotNull { Regex("列表([0-9]+)").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }.maxOrNull() ?: 0L
    return "列表${largest + 1}"
}
