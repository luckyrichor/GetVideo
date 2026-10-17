package com.getvideo.app.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

private val Context.dataStore by preferencesDataStore("settings")

/** 可选同步服务器地址和用户 id；手机直接提取不需要服务器。 */
class Settings(private val context: Context) {

    // Independent TX HTTPS endpoint; an explicitly saved empty value remains local-only.
    companion object { const val DEFAULT_SERVER_URL = TX_SERVER_URL }

    private val serverKey = stringPreferencesKey("server_url")
    private val userKey = stringPreferencesKey("user_id")

    val serverUrl: Flow<String> = context.dataStore.data.map {
        effectiveServerUrl(it[serverKey])
    }

    suspend fun setServerUrl(url: String) {
        // 结尾必须有斜杠，否则 Retrofit 会把 baseUrl 的最后一段吃掉
        val normalized = url.trim().takeIf { it.isNotEmpty() }?.trimEnd('/')?.plus("/") ?: ""
        // Once committed, finish account visibility and queue reconciliation even if the dialog closes.
        withContext(NonCancellable) {
            context.dataStore.edit { it[serverKey] = normalized }
            AccountStore.refreshVisibility(context)
        }
    }

    suspend fun serverUrlNow(): String = serverUrl.first()

    suspend fun userId(): String {
        AccountStore.forServer(serverUrlNow())?.let { return it.userId }
        val existing = context.dataStore.data.map { it[userKey] }.first()
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString()
        context.dataStore.edit { it[userKey] = fresh }
        return fresh
    }
}
