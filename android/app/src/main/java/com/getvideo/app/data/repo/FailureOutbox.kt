package com.getvideo.app.data.repo

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal fun failureLog(line: String): String {
    if (Regex("(?i)cookie|authorization|password|密码|token|session|api[_-]?key|secret|凭据").containsMatchIn(line))
        return "[敏感内容已省略]"
    return line.replace(Regex("https?://\\S+"), "[地址已省略]").take(1000)
}
internal fun failureShare(text: String): String = text.replace(
    Regex("(?im)((?:cookie|authorization|password|密码|token|session|api[_-]?key|secret)\\s*[:=]\\s*)[^\\r\\n]*"),
    "$1[已省略]").replace(Regex("(https?://)[^\\s/@]+:[^\\s/@]+@"), "$1[凭据已省略]@").take(10000)

/** Private atomic outbox, one UUID per extraction failure; deletion only follows server acknowledgement. */
internal class FailureOutbox(private val directory: File) {
    fun enqueue(server: String, owner: String, text: String, version: String, logs: List<String>): String {
        directory.mkdirs()
        val id = UUID.randomUUID().toString()
        val report = JSONObject().put("id", id).put("version", version.take(64))
            .put("occurred_at_ms", System.currentTimeMillis()).put("share_text", failureShare(text).ifBlank { "[空文本]" })
            .put("logs", JSONArray(logs.takeLast(80).map(::failureLog)))
        val record = JSONObject().put("server", server).put("owner", owner).put("report", report)
        val staged = File(directory, "$id.new")
        try {
            staged.writeText(record.toString())
            check(staged.renameTo(File(directory, "$id.json"))) { "无法暂存失败报告" }
        } finally { staged.delete() }
        return id
    }
    fun pending(): List<JSONObject> = directory.listFiles().orEmpty().filter { it.name.endsWith(".json") }
        .sortedBy { it.lastModified() }.mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }
    fun eligible(server: String, owner: String): List<JSONObject> = pending().filter {
        it.optString("server") == server && it.optString("owner").let { saved -> saved.isEmpty() || saved == owner }
    }
    fun defer(id: String) { runCatching { UUID.fromString(id); File(directory, "$id.json").setLastModified(System.currentTimeMillis()) } }
    fun bindOwner(id: String, owner: String): Boolean = synchronized(ownerLock) {
        UUID.fromString(id)
        val file = File(directory, "$id.json")
        if (!file.exists()) return@synchronized false
        val entry = JSONObject(file.readText())
        val existing = entry.optString("owner")
        if (existing.isNotEmpty()) return@synchronized existing == owner
        val staged = File(directory, "$id.owner-new")
        try {
            staged.writeText(entry.put("owner", owner).toString())
            check(staged.renameTo(file))
        } finally { staged.delete() }
        true
    }
    companion object { private val ownerLock = Any() }
    fun acknowledge(id: String) { UUID.fromString(id); File(directory, "$id.json").delete() }
}
