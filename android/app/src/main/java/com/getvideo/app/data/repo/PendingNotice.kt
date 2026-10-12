package com.getvideo.app.data.repo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Consume before presenting: leaving and re-entering the page must not replay a notification. */
internal class PendingNotice<T> {
    var value by mutableStateOf<T?>(null)
        private set
    fun post(value: T) { this.value = value }
    fun take(): T? = value.also { value = null }
}
