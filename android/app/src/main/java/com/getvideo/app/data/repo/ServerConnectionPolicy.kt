package com.getvideo.app.data.repo

import okhttp3.HttpUrl

internal const val TX_SERVER_URL = "https://43.142.37.199:8443/"

internal fun effectiveServerUrl(saved: String?): String = when (saved?.trim()?.trimEnd('/')) {
    null, "http://10.0.2.2:8900" -> TX_SERVER_URL
    "" -> ""
    else -> saved.trim().trimEnd('/') + "/"
}

internal fun isCredentialOrigin(request: HttpUrl, origin: HttpUrl): Boolean =
    request.scheme == "https" && origin.scheme == "https" &&
        request.host == origin.host && request.port == origin.port
