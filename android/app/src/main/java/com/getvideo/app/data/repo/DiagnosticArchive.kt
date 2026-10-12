package com.getvideo.app.data.repo

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** One private file per operation. Successful operations are retained but excluded from failure exports. */
internal class DiagnosticArchive(private val directory: File) {
    init {
        directory.mkdirs()
        directory.listFiles().orEmpty().filter { it.name.endsWith(".running.json") }.forEach { file ->
            check(file.renameTo(File(directory, file.name.replace(".running.json", ".interrupted.json"))))
        }
    }

    private var failuresCount = directory.listFiles().orEmpty().count { isFailure(it) }

    @Synchronized fun begin(kind: String, version: String, id: String = UUID.randomUUID().toString()): String {
        write(file(id), JSONObject().put("time", System.currentTimeMillis()).put("kind", kind)
            .put("version", version).put("status", "running").put("lines", JSONArray()))
        return id
    }

    @Synchronized fun record(id: String, message: String) {
        val file = file(id)
        val entry = JSONObject(file.readText())
        entry.getJSONArray("lines").put(sanitize(message))
        write(file, entry)
    }

    @Synchronized fun finish(id: String, success: Boolean) {
        val file = file(id)
        val status = if (success) "success" else "failed"
        write(file, JSONObject(file.readText()).put("status", status))
        check(file.renameTo(File(directory, "$id.$status.json"))) { "无法完成诊断记录" }
        if (!success) failuresCount++
    }

    @Synchronized fun failureCount(): Int = failuresCount
    @Synchronized fun exportFailures(): String = failures().joinToString("\n\n") { (_, entry) ->
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(entry.getLong("time")))
        val state = if (entry.optString("status") == "running" || entry.optString("status") == "interrupted") "中断" else "失败"
        val lines = entry.getJSONArray("lines")
        "[$time] GetVideo ${entry.optString("version")} · ${entry.optString("kind")} · $state\n" +
            (0 until lines.length()).joinToString("\n") { lines.getString(it) }
    }

    private fun isFailure(file: File) = file.name.endsWith(".failed.json") || file.name.endsWith(".interrupted.json")
    private fun failures() = directory.listFiles().orEmpty().filter(::isFailure).mapNotNull { file ->
        runCatching { file to JSONObject(file.readText()) }.getOrNull()
    }.sortedBy { it.second.optLong("time") }
    private fun file(id: String) = File(directory, "$id.running.json")
    private fun write(file: File, entry: JSONObject) {
        val staged = File(file.path + ".new")
        try {
            staged.writeText(entry.toString())
            staged.setReadable(false, false); staged.setWritable(false, false)
            staged.setReadable(true, true); staged.setWritable(true, true)
            check(staged.renameTo(file)) { "无法保存诊断记录" }
        } finally { staged.delete() }
    }
    private fun sanitize(message: String): String {
        if (Regex("(?i)cookie|authorization|password|密码|token|session|api[_-]?key|secret|凭据").containsMatchIn(message)) return "[敏感内容已省略]"
        return message.replace(Regex("https?://\\S+"), "[地址已省略]")
    }
}
