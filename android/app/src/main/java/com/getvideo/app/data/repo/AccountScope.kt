package com.getvideo.app.data.repo

import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrl

fun cloudScopeKey(server: String, userId: String): String = MessageDigest.getInstance("SHA-256")
    .digest((server.trim().trimEnd('/').toHttpUrl().toString() + "|" + userId).toByteArray())
    .joinToString("") { "%02x".format(it) }

fun chooseCloudLocalId(remoteId: Long, used: Set<Long>): Long {
    require(remoteId > 0)
    if (remoteId !in used) return remoteId
    return Math.addExact(used.filter { it > 0 }.maxOrNull() ?: 0, 1)
}

fun accountResponseIsCurrent(original: AccountStore.Account?, current: AccountStore.Account?, server: String): Boolean =
    original != null && original == current && original.serverUrl == server
