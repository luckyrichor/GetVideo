package com.getvideo.app.data.repo

import android.content.Context
import android.util.AtomicFile
import com.getvideo.app.data.remote.AgentToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** A completed extraction's receipt survives process death and is safe to resend by action ID. */
class AgentOutbox(private val context: Context) {
    @Serializable
    data class Receipt(val userId: String, val server: String, val actionId: String, val result: AgentToolResult)
    private fun file(user: String, server: String): AtomicFile {
        val scope = MessageDigest.getInstance("SHA-256").digest("$user|$server".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(context.filesDir, "agent-receipt-$scope.enc"))
    }
    fun load(user: String, server: String): Receipt? = runCatching {
        val data = file(user, server).openRead().use { it.readBytes() }
        Json.decodeFromString<Receipt>(String(AccountStore.decrypt(data), Charsets.UTF_8))
            .takeIf { it.userId == user && it.server == server }
    }.getOrNull()
    fun save(receipt: Receipt) {
        val atomic = file(receipt.userId, receipt.server)
        val stream = atomic.startWrite()
        try {
            stream.write(AccountStore.encrypt(Json.encodeToString(Receipt.serializer(), receipt).toByteArray()))
            atomic.finishWrite(stream)
        } catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    fun clear(user: String, server: String) = file(user, server).delete()
}
