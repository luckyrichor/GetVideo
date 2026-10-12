package com.getvideo.app.data.repo

import okhttp3.HttpUrl

internal const val TX_SERVER_URL = "http://10.0.2.2:8900/"

internal fun effectiveServerUrl(saved: String?): String = when (saved?.trim()?.trimEnd('/')) {
    null, "", "http://127.0.0.1:18900", "http://127.0.0.1:8900", "http://10.0.2.2:8900" -> TX_SERVER_URL
    else -> saved.trim().trimEnd('/') + "/"
}

internal fun isCredentialOrigin(request: HttpUrl, origin: HttpUrl): Boolean =
    request.scheme == "https" && origin.scheme == "https" &&
        request.host == origin.host && request.port == origin.port
