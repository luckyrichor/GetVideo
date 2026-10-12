package com.getvideo.app.player

import androidx.media3.common.Player

/** 睡眠定时等待播完时，临时关闭循环；取消后恢复用户选定的方式。 */
internal fun playbackRepeatMode(mode: PlayerHolder.Mode, waitingForTrackEnd: Boolean): Int {
    if (waitingForTrackEnd) return Player.REPEAT_MODE_OFF
    return when (mode) {
        PlayerHolder.Mode.LOOP -> Player.REPEAT_MODE_ALL
        PlayerHolder.Mode.SINGLE -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }
}
