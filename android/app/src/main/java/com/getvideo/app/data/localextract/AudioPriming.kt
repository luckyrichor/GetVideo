package com.getvideo.app.data.localextract

/** A negative timestamp alone is not proof that encoded audio is disposable. */
internal fun hasDeclaredAudioPreroll(firstTimeUs: Long, encoderDelaySamples: Int): Boolean =
    firstTimeUs < 0 && encoderDelaySamples > 0
