package com.getvideo.app.data.repo

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val TX_SERVER_URL = "https://43.142.37.199/getvideo/"
internal const val TX_LEGACY_SERVER_URL = "https://43.142.37.199:8443/"

/** Only this explicitly known relocation shares an identity. Never alias arbitrary servers. */
internal fun canonicalServerUrl(saved: String): String {
    val normalized = saved.trim().trimEnd('/').let { if (it.isEmpty()) "" else "$it/" }
    return if (normalized == TX_LEGACY_SERVER_URL) TX_SERVER_URL else normalized
}

/** Preserve existing Room scope hashes, AI caches and encrypted Agent receipt filenames. */
internal fun serverIdentityUrl(server: String): String =
    canonicalServerUrl(server).let { if (it == TX_SERVER_URL) TX_LEGACY_SERVER_URL else it }

internal fun effectiveServerUrl(saved: String?): String = when (saved?.trim()?.trimEnd('/')) {
    null, "http://10.0.2.2:8900" -> TX_SERVER_URL
    "" -> ""
    else -> canonicalServerUrl(saved)
}

internal fun isCredentialOrigin(request: HttpUrl, origin: HttpUrl): Boolean =
    request.scheme == "https" && origin.scheme == "https" &&
        request.host == origin.host && request.port == origin.port &&
        request.username.isEmpty() && request.password.isEmpty() &&
        request.encodedPath.startsWith(origin.encodedPath.trimEnd('/') + "/") &&
        !Regex("(?i)%2f|%5c|%25").containsMatchIn(request.encodedPath)

/** API-root paths in JSON are relative to the configured service, never to the shared host. */
internal fun serverResourceUrl(server: String, resource: String): HttpUrl {
    val base = canonicalServerUrl(server).toHttpUrl()
    require(resource.isNotBlank() && !resource.startsWith("//") && !resource.contains('\\')) { "无效资源地址" }
    require(!Regex("(?i)%2e|%2f|%5c|%25").containsMatchIn(resource.substringBefore('?'))) { "无效资源路径" }
    require(resource.substringBefore('?').split('/').none { it == "." || it == ".." }) { "无效资源路径" }
    val absolute = resource.toHttpUrlOrNull()
    val path = if (absolute != null) {
        val legacy = base.toString() == TX_SERVER_URL && absolute.scheme == "https" &&
            absolute.host == base.host && absolute.port == 8443 && absolute.username.isEmpty() && absolute.password.isEmpty()
        require(legacy || isCredentialOrigin(absolute, base)) { "资源地址不属于当前服务" }
        absolute.encodedPath + (absolute.encodedQuery?.let { "?$it" } ?: "")
    } else {
        require(!resource.contains(":") && !resource.contains('#')) { "无效资源地址" }
        resource
    }
    val prefix = base.encodedPath.trim('/')
    val relative = path.removePrefix("/").let { if (prefix.isNotEmpty()) it.removePrefix("$prefix/") else it }
    val result = requireNotNull(base.resolve(relative)) { "无效资源地址" }
    require(result.host == base.host && result.port == base.port && result.scheme == base.scheme &&
        result.encodedPath.startsWith(base.encodedPath)) { "资源路径超出服务范围" }
    return result
}
