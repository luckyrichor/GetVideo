package com.getvideo.app.data.repo

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DiagnosticsStore {
    var lines by mutableStateOf<List<String>>(emptyList())
        private set
    var failureCount by mutableStateOf(0)
        private set
    private var archive: DiagnosticArchive? = null
    private var current: String? = null
    private val writer = Executors.newSingleThreadExecutor()
    private fun enqueue(block: () -> Unit) { writer.execute { runCatching(block); refresh() } }

    fun initialize(context: Context) {
        val directory = File(context.filesDir, "diagnostics")
        enqueue { if (archive == null) archive = DiagnosticArchive(directory) }
    }
    fun beginLocal(version: String) = begin("手机直提取", version)
    fun beginServer(version: String) = begin("服务器提取", version)
    private fun begin(kind: String, version: String) {
        val id = UUID.randomUUID().toString()
        current = id
        enqueue { archive?.begin(kind, version, id) }
        lines = listOf("GetVideo $version · $kind")
    }
    fun record(startedAt: Long, message: String) {
        val line = "[+${(SystemClock.elapsedRealtime() - startedAt) / 1000}s] $message"
        lines = (lines + line).takeLast(60)
        current?.let { id -> enqueue { archive?.record(id, line) } }
    }
    fun finish(success: Boolean) {
        current?.let { id -> enqueue { archive?.finish(id, success) } }
        current = null
    }
    /** Independently logged operations do not overwrite an extraction in progress. */
    suspend fun <T> operation(kind: String, block: suspend () -> T): T {
        val id = UUID.randomUUID().toString()
        enqueue { archive?.begin(kind, com.getvideo.app.BuildConfig.VERSION_NAME, id) }
        try {
            val result = block()
            enqueue { archive?.record(id, "操作完成"); archive?.finish(id, true) }
            return result
        } catch (error: Exception) {
            val message = "${error.javaClass.simpleName}：${ApiProvider.humanMessage(error)}"
            enqueue { archive?.record(id, message); archive?.finish(id, false) }
            throw error
        }
    }
    fun event(kind: String, message: String, success: Boolean) {
        enqueue {
            archive?.let { store ->
                val id = store.begin(kind, com.getvideo.app.BuildConfig.VERSION_NAME)
                store.record(id, message)
                store.finish(id, success)
            }
        }
    }
    suspend fun exportFailures(): String = withContext(Dispatchers.IO) {
        // Queue behind pending records so a just-finished failure is present in the export.
        writer.submit<String> { archive?.exportFailures().orEmpty() }.get()
    }
    private fun refresh() { failureCount = runCatching { archive?.failureCount() ?: 0 }.getOrDefault(0) }
}
